"""
Project Fusion - Bridge Daemon 主入口 (v2)
串联 KDE Connect / Scrcpy / Input Leap / WebSocket，实现 Android + Windows 系统级融合

双通道架构:
  - 主通道: WebSocket (Android 伴侣 App，实时推送，零延迟)
  - 备用通道: ADB shell (无需安装 App，轮询方案)

用法:
    python -m bridge                          # 启动全部模块
    python -m bridge --no-notification         # 禁用通知桥接
    python -m bridge --no-companion            # 不使用 Android 伴侣 App
    python -m bridge --list-devices            # 列出已连接设备
    python -m bridge --autostart               # 注册开机自启
    python -m bridge --remove-autostart        # 取消开机自启
"""

import sys
import os
import time
import logging
import argparse
import subprocess

from bridge.config import load_config
from bridge.utils.scrcpy_ctrl import ScrcpyController
from bridge.utils.win32_clipboard import get_clipboard_text, set_clipboard_text
from bridge.utils.win32_toast import send_toast
from bridge.modules.clipboard_bridge import ClipboardBridge
from bridge.modules.notification_bridge import NotificationBridge
from bridge.modules.file_bridge import FileBridge
from bridge.modules.phone_bridge import PhoneBridge
from bridge.modules.battery_bridge import BatteryBridge
from bridge.modules.screenshot_bridge import ScreenshotBridge
from bridge.modules.sms_bridge import SMSBridge
from bridge.modules.handoff_bridge import HandoffBridge
from bridge.modules.audio_bridge import AudioBridge
from bridge.listeners.kde_connect import KDEConnectListener
from bridge.listeners.window_focus import WindowFocusListener
from bridge.listeners.clipboard_hook import ClipboardChangeListener
from bridge.listeners.mouse_hook import EdgeDetector
from bridge.listeners.ws_client import FusionWSClient
from bridge.listeners.hotkey_manager import HotkeyManager
from bridge.listeners.dnd_manager import DNDManager
from bridge.listeners.proximity_detector import ProximityDetector
from bridge.dispatchers.input_leap import InputLeapController
from bridge.dispatchers.tray_icon import TrayIcon
from bridge.dispatchers.autostart import enable_autostart, disable_autostart, is_autostart_enabled

logger = logging.getLogger(__name__)


def setup_logging(config: dict):
    """配置日志"""
    log_dir = config.get("paths", {}).get("logs", r"D:\Fusion\Logs")
    os.makedirs(log_dir, exist_ok=True)

    log_file = os.path.join(log_dir, f"fusion_{time.strftime('%Y%m%d')}.log")

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
        handlers=[
            logging.FileHandler(log_file, encoding="utf-8"),
            logging.StreamHandler(sys.stdout),
        ],
    )


