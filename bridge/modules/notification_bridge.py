"""
通知桥接模块 (升级版)
- 手机通知推送到 Windows Toast
- 收到通知自动置顶 Scrcpy 窗口
- 来电弹窗提醒
- 通知去重
"""

import threading
import time
import re
import logging
from collections import OrderedDict

from bridge.utils.win32_toast import send_toast, send_toast_with_action

logger = logging.getLogger(__name__)


class NotificationBridge:
    """通知桥接器 - 增强版"""

    def __init__(self, bridge):
        self.bridge = bridge
        self.config = bridge.config.get("notification", {})
        self.running = False
        self.monitor_thread = None

        # 通知去重: {key: timestamp}
        self.seen_notifications = OrderedDict()
        self.max_seen = 50  # 最多保留 50 条去重记录
        self.dedup_window = 5  # 5 秒内相同通知不重复

        # 忽略的包名
        self.ignore_packages = set(self.config.get("ignore_packages", [
            "com.android.systemui",
            "com.android.launcher",
            "android",
            "com.android.packageinstaller",
        ]))

        # 通知轮询间隔
        self.poll_interval = self.config.get("poll_interval_ms", 2000) / 1000.0

    def start(self):
        """启动通知监控"""
        if self.running:
            return

        self.running = True
        self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.monitor_thread.start()
        logger.info("通知桥接已启动 - 手机通知将推送到 Windows")

    def stop(self):
        """停止通知监控"""
        self.running = False
        if self.monitor_thread:
            self.monitor_thread.join(timeout=2)

    def _monitor_loop(self):
        """监控循环"""
        while self.running:
            try:
                self._check_notifications()
            except Exception as e:
                logger.error(f"通知监控错误: {e}")
            time.sleep(self.poll_interval)

    def _check_notifications(self):
        """检查新通知"""
        output, _, code = self.bridge.adb_shell(
            "dumpsys notification --noredact 2>/dev/null | "
            "grep -E '(pkg=|Notification|title=|text=|key=)' | head -80"
        )

        if code != 0 or not output:
            return

        notifications = self._parse_notifications(output)

        for pkg, title, text, key in notifications:
            if not self._is_new_notification(key):
                continue

            if pkg in self.ignore_packages:
                continue

            if not title:
                continue

            # 发送到 Windows
            self._send_windows_notification(title, text, pkg)

            # 自动置顶 Scrcpy 窗口
            if self.config.get("auto_focus_scrcpy", True) and self.bridge.scrcpy_ctrl:
                self.bridge.scrcpy_ctrl.bring_to_front()

            logger.info(f"[通知] {title}: {(text or '')[:50]}")

    def _parse_notifications(self, raw_output: str) -> list:
        """解析 dumpsys notification 输出"""
        notifications = []
        current = {}
        current_key = None

        for line in raw_output.split("\n"):
            line = line.strip()

            # 识别通知块
            pkg_match = re.search(r'pkg=([\w.]+)', line)
            if pkg_match:
                if current and current_key:
                    notifications.append((
                        current.get("pkg", ""),
                        current.get("title", ""),
                        current.get("text", ""),
                        current_key,
                    ))
                current = {"pkg": pkg_match.group(1)}
                current_key = pkg_match.group(1)

            key_match = re.search(r'key=(\S+)', line)
            if key_match:
                current_key = key_match.group(1)

            title_match = re.search(r'title=(.*?)(?:,|$)', line)
            if title_match and "title" not in current:
                current["title"] = title_match.group(1).strip()

            text_match = re.search(r'text=(.*?)(?:,|$)', line)
            if text_match and "text" not in current:
                current["text"] = text_match.group(1).strip()

        # 最后一个
        if current and current_key:
            notifications.append((
                current.get("pkg", ""),
                current.get("title", ""),
                current.get("text", ""),
                current_key,
            ))

        return notifications

    def _is_new_notification(self, key: str) -> bool:
        """去重检查"""
        now = time.time()
        if key in self.seen_notifications:
            last_time = self.seen_notifications[key]
            if now - last_time < self.dedup_window:
                return False

        self.seen_notifications[key] = now

        # 清理过期记录
        while len(self.seen_notifications) > self.max_seen:
            self.seen_notifications.popitem(last=False)

        return True

    def _send_windows_notification(self, title: str, text: str, package: str):
        """发送 Windows Toast 通知"""
        # 根据包名映射友好名称
        app_name = self._package_to_name(package)

        if self.config.get("show_toast", True):
            send_toast(
                title=f"[{app_name}] {title}",
                text=text[:200] if text else "",
                app_name="Project Fusion",
            )

    @staticmethod
    def _package_to_name(pkg: str) -> str:
        """包名转友好名称"""
        name_map = {
            "com.tencent.mm": "微信",
            "com.tencent.mobileqq": "QQ",
            "com.eg.android.AlipayGphone": "支付宝",
            "com.taobao.taobao": "淘宝",
            "com.tencent.qqmusic": "QQ音乐",
            "com.netease.cloudmusic": "网易云音乐",
            "com.sina.weibo": "微博",
            "com.ss.android.ugc.aweme": "抖音",
            "com.zhihu.android": "知乎",
            "com.bilibili.app.in": "B站",
        }
        if pkg in name_map:
            return name_map[pkg]
        # 取最后一段
        parts = pkg.split(".")
        return parts[-1] if parts else pkg

    def send_test_notification(self):
        """发送测试通知"""
        send_toast("Project Fusion", "通知桥接测试成功!", "Project Fusion")
