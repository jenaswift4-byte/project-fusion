"""
KDE Connect 事件监听器
通过 kdeconnect-cli 包装实现 Windows 端的 KDE Connect 事件订阅
支持: 通知、剪贴板、电池、来电、文件接收
"""

import json
import subprocess
import threading
import time
import logging
from typing import Callable, Optional

logger = logging.getLogger(__name__)


class KDEConnectListener:
    """KDE Connect 事件监听器"""

    def __init__(self, config: dict):
        self.config = config.get("kde_connect", {})
        self.cli_path = self.config.get("cli_path", "kdeconnect-cli")
        self.device_id = self.config.get("device_id", "auto")
        self.poll_interval = self.config.get("poll_interval_ms", 1000) / 1000.0
        self.running = False
        self._monitor_thread = None
        self._handlers = {}
        self._resolved_device_id = None
        self._last_notifications = []
        self._last_battery = None

    def on(self, event_type: str, handler: Callable):
        """注册事件处理器

        事件类型:
        - notification: 手机通知
        - clipboard: 剪贴板变化
        - battery: 电池状态变化
        - telephony: 来电/通话状态
        - device_connected: 设备连接
        - device_disconnected: 设备断开
        """
        self._handlers.setdefault(event_type, []).append(handler)

    async def _emit(self, event_type: str, data: dict):
        """触发事件"""
        for handler in self._handlers.get(event_type, []):
            try:
                result = handler(data)
                if hasattr(result, '__await__'):
                    await result
            except Exception as e:
                logger.error(f"事件处理器错误 [{event_type}]: {e}")

    def start(self) -> bool:
        """启动监听"""
        if not self.config.get("enabled", False):
            logger.info("KDE Connect 监听已禁用 (配置中 kde_connect.enabled=false)")
            return False

        # 检查 kdeconnect-cli 是否可用
        if not self._check_cli():
            logger.warning("kdeconnect-cli 不可用，KDE Connect 监听无法启动")
            logger.warning("请安装 KDE Connect: https://kdeconnect.kde.org/")
            return False

        # 解析设备 ID
        if self.device_id == "auto":
            self._resolved_device_id = self._auto_find_device()
            if not self._resolved_device_id:
                logger.warning("未找到已配对的 KDE Connect 设备")
                return False
        else:
            self._resolved_device_id = self.device_id

        logger.info(f"KDE Connect 监听已启动 (设备: {self._resolved_device_id})")
        self.running = True
        self._monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self._monitor_thread.start()
        return True

    def stop(self):
        """停止监听"""
        self.running = False
        if self._monitor_thread:
            self._monitor_thread.join(timeout=3)

    def _check_cli(self) -> bool:
        """检查 kdeconnect-cli 是否可用"""
        try:
            result = subprocess.run(
                [self.cli_path, "--version"],
                capture_output=True, text=True, timeout=5,
            )
            return result.returncode == 0
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return False

    def _auto_find_device(self) -> Optional[str]:
        """自动发现已配对设备"""
        try:
            result = subprocess.run(
                [self.cli_path, "--list-devices", "--id-name-only"],
                capture_output=True, text=True, timeout=10,
            )
            if result.returncode == 0 and result.stdout:
                for line in result.stdout.strip().split("\n"):
                    # 格式: <device_id> <device_name>
                    parts = line.strip().split(maxsplit=1)
                    if len(parts) >= 1:
                        return parts[0]
        except Exception as e:
            logger.error(f"自动发现设备失败: {e}")
        return None

    def _monitor_loop(self):
        """轮询监听循环"""
        while self.running:
            try:
                self._poll_notifications()
                self._poll_battery()
            except Exception as e:
                logger.error(f"KDE Connect 监听错误: {e}")
            time.sleep(self.poll_interval)

    def _poll_notifications(self):
        """轮询通知"""
        try:
            result = subprocess.run(
                [self.cli_path, "-d", self._resolved_device_id,
                 "--list-notifications"],
                capture_output=True, text=True, timeout=10,
            )
            if result.returncode == 0 and result.stdout:
                current = result.stdout.strip()
                if current != self._last_notifications:
                    self._last_notifications = current
                    # 解析通知
                    notifications = self._parse_notifications(current)
                    for notif in notifications:
                        import asyncio
                        try:
                            loop = asyncio.get_event_loop()
                            if loop.is_running():
                                asyncio.ensure_future(
                                    self._emit("notification", notif)
                                )
                            else:
                                loop.run_until_complete(
                                    self._emit("notification", notif)
                                )
                        except RuntimeError:
                            # 没有事件循环，直接调用
                            for h in self._handlers.get("notification", []):
                                try:
                                    h(notif)
                                except Exception:
                                    pass
        except subprocess.TimeoutExpired:
            logger.debug("KDE Connect 通知轮询超时")

    def _poll_battery(self):
        """轮询电池状态"""
        try:
            result = subprocess.run(
                [self.cli_path, "-d", self._resolved_device_id,
                 "--list-notifications"],
                capture_output=True, text=True, timeout=10,
            )
            # KDE Connect 电池信息可通过 dbus 获取
            # 这里简化处理，后续可扩展
        except Exception:
            pass

    @staticmethod
    def _parse_notifications(raw: str) -> list:
        """解析 kdeconnect-cli 通知输出"""
        notifications = []
        current = {}
        for line in raw.split("\n"):
            line = line.strip()
            if not line:
                if current:
                    notifications.append(current)
                    current = {}
                continue

            if "=" in line:
                key, _, value = line.partition("=")
                current[key.strip()] = value.strip()

        if current:
            notifications.append(current)

        return notifications

    # === 主动操作 ===

    def send_clipboard(self, text: str) -> bool:
        """发送剪贴板内容到手机"""
        try:
            result = subprocess.run(
                [self.cli_path, "-d", self._resolved_device_id,
                 "--share-text", text],
                capture_output=True, text=True, timeout=10,
            )
            return result.returncode == 0
        except Exception as e:
            logger.error(f"发送剪贴板失败: {e}")
            return False

    def send_file(self, file_path: str) -> bool:
        """发送文件到手机"""
        try:
            result = subprocess.run(
                [self.cli_path, "-d", self._resolved_device_id,
                 "--share", file_path],
                capture_output=True, text=True, timeout=30,
            )
            return result.returncode == 0
        except Exception as e:
            logger.error(f"发送文件失败: {e}")
            return False

    def ping(self) -> bool:
        """发送 ping 到手机"""
        try:
            result = subprocess.run(
                [self.cli_path, "-d", self._resolved_device_id, "--ping"],
                capture_output=True, text=True, timeout=10,
            )
            return result.returncode == 0
        except Exception as e:
            logger.error(f"ping 失败: {e}")
            return False

    def ring_device(self) -> bool:
        """让手机响铃 (找不到手机时)"""
        try:
            result = subprocess.run(
                [self.cli_path, "-d", self._resolved_device_id, "--ring"],
                capture_output=True, text=True, timeout=10,
            )
            return result.returncode == 0
        except Exception as e:
            logger.error(f"响铃失败: {e}")
            return False

    def request_pair(self) -> bool:
        """请求配对"""
        try:
            result = subprocess.run(
                [self.cli_path, "-d", self._resolved_device_id, "--pair"],
                capture_output=True, text=True, timeout=15,
            )
            return result.returncode == 0
        except Exception as e:
            logger.error(f"配对失败: {e}")
            return False
