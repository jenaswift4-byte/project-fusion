"""
电话桥接模块 (升级版)
- 拨打电话 / 挂断 / 接听
- 来电检测 + Windows 弹窗提醒
"""

import threading
import time
import logging

from bridge.utils.win32_toast import send_toast_with_action

logger = logging.getLogger(__name__)


class PhoneBridge:
    """电话桥接器"""

    def __init__(self, bridge):
        self.bridge = bridge
        self.config = bridge.config.get("telephony", {})
        self.running = False
        self.monitor_thread = None
        self.last_call_state = "idle"
        self.poll_interval = self.config.get("poll_interval_ms", 2000) / 1000.0

    def start(self):
        """启动电话监控"""
        self.running = True
        self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.monitor_thread.start()
        logger.info("通话控制已启动 - 来电将弹窗提醒")

    def stop(self):
        """停止电话监控"""
        self.running = False
        if self.monitor_thread:
            self.monitor_thread.join(timeout=2)

    def _monitor_loop(self):
        """监控通话状态"""
        while self.running:
            try:
                state = self.get_call_state()
                if state != self.last_call_state:
                    self._on_call_state_change(self.last_call_state, state)
                    self.last_call_state = state
            except Exception as e:
                logger.error(f"通话监控错误: {e}")
            time.sleep(self.poll_interval)

    def _on_call_state_change(self, old_state: str, new_state: str):
        """通话状态变化回调"""
        if new_state == "ringing":
            logger.info("[通话] 来电!")
            if self.config.get("show_call_popup", True):
                send_toast_with_action(
                    title="来电",
                    text="手机有来电，点击查看",
                    action_label="查看手机",
                    app_name="Project Fusion",
                )
            if self.config.get("auto_focus_scrcpy", True) and self.bridge.scrcpy_ctrl:
                self.bridge.scrcpy_ctrl.bring_to_front()

        elif new_state == "offhook":
            logger.info("[通话] 通话中")

        elif new_state == "idle" and old_state != "idle":
            logger.info("[通话] 通话结束")

    def call(self, phone_number: str) -> bool:
        """拨打电话"""
        if not phone_number:
            return False

        phone_number = "".join(c for c in phone_number if c.isdigit() or c in "+")
        if not phone_number:
            return False

        logger.info(f"正在拨打: {phone_number}")
        cmd = f"am start -a android.intent.action.DIAL -d tel:{phone_number}"
        _, err, code = self.bridge.adb_shell(cmd)

        if code == 0:
            if self.bridge.scrcpy_ctrl:
                self.bridge.scrcpy_ctrl.bring_to_front()
            return True
        else:
            logger.error(f"拨号失败: {err}")
            return False

    def hangup(self) -> bool:
        """挂断电话"""
        _, err, code = self.bridge.adb_shell("input keyevent 6")  # KEYCODE_ENDCALL
        if code == 0:
            logger.info("已挂断")
            return True
        else:
            logger.error(f"挂断失败: {err}")
            return False

    def answer(self) -> bool:
        """接听电话"""
        _, err, code = self.bridge.adb_shell("input keyevent 5")  # KEYCODE_CALL
        if code == 0:
            logger.info("已接听")
            return True
        else:
            logger.error(f"接听失败: {err}")
            return False

    def get_call_state(self) -> str:
        """获取通话状态: idle / ringing / offhook / unknown"""
        output, _, _ = self.bridge.adb_shell(
            "dumpsys telephony.registry 2>/dev/null | grep 'mCallState' | head -1"
        )
        if output:
            if "mCallState=0" in output:
                return "idle"
            elif "mCallState=1" in output:
                return "ringing"
            elif "mCallState=2" in output:
                return "offhook"
        return "unknown"

    def send_sms(self, phone_number: str, message: str) -> bool:
        """发送短信 (打开短信 App)"""
        if not phone_number or not message:
            return False

        phone_number = "".join(c for c in phone_number if c.isdigit() or c in "+")
        escaped = message.replace("'", "\\'")[:160]

        logger.info(f"发送短信到: {phone_number}")
        cmd = f"am start -a android.intent.action.SENDTO -d sms:{phone_number} --es sms_body '{escaped}'"
        _, err, code = self.bridge.adb_shell(cmd)

        if code == 0:
            if self.bridge.scrcpy_ctrl:
                self.bridge.scrcpy_ctrl.bring_to_front()
            return True
        else:
            logger.error(f"短信发送失败: {err}")
            return False
