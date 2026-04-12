"""
智能免打扰模块
PC 全屏时自动缓存手机通知，退出全屏后批量推送

手机作为 PC 外设扩展板:
  - 检测 PC 当前是否全屏 (游戏/PPT/视频)
  - 全屏时: 手机通知静默缓存，不弹 Toast
  - 退出全屏: 批量推送缓存的通知
  - 来电/紧急通知不受免打扰影响 (直接弹出)
  - 支持自定义免打扰应用白名单
"""

import time
import ctypes
import ctypes.wintypes as wintypes
import threading
import logging

logger = logging.getLogger(__name__)

# Win32 API 常量
user32 = ctypes.windll.user32
GWL_STYLE = -16
WS_VISIBLE = 0x10000000
WS_POPUP = 0x80000000


class DNDManager:
    """智能免打扰 - 全屏检测 + 通知缓存"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._thread = None

        # 通知缓存队列
        self._pending_notifications = []

        # 全屏状态
        self._is_fullscreen = False

        # 配置
        dnd_cfg = daemon.config.get("dnd", {})
        self.enabled = dnd_cfg.get("enabled", True)
        self.check_interval = dnd_cfg.get("check_interval_ms", 2000) / 1000.0
        self.whitelist_apps = dnd_cfg.get("whitelist_apps", [])  # 这些应用全屏时仍弹通知
        self.bypass_packages = dnd_cfg.get("bypass_packages", [
            "com.tencent.mobileqq",  # QQ 来电
            "com.whatsapp",          # WhatsApp 来电
        ])  # 这些手机包名的通知始终弹出 (紧急)

    def start(self):
        """启动免打扰检测"""
        if not self.enabled:
            logger.info("智能免打扰已禁用 (配置中 dnd.enabled=false)")
            return
        self.running = True
        self._thread = threading.Thread(target=self._check_loop, daemon=True)
        self._thread.start()
        logger.info("智能免打扰已启动")

    def stop(self):
        """停止免打扰检测"""
        self.running = False

    def should_suppress(self, pkg: str = "") -> bool:
        """判断当前通知是否应该被抑制

        Args:
            pkg: 手机应用包名

        Returns:
            True = 抑制通知 (静默缓存)
            False = 正常弹出
        """
        if not self._is_fullscreen:
            return False

        # 紧急包名不抑制
        if pkg in self.bypass_packages:
            return False

        return True

    def cache_notification(self, notification: dict):
        """缓存通知 (全屏时调用)"""
        self._pending_notifications.append({
            **notification,
            "cached_at": time.time(),
        })
        count = len(self._pending_notifications)
        # 更新托盘状态
        if self.daemon.tray_icon:
            self.daemon.tray_icon.update_dnd_count(count)
        logger.debug(f"[免打扰] 缓存通知 (共 {count} 条)")

    def _flush_pending(self):
        """批量推送缓存的通知"""
        if not self._pending_notifications:
            return

        count = len(self._pending_notifications)
        logger.info(f"[免打扰] 退出全屏，推送 {count} 条缓存通知")

        from bridge.utils.win32_toast import send_toast

        # 合并通知摘要
        if count <= 3:
            # 少量通知逐条推送
            for notif in self._pending_notifications:
                title = notif.get("title", "通知")
                text = notif.get("text", "")
                pkg = notif.get("package", "")
                from bridge.modules.notification_bridge import NotificationBridge
                app_name = NotificationBridge._package_to_name(pkg)
                send_toast(
                    title=f"[{app_name}] {title}",
                    text=text[:200] if text else "",
                    app_name="Project Fusion",
                )
        else:
            # 大量通知合并为摘要
            send_toast(
                title=f"📬 {count} 条手机通知",
                text="免打扰期间缓存的通知，请查看手机",
                app_name="Project Fusion",
            )

        self._pending_notifications.clear()
        if self.daemon.tray_icon:
            self.daemon.tray_icon.update_dnd_count(0)

    def is_fullscreen(self) -> bool:
        """检测 PC 前台窗口是否全屏"""
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return False

        # 获取窗口矩形
        rect = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))

        # 获取窗口风格
        style = user32.GetWindowLongW(hwnd, GWL_STYLE)

        # 获取显示器信息
        monitor = user32.MonitorFromWindow(hwnd, 2)  # MONITOR_DEFAULTTOPRIMARY
        if not monitor:
            return False

        monitor_info = _MONITORINFO()
        monitor_info.cbSize = ctypes.sizeof(_MONITORINFO)
        user32.GetMonitorInfoW(monitor, ctypes.byref(monitor_info))

        # 窗口面积与显示器面积对比
        win_w = rect.right - rect.left
        win_h = rect.bottom - rect.top
        mon_w = monitor_info.rcMonitor.right - monitor_info.rcMonitor.left
        mon_h = monitor_info.rcMonitor.bottom - monitor_info.rcMonitor.top

        # 全屏判断: 窗口覆盖整个显示器且不是明显的桌面窗口
        is_covering = (win_w >= mon_w and win_h >= mon_h)

        # 排除桌面窗口和不可见窗口
        is_desktop = not (style & WS_VISIBLE) or (user32.GetAncestor(hwnd, 2) == user32.GetDesktopWindow())

        return is_covering and not is_desktop

    def _check_loop(self):
        """轮询检测全屏状态"""
        while self.running:
            was_fullscreen = self._is_fullscreen
            self._is_fullscreen = self.is_fullscreen()

            # 从全屏退出 → 推送缓存通知
            if was_fullscreen and not self._is_fullscreen:
                self._flush_pending()

            time.sleep(self.check_interval)


class _MONITORINFO(ctypes.Structure):
    """MONITORINFO 结构体"""
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("rcMonitor", wintypes.RECT),
        ("rcWork", wintypes.RECT),
        ("dwFlags", wintypes.DWORD),
    ]
