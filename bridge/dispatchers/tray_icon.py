"""
系统托盘 UI
使用 pystray 在系统托盘显示 Project Fusion 图标
支持: 右键菜单、状态显示、模块开关
"""

import os
import threading
import logging
from pathlib import Path

logger = logging.getLogger(__name__)

# 托盘图标 (16x16 简易 SVG → PNG 在运行时生成)
ICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">
  <rect x="4" y="8" width="24" height="40" rx="3" fill="#4CAF50" stroke="#333" stroke-width="2"/>
  <rect x="36" y="2" width="24" height="52" rx="2" fill="#2196F3" stroke="#333" stroke-width="2"/>
  <path d="M28 28 L36 28" stroke="#FF9800" stroke-width="3" stroke-linecap="round"/>
  <path d="M28 34 L36 34" stroke="#FF9800" stroke-width="3" stroke-linecap="round"/>
</svg>"""


class TrayIcon:
    """系统托盘图标"""

    def __init__(self, daemon):
        self.daemon = daemon
        self._icon = None
        self._thread = None

    def start(self):
        """启动托盘图标"""
        try:
            import pystray
            from PIL import Image
            import io
        except ImportError:
            logger.warning("pystray 或 Pillow 未安装，托盘图标不可用")
            logger.warning("安装: pip install pystray Pillow")
            return

        try:
            # 生成图标
            img = Image.open(io.BytesIO(ICON_SVG.encode())).resize((64, 64))
        except Exception:
            # 备选: 创建简单图标
            img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
            from PIL import ImageDraw
            draw = ImageDraw.Draw(img)
            draw.rectangle([4, 8, 28, 48], fill="#4CAF50", outline="#333")
            draw.rectangle([36, 2, 60, 54], fill="#2196F3", outline="#333")

        # 创建托盘图标
        self._icon = pystray.Icon(
            name="Project Fusion",
            icon=img,
            title="Project Fusion - 跨设备无缝融合",
            menu=self._build_menu(),
        )

        # 在独立线程运行
        self._thread = threading.Thread(target=self._icon.run, daemon=True)
        self._thread.start()
        logger.info("系统托盘图标已启动")

    def stop(self):
        """停止托盘图标"""
        if self._icon:
            try:
                self._icon.stop()
            except Exception:
                pass

    def update_status(self, status_text: str):
        """更新托盘提示文字"""
        if self._icon:
            try:
                self._icon.title = f"Project Fusion - {status_text}"
            except Exception:
                pass

    def _build_menu(self):
        """构建右键菜单"""
        try:
            from pystray import MenuItem, Menu
        except ImportError:
            return None

        return Menu(
            MenuItem("Project Fusion", lambda: None, enabled=False),
            Menu.SEPARATOR,
            MenuItem(
                "状态: 运行中",
                lambda: None,
                enabled=False,
            ),
            Menu.SEPARATOR,
            MenuItem("打开 Scrcpy", self._action_focus_scrcpy),
            MenuItem("发送 Ping", self._action_ping),
            MenuItem("手机响铃", self._action_ring),
            Menu.SEPARATOR,
            MenuItem(
                "剪贴板同步",
                self._action_toggle_clipboard,
                checked=lambda item: self.daemon.clipboard_bridge.running,
            ),
            MenuItem(
                "通知桥接",
                self._action_toggle_notification,
                checked=lambda item: self.daemon.notification_bridge.running,
            ),
            MenuItem(
                "通话控制",
                self._action_toggle_phone,
                checked=lambda item: self.daemon.phone_bridge.running,
            ),
            Menu.SEPARATOR,
            MenuItem("退出", self._action_quit),
        )

    def _action_focus_scrcpy(self, icon=None, item=None):
        """聚焦 Scrcpy 窗口"""
        if self.daemon.scrcpy_ctrl:
            self.daemon.scrcpy_ctrl.bring_to_front()

    def _action_ping(self, icon=None, item=None):
        """发送 ping"""
        if self.daemon.kde_listener and self.daemon.kde_listener.running:
            self.daemon.kde_listener.ping()
        else:
            self.daemon.adb_shell("input keyevent 3", capture=False)  # HOME

    def _action_ring(self, icon=None, item=None):
        """手机响铃"""
        if self.daemon.kde_listener and self.daemon.kde_listener.running:
            self.daemon.kde_listener.ring_device()

    def _action_toggle_clipboard(self, icon=None, item=None):
        """切换剪贴板同步"""
        if self.daemon.clipboard_bridge.running:
            self.daemon.clipboard_bridge.stop()
            logger.info("剪贴板同步已暂停")
        else:
            self.daemon.clipboard_bridge.start()
            logger.info("剪贴板同步已恢复")

    def _action_toggle_notification(self, icon=None, item=None):
        """切换通知桥接"""
        if self.daemon.notification_bridge.running:
            self.daemon.notification_bridge.stop()
            logger.info("通知桥接已暂停")
        else:
            self.daemon.notification_bridge.start()
            logger.info("通知桥接已恢复")

    def _action_toggle_phone(self, icon=None, item=None):
        """切换通话控制"""
        if self.daemon.phone_bridge.running:
            self.daemon.phone_bridge.stop()
            logger.info("通话控制已暂停")
        else:
            self.daemon.phone_bridge.start()
            logger.info("通话控制已恢复")

    def _action_quit(self, icon=None, item=None):
        """退出"""
        self.daemon.stop()
        if self._icon:
            self._icon.stop()
