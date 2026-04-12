"""
短信收发模块
PC 端直接查看/发送手机短信

手机作为 PC 外设扩展板:
  - PC 端读取手机最新短信
  - PC 端发送短信 (通过 ADB am start intent)
  - 新短信实时推送 (WS 通道) + Toast 通知
  - 短信历史查询
"""

import json
import time
import threading
import logging

logger = logging.getLogger(__name__)


class SMSBridge:
    """短信收发桥接"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._thread = None
        self._last_sms_count = 0

        # 配置
        sms_cfg = daemon.config.get("sms", {})
        self.enabled = sms_cfg.get("enabled", True)
        self.poll_interval = sms_cfg.get("poll_interval_ms", 5000) / 1000.0
        self.max_display = sms_cfg.get("max_display", 20)

    def start(self):
        """启动短信桥接"""
        if not self.enabled:
            logger.info("短信桥接已禁用")
            return
        self.running = True
        # 如果没有 WS 通道，用 ADB 轮询
        if not self.daemon.use_companion:
            self._thread = threading.Thread(target=self._poll_loop, daemon=True)
            self._thread.start()
            logger.info(f"短信桥接已启动 (ADB 轮询, 间隔: {self.poll_interval}s)")
        else:
            logger.info("短信桥接已启动 (WebSocket 实时)")

    def stop(self):
        """停止短信桥接"""
        self.running = False

    def get_recent_sms(self, limit: int = 10) -> list:
        """获取最近的短信

        Args:
            limit: 最大返回条数

        Returns:
            短信列表 [{address, body, date, type, read}]
        """
        # 使用 content query 查询短信数据库
        # 注意: Android content query 不支持 --limit，用 head 截断
        output, _, rc = self.daemon.adb_shell(
            f'content query --uri content://sms --projection address:body:date:type:read '
            f'--sort "date DESC" | head -{limit}'
        )

        if rc != 0 or not output:
            # 备用方案: 用 SQLite 直接查询 (需要 root)
            return self._get_sms_sqlite(limit)

        return self._parse_content_query(output)

    def get_unread_sms(self) -> list:
        """获取未读短信"""
        output, _, rc = self.daemon.adb_shell(
            'content query --uri content://sms --projection address:body:date:type:read '
            '--where "read=0" --sort "date DESC" | head -20'
        )

        if rc != 0 or not output:
            return []

        return self._parse_content_query(output)

    def send_sms(self, phone_number: str, message: str) -> bool:
        """发送短信

        Args:
            phone_number: 目标号码
            message: 短信内容

        Returns:
            是否成功
        """
        # 方式1: 通过 ADB am start 调用系统短信应用发送
        # 需要 smsmanager 权限或使用 intent
        try:
            # 使用 am start 发送短信 intent
            # 注意: 这会打开短信应用，需要用户点击发送
            # 更好的方案是通过 WS 让伴侣 App 发送
            escaped_msg = message.replace("'", "\\'").replace('"', '\\"')
            escaped_num = phone_number.replace("'", "\\'").replace('"', '\\"')

            # 通过 ADB 直接调用 SmsManager (需要系统权限)
            output, err, rc = self.daemon.adb_shell(
                f"am broadcast -a android.intent.action.SENDTO "
                f"-d sms:{escaped_num} --es sms_body '{escaped_msg}'",
                capture=True,
            )

            if rc == 0:
                logger.info(f"[短信] 已发送到 {phone_number}: {message[:30]}")
                return True
            else:
                # 备用: 打开短信编辑界面
                self.daemon.adb_shell(
                    f"am start -a android.intent.action.SENDTO "
                    f"-d sms:{escaped_num} --es sms_body '{escaped_msg}'",
                    capture=False,
                )
                logger.info(f"[短信] 已打开短信编辑界面: {phone_number}")
                return True

        except Exception as e:
            logger.error(f"[短信] 发送失败: {e}")
            return False

    def send_sms_via_ws(self, phone_number: str, message: str) -> bool:
        """通过 WebSocket 伴侣 App 发送短信 (更可靠)"""
        if not self.daemon.use_companion or not self.daemon.ws_client:
            return self.send_sms(phone_number, message)

        self.daemon.ws_client.send_json({
            "type": "send_sms",
            "number": phone_number,
            "body": message,
        })
        logger.info(f"[短信] WS 发送到 {phone_number}: {message[:30]}")
        return True

    def _parse_content_query(self, output: str) -> list:
        """解析 content query 输出"""
        results = []
        for line in output.strip().split("\n"):
            if not line.strip() or line.startswith("Row:"):
                # ADB content query 格式: Row: 0 address=xxx, body=xxx, date=xxx, type=1, read=1
                line = line.replace("Row: ", "").strip()
                if not line:
                    continue

            entry = {}
            for part in line.split(", "):
                if "=" in part:
                    key, _, value = part.partition("=")
                    key = key.strip()
                    value = value.strip()
                    if key in ("address", "body", "date", "type", "read"):
                        entry[key] = value

            if entry.get("address") and entry.get("body"):
                # 转换 type
                sms_type = entry.get("type", "1")
                entry["type_name"] = {
                    "1": "收到",
                    "2": "发出",
                    "3": "草稿",
                    "4": "发件箱",
                    "5": "失败",
                    "6": "排队",
                }.get(sms_type, "未知")

                results.append(entry)

        return results

    def _get_sms_sqlite(self, limit: int = 10) -> list:
        """SQLite 方式读取短信 (需要 root)"""
        output, _, rc = self.daemon.adb_shell(
            f'sqlite3 /data/data/com.android.providers.telephony/databases/mmssms.db '
            f'"SELECT address, body, date, type FROM sms ORDER BY date DESC LIMIT {limit};"'
        )
        if rc != 0:
            return []

        results = []
        for line in output.strip().split("\n"):
            parts = line.split("|")
            if len(parts) >= 4:
                results.append({
                    "address": parts[0],
                    "body": parts[1],
                    "date": parts[2],
                    "type": parts[3],
                })
        return results

    def _poll_loop(self):
        """ADB 轮询检测新短信"""
        while self.running:
            try:
                unread = self.get_unread_sms()
                current_count = len(unread)

                if current_count > self._last_sms_count and self._last_sms_count >= 0:
                    # 有新短信
                    new_count = current_count - self._last_sms_count
                    for sms in unread[:new_count]:
                        from bridge.utils.win32_toast import send_toast
                        from bridge.modules.notification_bridge import NotificationBridge
                        # 尝试匹配联系人名
                        address = sms.get("address", "未知号码")
                        body = sms.get("body", "")
                        send_toast(
                            title=f"[短信] {address}",
                            text=body[:200] if body else "",
                            app_name="Project Fusion",
                        )
                        logger.info(f"[短信] 新短信: {address}: {body[:40]}")

                self._last_sms_count = current_count

            except Exception as e:
                logger.debug(f"[短信] 轮询错误: {e}")

            time.sleep(self.poll_interval)

    def on_ws_sms(self, data: dict):
        """WebSocket 通道: 收到新短信推送"""
        address = data.get("address", "未知号码")
        body = data.get("body", "")
        from bridge.utils.win32_toast import send_toast
        send_toast(
            title=f"[短信] {address}",
            text=body[:200] if body else "",
            app_name="Project Fusion",
        )
        logger.info(f"[短信] WS新短信: {address}: {body[:40]}")