class BridgeDaemon:
    """Fusion 守护进程 - 双通道架构"""

    def __init__(self, config: dict):
        self.config = config
        self.running = False
        self.device_serial = None

        # ADB 路径
        self.adb_path = config.get("adb", {}).get("path", "adb")

        # Scrcpy 控制器
        self.scrcpy_ctrl = ScrcpyController(config)

        # 基础模块 (ADB 备用方案)
        self.clipboard_bridge = ClipboardBridge(self)
        self.notification_bridge = NotificationBridge(self)
        self.file_bridge = FileBridge(self)
        self.phone_bridge = PhoneBridge(self)

        # 扩展模块 (手机作为 PC 外设)
        self.battery_bridge = BatteryBridge(self)      # 电池状态 → 托盘显示
        self.screenshot_bridge = ScreenshotBridge(self) # 快捷截图
        self.sms_bridge = SMSBridge(self)               # 短信收发
        self.handoff_bridge = HandoffBridge(self)        # 链接接力
        self.audio_bridge = AudioBridge(self)            # 音频流转

        # 实时通道
        self.ws_client = FusionWSClient(config)  # WebSocket 主通道
        self.kde_listener = KDEConnectListener(config)  # KDE Connect

        # Win32 实时钩子
        self.clipboard_hook = ClipboardChangeListener()  # 替代剪贴板轮询
        self.edge_detector = EdgeDetector(  # 屏幕边缘检测
            edge_side="right",
            trigger_pixels=5,
        )
        self.focus_listener = WindowFocusListener(
            config.get("scrcpy", {}).get("window_title", "Phone")
        )

        # 全局热键 (手机当 PC 外设的操控入口)
        self.hotkey_manager = HotkeyManager(self)

        # 智能免打扰 (全屏检测 + 通知缓存)
        self.dnd_manager = DNDManager(self)

        # 近场自动连接 (蓝牙 RSSI)
        self.proximity_detector = ProximityDetector(self)

        # Input Leap 键鼠流转
        self.input_leap = InputLeapController(config)

        # 系统托盘
        self.tray_icon = TrayIcon(self)

        # 通信模式标记
        self.use_companion = False  # 是否使用伴侣 App

    def adb_shell(self, command: str, capture: bool = True):
        """执行 ADB shell 命令 (备用通道)"""
        device_arg = ["-s", self.device_serial] if self.device_serial else []
        cmd = [self.adb_path] + device_arg + ["shell", command]
        try:
            if capture:
                result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
                return result.stdout.strip(), result.stderr.strip(), result.returncode
            else:
                subprocess.Popen(cmd)
                return None, None, 0
        except subprocess.TimeoutExpired:
            return None, "Timeout", 1
        except Exception as e:
            return None, str(e), 1

    def get_device_info(self) -> dict:
        """获取设备信息"""
        output, _, _ = self.adb_shell("getprop ro.product.model")
        model = output if output else "Unknown"
        output, _, _ = self.adb_shell("getprop ro.build.version.release")
        android_version = output if output else "Unknown"
        return {"model": model, "android_version": android_version}

    def list_devices(self):
        """列出已连接设备"""
        try:
            result = subprocess.run(
                [self.adb_path, "devices", "-l"],
                capture_output=True, text=True, timeout=10,
            )
            print(result.stdout)
        except Exception as e:
            print(f"获取设备列表失败: {e}")

    def auto_connect_wifi(self):
        """尝试 WiFi ADB 连接"""
        if not self.config.get("adb", {}).get("wifi_auto_connect", True):
            return

        try:
            result = subprocess.run(
                [self.adb_path, "devices"],
                capture_output=True, text=True, timeout=10,
            )
            lines = result.stdout.strip().split("\n")[1:]
            usb_devices = [
                l.split()[0] for l in lines
                if "device" in l and not l.startswith("List")
            ]

            if usb_devices:
                self.device_serial = usb_devices[0]
                output, _, _ = self.adb_shell(
                    "ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}' | cut -d/ -f1"
                )
                if output:
                    wifi_port = self.config.get("adb", {}).get("wifi_port", 5555)
                    subprocess.run(
                        [self.adb_path, "-s", self.device_serial, "tcpip", str(wifi_port)],
                        capture_output=True, timeout=10,
                    )
                    time.sleep(1)
                    subprocess.run(
                        [self.adb_path, "connect", f"{output}:{wifi_port}"],
                        capture_output=True, timeout=10,
                    )
                    logging.info(f"WiFi ADB 已连接: {output}:{wifi_port}")
        except Exception as e:
            logging.warning(f"WiFi ADB 自动连接失败: {e}")

    def start(self, enable_scrcpy=True, enable_clipboard=True,
              enable_notification=True, enable_file=True, enable_phone=True,
              enable_tray=True, enable_companion=True):
        """启动所有模块"""
        self.running = True

        print()
        print("=" * 60)
        print("    Project Fusion - Android + Windows 系统级融合")
        print("=" * 60)
        print()

        # ═══ Phase 1: 设备连接 ═══
        print("[1/9] 设备连接...")
        self.auto_connect_wifi()
        device_info = self.get_device_info()
        print(f"  设备: {device_info['model']} (Android {device_info['android_version']})")
        print()

        # ═══ Phase 2: Scrcpy 投屏 ═══
        if enable_scrcpy:
            print("[2/9] Scrcpy 投屏...")
            self.scrcpy_ctrl.start()
        else:
            print("[2/9] Scrcpy 已禁用")
        print()

        # ═══ Phase 3: 实时通道 (WebSocket 伴侣 App) ═══
        print("[3/9] 实时通信通道...")
        if enable_companion:
            self._setup_ws_handlers()
            if self.ws_client.start():
                self.use_companion = True
                print("  ✅ WebSocket 伴侣通道 (零延迟推送)")
            else:
                print("  ⏭️ 伴侣 App 未连接，使用 ADB 备用通道")
        else:
            print("  ⏭️ 伴侣 App 已禁用")
        print()

        # ═══ Phase 4: Win32 实时钩子 ═══
        print("[4/9] Win32 实时钩子...")

        # 剪贴板实时监听 (替代轮询)
        if enable_clipboard:
            self.clipboard_hook.on_change(self._on_pc_clipboard_changed)
            self.clipboard_hook.start()
            print("  ✅ 剪贴板实时监听 (Win32 Hook，零延迟)")

        # 屏幕边缘检测 (键鼠流转触发)
        self.edge_detector.on_edge_enter(self._on_mouse_edge_enter)
        self.edge_detector.on_edge_leave(self._on_mouse_edge_leave)
        self.edge_detector.start()
        print("  ✅ 屏幕边缘检测 (鼠标穿越)")

        # 窗口焦点监听
        self.focus_listener.on_focus_gained(self._on_scrcpy_focused)
        self.focus_listener.on_focus_lost(self._on_scrcpy_unfocused)
        self.focus_listener.start()
        print("  ✅ 窗口焦点监听")
        print()

        # ═══ Phase 5: 功能模块 ═══
        print("[5/9] 功能模块...")

        if enable_clipboard:
            if not self.use_companion:
                # ADB 备用: 启动轮询
                self.clipboard_bridge.start()
                print("  ✅ 剪贴板同步 (ADB 轮询)")
            else:
                print("  ✅ 剪贴板同步 (WebSocket 实时)")

        if enable_notification:
            if not self.use_companion:
                self.notification_bridge.start()
                print("  ✅ 通知桥接 (ADB 轮询)")
            else:
                print("  ✅ 通知桥接 (WebSocket 实时)")

        if enable_file:
            self.file_bridge.start()
            print("  ✅ 文件传输")

        if enable_phone:
            if not self.use_companion:
                self.phone_bridge.start()
                print("  ✅ 通话控制 (ADB 轮询)")
            else:
                print("  ✅ 通话控制 (WebSocket 实时)")

        # 扩展模块: 手机作为 PC 外设
        print("  ✅ 电池状态监控")
        self.battery_bridge.start()

        print("  ✅ 快捷截图")
        self.screenshot_bridge.start()

        sms_cfg = self.config.get("sms", {})
        if sms_cfg.get("enabled", True):
            print("  ✅ 短信收发")
            self.sms_bridge.start()

        handoff_cfg = self.config.get("handoff", {})
        if handoff_cfg.get("enabled", True):
            print("  ✅ 链接接力 (Handoff)")
            self.handoff_bridge.start()

        audio_cfg = self.config.get("audio", {})
        if audio_cfg.get("enabled", True):
            print("  ✅ 音频流转")
            self.audio_bridge.start()
        print()

        # ═══ Phase 6: KDE Connect ═══
        print("[6/9] KDE Connect...")
        if self.kde_listener.start():
            print("  ✅ KDE Connect 已连接")
            self.kde_listener.on("notification", self._on_kde_notification)
            self.kde_listener.on("clipboard", self._on_kde_clipboard)
            self.kde_listener.on("telephony", self._on_kde_telephony)
        else:
            print("  ⏭️ KDE Connect 未启用")
        print()

        # ═══ Phase 7: 全局热键 ═══
        print("[7/11] 全局热键...")
        self.hotkey_manager.start()
        if self.hotkey_manager.running:
            print("  ✅ 全局热键 (Win+Shift+S 截图 / P 聚焦 / H HOME / B BACK ...)")
        else:
            print("  ⏭️ 全局热键未启用")
        print()

        # ═══ Phase 8: 智能免打扰 ═══
        print("[8/11] 智能免打扰...")
        self.dnd_manager.start()
        if self.dnd_manager.running:
            print("  ✅ 全屏自动静默通知")
        else:
            print("  ⏭️ 智能免打扰未启用")
        print()

        # ═══ Phase 9: 近场检测 ═══
        print("[9/11] 近场检测...")
        self.proximity_detector.start()
        if self.proximity_detector.running:
            print("  ✅ 蓝牙近场自动连接")
        else:
            print("  ⏭️ 近场检测未启用")
        print()

        # ═══ Phase 10: Input Leap ═══
        print("[10/11] 键鼠流转...")
        if self.input_leap.start():
            print("  ✅ Input Leap 已启动")
        else:
            print("  ⏭️ Input Leap 未启用 (使用边缘检测替代)")
        print()

        # ═══ Phase 11: 系统托盘 ═══
        if enable_tray:
            print("[11/11] 系统托盘...")
            self.tray_icon.start()
            print("  ✅ 托盘图标已显示")
        else:
            print("[11/11] 系统托盘已禁用")
        print()

        # ═══ 完成 ═══
        channel = "WebSocket 实时" if self.use_companion else "ADB 轮询"
        print(f"就绪! 通信模式: {channel}")
        print()
        print("-" * 60)
        print("  Ctrl+C 停止 / 托盘右键退出 / 鼠标右滑进入手机")
        print("  Win+Shift+S 截图 / P 聚焦 / H HOME / B BACK / R 多任务")
        print("-" * 60)
        print()

        self.tray_icon.update_status("运行中" + (" [实时]" if self.use_companion else ""))

        # 主循环
        try:
            while self.running:
                if enable_scrcpy and not self.scrcpy_ctrl.is_running():
                    logging.debug("Scrcpy 进程已退出")
                time.sleep(1)
        except KeyboardInterrupt:
            self.stop()

    def stop(self):
        """停止所有模块"""
        print("\n正在停止 Project Fusion...")
        self.running = False

        self.tray_icon.stop()
        self.edge_detector.stop()
        self.clipboard_hook.stop()
        self.focus_listener.stop()
        self.hotkey_manager.stop()
        self.dnd_manager.stop()
        self.proximity_detector.stop()
        self.input_leap.stop()
        self.ws_client.stop()
        self.kde_listener.stop()
        self.clipboard_bridge.stop()
        self.notification_bridge.stop()
        self.phone_bridge.stop()
        self.battery_bridge.stop()
        self.screenshot_bridge.stop()
        self.sms_bridge.stop()
        self.handoff_bridge.stop()
        self.audio_bridge.stop()
        self.scrcpy_ctrl.stop()

        print("Project Fusion 已停止")

    # ══════════════════════════════════════
    # WebSocket 事件处理器 (伴侣 App 实时通道)
    # ══════════════════════════════════════

    def _setup_ws_handlers(self):
        """注册 WebSocket 事件处理器"""
        self.ws_client.on("notification", self._on_ws_notification)
        self.ws_client.on("clipboard", self._on_ws_clipboard)
        self.ws_client.on("telephony", self._on_ws_telephony)
        self.ws_client.on("sms", self._on_ws_sms)
        self.ws_client.on("battery", self._on_ws_battery)
        self.ws_client.on("connected", self._on_ws_connected)

    def _on_ws_notification(self, data: dict):
        """WebSocket: 手机通知"""
        title = data.get("title", "")
        text = data.get("text", "")
        pkg = data.get("package", "unknown")

        if not title:
            return

        # 智能免打扰: 全屏时缓存通知
        if self.dnd_manager.running and self.dnd_manager.should_suppress(pkg):
            self.dnd_manager.cache_notification(data)
            return

        send_toast(
            title=f"[{NotificationBridge._package_to_name(pkg)}] {title}",
            text=text[:200] if text else "",
            app_name="Project Fusion",
        )

        if self.config.get("notification", {}).get("auto_focus_scrcpy", True):
            self.scrcpy_ctrl.bring_to_front()

        logger.info(f"[WS通知] {title}: {(text or '')[:50]}")

    def _on_ws_clipboard(self, data: dict):
        """WebSocket: 剪贴板变化"""
        content = data.get("content", "")
        content_type = data.get("contentType", "text")

        if not content:
            return

        # 抑制剪贴板 Hook 回弹 (自身设置会触发 WM_CLIPBOARDUPDATE)
        self.clipboard_hook.suppress_next()

        if content_type == "url" and self.config.get("clipboard", {}).get("auto_open_urls", True):
            set_clipboard_text(content)
            try:
                os.startfile(content)
            except Exception:
                pass
            logger.info(f"[WS剪贴板] 链接已打开: {content[:60]}")
        else:
            set_clipboard_text(content)
            logger.info(f"[WS剪贴板] 文本已同步: {content[:40]}")

    def _on_ws_telephony(self, data: dict):
        """WebSocket: 通话状态"""
        state = data.get("state", "")
        if state == "RINGING":
            from bridge.utils.win32_toast import send_toast_with_action
            send_toast_with_action(
                title="来电",
                text="手机有来电，点击查看",
                action_label="查看手机",
                app_name="Project Fusion",
            )
            if self.config.get("telephony", {}).get("auto_focus_scrcpy", True):
                self.scrcpy_ctrl.bring_to_front()
            logger.info("[WS通话] 来电!")

    def _on_ws_connected(self, data: dict):
        """WebSocket: 连接成功"""
        device = data.get("device", "Unknown")
        version = data.get("androidVersion", "?")
        logger.info(f"伴侣 App 已连接: {device} (Android {version})")

    def _on_ws_sms(self, data: dict):
        """WebSocket: 短信"""
        self.sms_bridge.on_ws_sms(data)

    def _on_ws_battery(self, data: dict):
        """WebSocket: 电池状态 (来自伴侣 App)"""
        level = data.get("level", -1)
        charging = data.get("charging", False)
        # 直接更新电池桥接的状态
        self.battery_bridge._last_level = level
        self.battery_bridge._last_charging = charging
        charge_icon = "⚡" if charging else "🔋"
        if self.tray_icon:
            self.tray_icon.update_battery(f"{charge_icon} {level}%")
        logger.debug(f"[WS电池] {level}% {'充电中' if charging else '放电中'}")

    # ══════════════════════════════════════
    # Win32 实时钩子回调
    # ══════════════════════════════════════

    def _on_pc_clipboard_changed(self):
        """Win32: PC 剪贴板变化 (实时钩子)"""
        # WM_CLIPBOARDUPDATE 到达时，发送方可能还没关闭剪贴板，需要重试
        text = None
        for attempt in range(3):
            text = get_clipboard_text()
            if text:
                break
            time.sleep(0.1)  # 等 100ms 重试

        if not text:
            logger.debug("剪贴板变化但读取为空 (可能被占用)")
            return

        # 判断是否包含 URL (用正则提取，支持前面有中文标点的情况)
        import re
        url_match = re.search(r'(https?://[^\s<>\"]+)', text)
        detected_url = url_match.group(1) if url_match else None

        # 推送到手机
        if self.use_companion:
            # WebSocket 通道: 实时推送到伴侣 App
            self.ws_client.set_clipboard(text)
            # URL 自动接力: PC 复制链接 → 手机自动打开
            if detected_url and self.handoff_bridge.running and self.handoff_bridge.open_on_phone:
                self.ws_client.open_url(detected_url)
                logger.info(f"[PC剪贴板变化] URL 已接力到手机: {detected_url[:60]}")
            else:
                logger.info(f"[PC剪贴板变化] {text[:40]}")
        else:
            # ADB 通道
            self.clipboard_bridge.last_pc_clipboard = text
            self.clipboard_bridge._sync_to_phone(text)
            # URL 自动接力
            if detected_url and self.handoff_bridge.running and self.handoff_bridge.open_on_phone:
                self.handoff_bridge.send_url_to_phone(detected_url)
                logger.info(f"[PC剪贴板变化] URL 已接力到手机: {detected_url[:60]}")
            else:
                logger.info(f"[PC剪贴板变化] {text[:40]}")

    def _on_mouse_edge_enter(self, x, y):
        """鼠标到达屏幕右边缘 → 进入手机区域"""
        self.scrcpy_ctrl.bring_to_front()
        self.tray_icon.update_status("操控手机中 [实时]")
        logger.debug("鼠标进入手机区域")

    def _on_mouse_edge_leave(self, x, y):
        """鼠标离开边缘 → 回到 Windows"""
        self.tray_icon.update_status("运行中" + (" [实时]" if self.use_companion else ""))
        logger.debug("鼠标回到 Windows")

    def _on_scrcpy_focused(self):
        """Scrcpy 获得焦点"""
        if self.input_leap and self.input_leap.enabled:
            self.input_leap.mouse_on_phone = True
            self.input_leap.keyboard_captured = True
        self.tray_icon.update_status("操控手机中")

    def _on_scrcpy_unfocused(self):
        """Scrcpy 失去焦点"""
        if self.input_leap and self.input_leap.enabled:
            self.input_leap.mouse_on_phone = False
            self.input_leap.keyboard_captured = False
        self.tray_icon.update_status("运行中")

    # ══════════════════════════════════════
    # KDE Connect 回调
    # ══════════════════════════════════════

    def _on_kde_notification(self, data: dict):
        title = data.get("title", data.get("appName", ""))
        text = data.get("text", data.get("body", ""))
        pkg = data.get("appName", "unknown")
        self.notification_bridge._send_windows_notification(title, text, pkg)
        if self.config.get("notification", {}).get("auto_focus_scrcpy", True):
            self.scrcpy_ctrl.bring_to_front()

    def _on_kde_clipboard(self, data: dict):
        content = data.get("content", "")
        if content:
            self.clipboard_bridge._handle_phone_content(content)

    def _on_kde_telephony(self, data: dict):
        event = data.get("event", "")
        if event == "ringing":
            self.phone_bridge._on_call_state_change("idle", "ringing")


