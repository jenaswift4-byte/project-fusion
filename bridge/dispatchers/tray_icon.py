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
        self._battery_text = ""
        self._dnd_count = 0
        self._proximity_text = ""

    def start(self):
        """启动托盘图标"""
        try:
            import pystray
            from PIL import Image, ImageDraw
        except ImportError:
            logger.warning("pystray 或 Pillow 未安装，托盘图标不可用")
            logger.warning("安装: pip install pystray Pillow")
            return

        # 创建图标 (PIL 不支持 SVG，直接绘制)
        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        # 手机 (绿色)
        draw.rounded_rectangle([4, 8, 28, 48], radius=3, fill="#4CAF50", outline="#333333", width=2)
        # 电脑 (蓝色)
        draw.rounded_rectangle([36, 2, 60, 54], radius=2, fill="#2196F3", outline="#333333", width=2)
        # 连接线 (橙色)
        draw.line([28, 28, 36, 28], fill="#FF9800", width=3)
        draw.line([28, 34, 36, 34], fill="#FF9800", width=3)

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
                title = f"Project Fusion - {status_text}"
                if self._battery_text:
                    title += f" | {self._battery_text}"
                self._icon.title = title
            except Exception:
                pass

    def update_battery(self, battery_text: str):
        """更新电池状态显示"""
        self._battery_text = battery_text
        # 刷新托盘提示
        if self._icon:
            try:
                current = self._icon.title or "Project Fusion"
                # 移除旧电池信息
                base = current.split(" | ")[0] if " | " in current else current
                self._icon.title = f"{base} | {battery_text}"
            except Exception:
                pass

    def update_dnd_count(self, count: int):
        """更新免打扰缓存通知数"""
        self._dnd_count = count

    def update_proximity(self, text: str):
        """更新近场检测状态"""
        self._proximity_text = text

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
            MenuItem("手机截图", self._action_screenshot),
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
            MenuItem(
                "免打扰",
                self._action_toggle_dnd,
                checked=lambda item: self.daemon.dnd_manager.running,
            ),
            Menu.SEPARATOR,
            MenuItem("推送链接到手机", self._action_handoff),
            MenuItem("查看短信", self._action_view_sms),
            MenuItem("截图文件夹", self._action_screenshot_folder),
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

    def _action_toggle_dnd(self, icon=None, item=None):
        """切换免打扰"""
        if self.daemon.dnd_manager.running:
            self.daemon.dnd_manager.stop()
            logger.info("智能免打扰已关闭")
        else:
            self.daemon.dnd_manager.start()
            logger.info("智能免打扰已开启")

    def _action_screenshot(self, icon=None, item=None):
        """手机截图"""
        if hasattr(self.daemon, 'screenshot_bridge') and self.daemon.screenshot_bridge:
            self.daemon.screenshot_bridge.take_screenshot_async()

    def _action_handoff(self, icon=None, item=None):
        """推送 PC 浏览器链接到手机"""
        if hasattr(self.daemon, 'handoff_bridge') and self.daemon.handoff_bridge:
            self.daemon.handoff_bridge.send_pc_url_to_phone()

    def _action_view_sms(self, icon=None, item=None):
        """查看短信 (Toast 显示最新)"""
        if hasattr(self.daemon, 'sms_bridge') and self.daemon.sms_bridge:
            sms_list = self.daemon.sms_bridge.get_recent_sms(3)
            if sms_list:
                from bridge.utils.win32_toast import send_toast
                for sms in sms_list:
                    send_toast(
                        title=f"[短信] {sms.get('address', '未知')}",
                        text=sms.get("body", "")[:200],
                        app_name="Project Fusion",
                    )
            else:
                from bridge.utils.win32_toast import send_toast
                send_toast(title="短信", text="暂无短信", app_name="Project Fusion")

    def _action_screenshot_folder(self, icon=None, item=None):
        """打开截图文件夹"""
        if hasattr(self.daemon, 'screenshot_bridge') and self.daemon.screenshot_bridge:
            self.daemon.screenshot_bridge.open_screenshot_folder()

    def _action_quit(self, icon=None, item=None):
        """退出"""
        self.daemon.stop()
        if self._icon:
            self._icon.stop()
