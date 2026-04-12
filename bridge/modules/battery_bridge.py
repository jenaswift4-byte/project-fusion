"""
电池状态桥接模块
通过 ADB dumpsys battery 定期获取手机电量，更新托盘图标 + 低电提醒

手机作为 PC 外设扩展板:
  - 托盘图标显示实时电量 (如 "🔋 85%")
  - 电量低于阈值弹 Toast 提醒
  - 充电状态变化通知
  - 通过 WS 通道也可获取 (如果伴侣 App 支持推送)
"""

import time
import threading
import logging

logger = logging.getLogger(__name__)


class BatteryBridge:
    """电池状态监控"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._thread = None

        # 上次状态 (用于检测变化)
        self._last_level = -1
        self._last_charging = False

        # 配置
        battery_cfg = daemon.config.get("battery", {})
        self.poll_interval = battery_cfg.get("poll_interval_ms", 30000) / 1000.0  # 默认30秒
        self.low_battery_threshold = battery_cfg.get("low_threshold", 20)
        self.critical_threshold = battery_cfg.get("critical_threshold", 10)

    def start(self):
        """启动电池监控"""
        if self.running:
            return
        self.running = True
        self._thread = threading.Thread(target=self._poll_loop, daemon=True)
        self._thread.start()
        logger.info(f"电池监控已启动 (间隔: {self.poll_interval}s)")

    def stop(self):
        """停止电池监控"""
        self.running = False

    def get_battery_info(self) -> dict:
        """获取电池信息 (ADB dumpsys battery)"""
        output, _, rc = self.daemon.adb_shell("dumpsys battery")
        if rc != 0 or not output:
            return {}

        info = {}
        for line in output.split("\n"):
            line = line.strip()
            if line.startswith("level:"):
                info["level"] = int(line.split(":")[1].strip())
            elif line.startswith("status:"):
                status_val = line.split(":")[1].strip()
                info["charging"] = status_val in ("2", "3", "4", "5")  # Charging/Discharging/Not charging/Full
                # 2=Charging, 3=Discharging, 4=Not charging, 5=Full
                info["charging"] = status_val == "2" or status_val == "5"
            elif line.startswith("temperature:"):
                temp_tenths = int(line.split(":")[1].strip())
                info["temperature"] = temp_tenths / 10.0  # 转为摄氏度
            elif line.startswith("voltage:"):
                info["voltage"] = int(line.split(":")[1].strip())

        return info

    def _poll_loop(self):
        """轮询电池状态"""
        # 首次立即获取
        self._check_battery()

        while self.running:
            time.sleep(self.poll_interval)
            if self.running:
                self._check_battery()

    def _check_battery(self):
        """检查电池状态并处理变化"""
        info = self.get_battery_info()
        if not info:
            return

        level = info.get("level", -1)
        charging = info.get("charging", False)
        temperature = info.get("temperature", 0)

        # 更新托盘状态
        charge_icon = "⚡" if charging else "🔋"
        status_text = f"{charge_icon} {level}%"
        if temperature > 0:
            status_text += f" ({temperature}°C)"

        # 通过 daemon 更新托盘提示
        if self.daemon.tray_icon:
            self.daemon.tray_icon.update_battery(status_text)

        # 检测变化 → 通知
        if self._last_level >= 0:
            # 低电量提醒 (仅首次触发或变得更低)
            if level <= self.critical_threshold and self._last_level > self.critical_threshold:
                from bridge.utils.win32_toast import send_toast
                send_toast(
                    title="⚠️ 手机电量严重不足",
                    text=f"电量仅剩 {level}%，请尽快充电！",
                    app_name="Project Fusion",
                )
                logger.warning(f"[电池] 严重不足: {level}%")
            elif level <= self.low_battery_threshold and self._last_level > self.low_battery_threshold:
                from bridge.utils.win32_toast import send_toast
                send_toast(
                    title="🔋 手机电量低",
                    text=f"电量 {level}%，建议充电",
                    app_name="Project Fusion",
                )
                logger.warning(f"[电池] 低电量: {level}%")

            # 充电状态变化
            if charging != self._last_charging:
                from bridge.utils.win32_toast import send_toast
                if charging:
                    send_toast(
                        title="⚡ 手机开始充电",
                        text=f"当前电量 {level}%",
                        app_name="Project Fusion",
                    )
                    logger.info(f"[电池] 开始充电: {level}%")
                else:
                    send_toast(
                        title="🔌 手机断开充电",
                        text=f"当前电量 {level}%",
                        app_name="Project Fusion",
                    )
                    logger.info(f"[电池] 断开充电: {level}%")

        self._last_level = level
        self._last_charging = charging
        logger.debug(f"[电池] {level}% {'充电中' if charging else '放电中'} {temperature}°C")
