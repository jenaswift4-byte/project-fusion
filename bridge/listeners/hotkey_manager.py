"""
全局热键系统
使用 keyboard 库注册全局快捷键，把手机当作 PC 外设来操控

手机作为 PC 外设扩展板:
  热键绑定:
    - Win+Shift+S: 手机截图 → 拉取到 PC + 剪贴板
    - Win+Shift+V: 粘贴手机剪贴板内容到 PC
    - Win+Shift+P: 聚焦/切换 Scrcpy 窗口
    - Win+Shift+H: 手机 HOME 键
    - Win+Shift+B: 手机 BACK 键
    - Win+Shift+R: 手机 Recent (多任务) 键
    - Win+Shift+L: 手机锁屏
    - Win+Shift+N: 手机展开通知栏
    - Win+Shift+M: 手机静音/取消静音
    - Win+Shift+F: 手机文件推送到 PC (拉取 Download)
    - Win+Shift+W: 打开手机 WiFi 设置
    - Win+Shift+E: 手机音量加/减
"""

import logging
import threading

logger = logging.getLogger(__name__)


class HotkeyManager:
    """全局热键管理器"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._keyboard = None
        self._registered = []

        # 配置
        hk_cfg = daemon.config.get("hotkeys", {})
        self.enabled = hk_cfg.get("enabled", True)
        self.prefix = hk_cfg.get("prefix", "win+shift")

    def start(self):
        """启动热键监听"""
        if not self.enabled:
            logger.info("全局热键已禁用 (配置中 hotkeys.enabled=false)")
            return

        try:
            import keyboard
            self._keyboard = keyboard
        except ImportError:
            logger.warning("keyboard 库未安装，全局热键不可用")
            logger.warning("安装: pip install keyboard")
            return

        self._register_hotkeys()
        self.running = True
        logger.info("全局热键已启动")

    def stop(self):
        """停止热键监听"""
        if self._keyboard and self._registered:
            for hk in self._registered:
                try:
                    self._keyboard.remove_hotkey(hk)
                except Exception:
                    pass
            self._registered.clear()
        self.running = False
        logger.info("全局热键已停止")

    def _register_hotkeys(self):
        """注册所有热键"""
        kb = self._keyboard
        p = self.prefix

        hotkey_map = {
            f"{p}+s": ("📱 手机截图", self._hotkey_screenshot),
            f"{p}+v": ("📋 粘贴手机剪贴板", self._hotkey_paste_phone_clipboard),
            f"{p}+p": ("🖥️ 聚焦手机窗口", self._hotkey_focus_scrcpy),
            f"{p}+h": ("🏠 HOME", self._hotkey_home),
            f"{p}+b": ("⬅️ BACK", self._hotkey_back),
            f"{p}+r": ("📱 多任务", self._hotkey_recent),
            f"{p}+l": ("🔒 锁屏", self._hotkey_lock),
            f"{p}+n": ("🔔 通知栏", self._hotkey_notifications),
            f"{p}+m": ("🔇 静音切换", self._hotkey_mute_toggle),
            f"{p}+up": ("🔊 音量+", self._hotkey_vol_up),
            f"{p}+down": ("🔉 音量-", self._hotkey_vol_down),
            f"{p}+f": ("📁 拉取手机文件", self._hotkey_pull_file),
            f"{p}+w": ("📶 WiFi 设置", self._hotkey_wifi_settings),
            f"{p}+e": ("📤 发送选中文件到手机", self._hotkey_push_selection),
        }

        for combo, (desc, handler) in hotkey_map.items():
            try:
                hk = kb.add_hotkey(combo, handler, suppress=False)
                self._registered.append(hk)
                logger.debug(f"热键注册: {combo} → {desc}")
            except Exception as e:
                logger.warning(f"热键注册失败 {combo}: {e}")

        logger.info(f"已注册 {len(self._registered)} 个全局热键")

    # ══════════════════════════════════════
    # 热键处理函数
    # ══════════════════════════════════════

    def _hotkey_screenshot(self):
        """手机截图"""
        logger.info("[热键] 手机截图")
        if hasattr(self.daemon, 'screenshot_bridge') and self.daemon.screenshot_bridge:
            self.daemon.screenshot_bridge.take_screenshot_async()
        else:
            # 直接用 ADB
            import time
            timestamp = time.strftime("%Y%m%d_%H%M%S")
            save_dir = self.daemon.config.get("screenshot", {}).get(
                "save_dir",
                self.daemon.config.get("paths", {}).get("clipboard_images", r"D:\Fusion\Screenshots")
            )
            import os
            os.makedirs(save_dir, exist_ok=True)
            phone_path = f"/sdcard/Pictures/fusion_screenshot_{timestamp}.png"
            local_path = os.path.join(save_dir, f"phone_{timestamp}.png")
            self.daemon.adb_shell(f"screencap -p {phone_path}", capture=False)
            threading.Thread(
                target=self._pull_screenshot,
                args=(phone_path, local_path),
                daemon=True,
            ).start()

    def _pull_screenshot(self, phone_path, local_path):
        """异步拉取截图"""
        import time, subprocess
        time.sleep(0.5)
        device_arg = ["-s", self.daemon.device_serial] if self.daemon.device_serial else []
        result = subprocess.run(
            [self.daemon.adb_path] + device_arg + ["pull", phone_path, local_path],
            capture_output=True, text=True, timeout=15,
        )
        if result.returncode == 0 and os.path.exists(local_path):
            self.daemon.adb_shell(f"rm {phone_path}", capture=False)
            logger.info(f"[截图] 已保存: {local_path}")
        else:
            logger.error(f"[截图] 拉取失败")

    def _hotkey_paste_phone_clipboard(self):
        """粘贴手机剪贴板到 PC"""
        logger.info("[热键] 粘贴手机剪贴板")
        # 通过 ADB 获取手机剪贴板
        output, _, rc = self.daemon.adb_shell(
            "am broadcast -n com.fusion.companion/.ClipboardReceiver 2>/dev/null; "
            "service call clipboard 2 s16 com.fusion.companion s16 '' 2>/dev/null || true"
        )
        # 更简单的方式: 如果 WS 连接，请求手机推送剪贴板
        if self.daemon.use_companion and self.daemon.ws_client:
            self.daemon.ws_client.send_json({"type": "get_clipboard"})

    def _hotkey_focus_scrcpy(self):
        """聚焦/切换 Scrcpy 窗口"""
        logger.info("[热键] 聚焦手机窗口")
        if self.daemon.scrcpy_ctrl:
            self.daemon.scrcpy_ctrl.bring_to_front()

    def _hotkey_home(self):
        """HOME 键"""
        self.daemon.adb_shell("input keyevent 3", capture=False)
        logger.debug("[热键] HOME")

    def _hotkey_back(self):
        """BACK 键"""
        self.daemon.adb_shell("input keyevent 4", capture=False)
        logger.debug("[热键] BACK")

    def _hotkey_recent(self):
        """Recent (多任务) 键"""
        self.daemon.adb_shell("input keyevent 187", capture=False)
        logger.debug("[热键] Recent")

    def _hotkey_lock(self):
        """锁屏"""
        self.daemon.adb_shell("input keyevent 26", capture=False)
        logger.debug("[热键] 锁屏")

    def _hotkey_notifications(self):
        """展开通知栏"""
        self.daemon.adb_shell("cmd statusbar expand-notifications", capture=False)
        logger.debug("[热键] 通知栏")

    def _hotkey_mute_toggle(self):
        """静音切换"""
        # 切换静音模式
        self.daemon.adb_shell(
            "input keyevent 164",  # KEYCODE_VOLUME_MUTE
            capture=False,
        )
        logger.debug("[热键] 静音切换")

    def _hotkey_vol_up(self):
        """音量+"""
        self.daemon.adb_shell("input keyevent 24", capture=False)
        logger.debug("[热键] 音量+")

    def _hotkey_vol_down(self):
        """音量-"""
        self.daemon.adb_shell("input keyevent 25", capture=False)
        logger.debug("[热键] 音量-")

    def _hotkey_pull_file(self):
        """拉取手机 Download 目录最新文件"""
        logger.info("[热键] 拉取手机文件")
        pull_target = self.daemon.config.get("file", {}).get("pull_target", r"C:\Users\wang\Downloads")
        # 拉取 Download 目录下最新修改的文件
        output, _, rc = self.daemon.adb_shell(
            "ls -t /sdcard/Download/ | head -5"
        )
        if output:
            files = [f.strip() for f in output.strip().split("\n") if f.strip()]
            if files and self.daemon.file_bridge:
                for f in files[:3]:  # 最多拉3个
                    self.daemon.file_bridge.pull(f"/sdcard/Download/{f}", pull_target)

    def _hotkey_wifi_settings(self):
        """打开手机 WiFi 设置"""
        self.daemon.adb_shell(
            "am start -a android.settings.WIFI_SETTINGS",
            capture=False,
        )
        logger.debug("[热键] WiFi 设置")

    def _hotkey_push_selection(self):
        """发送 PC 剪贴板内容/选中文件到手机"""
        logger.info("[热键] 推送到手机")
        try:
            from bridge.utils.win32_clipboard import get_clipboard_text
            text = get_clipboard_text()
            if text:
                if self.daemon.use_companion:
                    self.daemon.ws_client.set_clipboard(text)
                else:
                    self.daemon.clipboard_bridge._sync_to_phone(text)
                logger.info(f"[热键] 已推送文本到手机: {text[:30]}")
        except Exception as e:
            logger.warning(f"[热键] 推送失败: {e}")