def main():
    parser = argparse.ArgumentParser(
        description="Project Fusion - 跨设备系统级融合",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python -m bridge                        # 启动全部
  python -m bridge --no-companion         # 不使用伴侣 App (纯 ADB)
  python -m bridge --no-scrcpy            # 不启动 Scrcpy 窗口
  python -m bridge --no-tray              # 不显示系统托盘
  python -m bridge --list-devices         # 查看已连接设备
  python -m bridge --autostart            # 注册开机自启
        """,
    )
    parser.add_argument("-c", "--config", help="配置文件路径", default=None)
    parser.add_argument("-s", "--serial", help="设备序列号", default=None)
    parser.add_argument("--no-scrcpy", action="store_true", help="不启动 Scrcpy")
    parser.add_argument("--no-notification", action="store_true", help="禁用通知桥接")
    parser.add_argument("--no-file", action="store_true", help="禁用文件传输")
    parser.add_argument("--no-clipboard", action="store_true", help="禁用剪贴板同步")
    parser.add_argument("--no-phone", action="store_true", help="禁用通话控制")
    parser.add_argument("--no-tray", action="store_true", help="不显示系统托盘")
    parser.add_argument("--no-companion", action="store_true", help="不使用 Android 伴侣 App")
    parser.add_argument("--list-devices", action="store_true", help="列出已连接设备")
    parser.add_argument("--autostart", action="store_true", help="注册开机自启")
    parser.add_argument("--remove-autostart", action="store_true", help="取消开机自启")

    args = parser.parse_args()

    config = load_config(args.config)
    setup_logging(config)

    daemon = BridgeDaemon(config)

    if args.serial:
        daemon.device_serial = args.serial

    if args.list_devices:
        daemon.list_devices()
        return

    if args.autostart:
        if enable_autostart():
            print("✅ 开机自启已注册")
        else:
            print("❌ 注册开机自启失败")
        return

    if args.remove_autostart:
        if disable_autostart():
            print("✅ 开机自启已取消")
        else:
            print("❌ 取消开机自启失败")
        return

    daemon.start(
        enable_scrcpy=not args.no_scrcpy,
        enable_clipboard=not args.no_clipboard,
        enable_notification=not args.no_notification,
        enable_file=not args.no_file,
        enable_phone=not args.no_phone,
        enable_tray=not args.no_tray,
        enable_companion=not args.no_companion,
    )


if __name__ == "__main__":
    main()
