"""
手机省电管理器
在远程串流模式下优化手机功耗，延长电池使用时间

核心功能：
  - 动态刷新率调整
  - 屏幕亮度智能管理
  - 传感器功耗控制
  - 电池监控与自动调整
  - 温度保护
"""

import time
import logging
import threading
from typing import Dict, Optional
from dataclasses import dataclass
from enum import Enum

logger = logging.getLogger(__name__)


class PowerMode(Enum):
    """电源模式"""
    PERFORMANCE = "performance"  # 高性能模式
    BALANCED = "balanced"        # 平衡模式
    POWER_SAVING = "power_saving"  # 省电模式


@dataclass
class BatteryStatus:
    """电池状态"""
    level: int
    is_charging: bool
    temperature: float
    health: str
    status_text: str


class PhonePowerManager:
    """手机省电管理器"""

    # 刷新率配置
    REFRESH_RATE_HIGH = 120
    REFRESH_RATE_MEDIUM = 60
    REFRESH_RATE_LOW = 30

    # 电池阈值
    BATTERY_THRESHOLD_LOW = 30
    BATTERY_THRESHOLD_CRITICAL = 15
    BATTERY_THRESHOLD_CHARGING = 80

    # 温度阈值
    TEMP_THRESHOLD_WARNING = 40.0
    TEMP_THRESHOLD_CRITICAL = 45.0

    def __init__(self, daemon):
        self.daemon = daemon
        self.power_saving_enabled = False
        self.current_mode = PowerMode.BALANCED

        # 电池状态
        self.battery = BatteryStatus(
            level=100,
            is_charging=False,
            temperature=25.0,
            health="good",
            status_text="未知"
        )

        # 配置
        self.config = {
            "auto_enabled": True,
            "battery_threshold": self.BATTERY_THRESHOLD_LOW,
            "reduce_fps_when_low": True,
            "reduce_bitrate_when_low": True,
            "auto_adjust_refresh_rate": True,
        }

        # 监控线程
        self.monitoring = False
        self.monitor_thread = None

        # 保存的原始设置
        self.original_settings = {}

    def start_monitoring(self):
        """启动电池监控"""
        if self.monitoring:
            return

        self.monitoring = True
        self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.monitor_thread.start()
        logger.info("手机省电监控已启动")

    def stop_monitoring(self):
        """停止监控"""
        self.monitoring = False
        if self.monitor_thread:
            self.monitor_thread.join(timeout=2)

    def _monitor_loop(self):
        """监控循环"""
        while self.monitoring:
            try:
                # 更新电池状态
                self._update_battery_status()

                # 根据电量自动调整
                if self.config["auto_enabled"]:
                    self._auto_adjust()

            except Exception as e:
                logger.debug(f"电池监控错误: {e}")

            time.sleep(30)  # 每30秒检查一次

    def _update_battery_status(self):
        """更新电池状态"""
        try:
            # 获取电池信息
            output, _, _ = self.daemon.adb_shell(
                "dumpsys battery | grep -E 'level|status|temperature|health'"
            )

            if not output:
                return

            # 解析电池数据
            import re

            level_match = re.search(r"level:\s*(\d+)", output)
            if level_match:
                self.battery.level = int(level_match.group(1))

            status_match = re.search(r"status:\s*(\d+)", output)
            if status_match:
                status_code = int(status_match.group(1))
                self.battery.is_charging = status_code == 2  # 2 = 充电中

                status_map = {
                    1: "unknown",
                    2: "充电中",
                    3: "放电中",
                    4: "未充电",
                    5: "电量已满"
                }
                self.battery.status_text = status_map.get(status_code, "未知")

            temp_match = re.search(r"temperature:\s*(\d+)", output)
            if temp_match:
                self.battery.temperature = int(temp_match.group(1)) / 10.0

            health_match = re.search(r"health:\s*(\d+)", output)
            if health_match:
                health_code = int(health_match.group(1))
                health_map = {
                    1: "unknown",
                    2: "良好",
                    3: "过温",
                    4: "损坏",
                    5: "电量过低",
                    6: "过压"
                }
                self.battery.health = health_map.get(health_code, "未知")

            logger.debug(
                f"电池: {self.battery.level}%, "
                f"状态: {self.battery.status_text}, "
                f"温度: {self.battery.temperature}°C"
            )

        except Exception as e:
            logger.debug(f"获取电池状态失败: {e}")

    def _auto_adjust(self):
        """根据电量自动调整"""
        # 充电时：可以使用高性能模式
        if self.battery.is_charging:
            if self.battery.level >= self.BATTERY_THRESHOLD_CHARGING:
                self._set_mode(PowerMode.PERFORMANCE)
            else:
                self._set_mode(PowerMode.BALANCED)
            return

        # 非充电时：根据电量调整
        if self.battery.level <= self.BATTERY_THRESHOLD_CRITICAL:
            # 严重低电量：最低功率
            self._set_mode(PowerMode.POWER_SAVING)
        elif self.battery.level <= self.battery.level:
            # 低电量：省电模式
            self._set_mode(PowerMode.POWER_SAVING)
        else:
            # 正常电量：平衡模式
            self._set_mode(PowerMode.BALANCED)

        # 温度保护
        if self.battery.temperature > self.TEMP_THRESHOLD_CRITICAL:
            logger.warning(f"手机温度过高: {self.battery.temperature}°C，启用温度保护")
            self._set_mode(PowerMode.POWER_SAVING)

    def _set_mode(self, mode: PowerMode):
        """设置电源模式"""
        if mode == self.current_mode:
            return

        old_mode = self.current_mode
        self.current_mode = mode

        logger.info(f"电源模式切换: {old_mode.value} -> {mode.value}")

        if mode == PowerMode.PERFORMANCE:
            self._apply_performance_mode()
        elif mode == PowerMode.BALANCED:
            self._apply_balanced_mode()
        elif mode == PowerMode.POWER_SAVING:
            self._apply_power_saving_mode()

    def _apply_performance_mode(self):
        """应用高性能模式设置"""
        # 高刷新率
        if self.config["auto_adjust_refresh_rate"]:
            self._set_refresh_rate(self.REFRESH_RATE_HIGH)

        # 亮度恢复到自动
        self._restore_brightness()

        # 启用所有传感器
        self._restore_sensors()

        logger.debug("高性能模式已应用")

    def _apply_balanced_mode(self):
        """应用平衡模式设置"""
        # 中等刷新率
        if self.config["auto_adjust_refresh_rate"]:
            self._set_refresh_rate(self.REFRESH_RATE_MEDIUM)

        # 亮度自动调整（基于环境光）
        # (需要伴侣App配合)

        logger.debug("平衡模式已应用")

    def _apply_power_saving_mode(self):
        """应用省电模式设置"""
        # 低刷新率
        if self.config["auto_adjust_refresh_rate"]:
            self._set_refresh_rate(self.REFRESH_RATE_LOW)

        # 降低屏幕亮度
        self._reduce_brightness()

        # 禁用不必要的传感器
        self._disable_sensors()

        logger.debug("省电模式已应用")

    def enable_streaming_mode(self):
        """启用串流省电模式"""
        if self.power_saving_enabled:
            return

        self.power_saving_enabled = True

        # 保存原始设置
        self._save_original_settings()

        # 应用省电设置
        self._apply_power_saving_mode()

        # 启用浅色模式减少 OLED 功耗
        self._set_light_mode()

        logger.info("串流省电模式已启用")

    def disable_streaming_mode(self):
        """禁用串流省电模式"""
        if not self.power_saving_enabled:
            return

        self.power_saving_enabled = False

        # 恢复原始设置
        self._restore_original_settings()

        logger.info("串流省电模式已禁用")

    def _save_original_settings(self):
        """保存原始设置"""
        try:
            # 获取当前刷新率
            output, _, _ = self.daemon.adb_shell(
                "settings get system peak_refresh_rate"
            )
            if output:
                self.original_settings["refresh_rate"] = int(output.strip())

            # 获取当前亮度
            output, _, _ = self.daemon.adb_shell(
                "settings get system screen_brightness"
            )
            if output:
                self.original_settings["brightness"] = int(output.strip())

            # 获取深色模式状态
            output, _, _ = self.daemon.adb_shell(
                "settings get system ui_night_mode"
            )
            if output:
                self.original_settings["night_mode"] = output.strip()

            logger.debug(f"已保存原始设置: {self.original_settings}")

        except Exception as e:
            logger.debug(f"保存原始设置失败: {e}")

    def _restore_original_settings(self):
        """恢复原始设置"""
        try:
            if "refresh_rate" in self.original_settings:
                self._set_refresh_rate(self.original_settings["refresh_rate"])

            if "brightness" in self.original_settings:
                self._set_brightness(self.original_settings["brightness"])

            if "night_mode" in self.original_settings:
                self._set_night_mode(self.original_settings["night_mode"])

            # 恢复传感器
            self._restore_sensors()

            logger.debug("已恢复原始设置")

        except Exception as e:
            logger.debug(f"恢复原始设置失败: {e}")

    def _set_refresh_rate(self, hz: int):
        """设置屏幕刷新率"""
        try:
            # Android 11+ 支持动态刷新率
            self.daemon.adb_shell(
                f"settings put system peak_refresh_rate {hz}",
                capture=False
            )
            self.daemon.adb_shell(
                f"settings put system user_refresh_rate {hz}",
                capture=False
            )
            logger.debug(f"屏幕刷新率已设置为 {hz}Hz")

        except Exception as e:
            logger.debug(f"设置刷新率失败: {e}")

    def _set_brightness(self, level: int):
        """设置屏幕亮度 (0-255)"""
        try:
            level = max(0, min(255, level))
            self.daemon.adb_shell(
                f"settings put system screen_brightness {level}",
                capture=False
            )
            logger.debug(f"屏幕亮度已设置为 {level}")

        except Exception as e:
            logger.debug(f"设置亮度失败: {e}")

    def _reduce_brightness(self):
        """降低屏幕亮度"""
        try:
            # 设置为 30% 亮度
            reduced_brightness = int(255 * 0.3)
            self._set_brightness(reduced_brightness)

        except Exception as e:
            logger.debug(f"降低亮度失败: {e}")

    def _restore_brightness(self):
        """恢复亮度到自动"""
        try:
            # 启用自动亮度
            self.daemon.adb_shell(
                "settings put system screen_brightness_mode 1",
                capture=False
            )

        except Exception as e:
            logger.debug(f"恢复亮度失败: {e}")

    def _set_light_mode(self):
        """设置浅色模式（减少OLED功耗）"""
        try:
            # 浅色模式: ui_night_mode = 1
            # 深色模式: ui_night_mode = 2
            # 自动: ui_night_mode = 0
            self.daemon.adb_shell(
                "settings put system ui_night_mode 1",
                capture=False
            )
            logger.debug("浅色模式已启用")

        except Exception as e:
            logger.debug(f"设置浅色模式失败: {e}")

    def _set_night_mode(self, value: str):
        """恢复夜间模式设置"""
        try:
            self.daemon.adb_shell(
                f"settings put system ui_night_mode {value}",
                capture=False
            )

        except Exception as e:
            logger.debug(f"恢复夜间模式失败: {e}")

    def _disable_sensors(self):
        """禁用不必要的传感器"""
        sensors_to_disable = [
            "accelerometer",
            "gyroscope",
            "proximity",
        ]

        for sensor in sensors_to_disable:
            try:
                self.daemon.adb_shell(
                    f"settings put sensor_force_sleep {sensor} 1",
                    capture=False
                )
            except:
                pass

        logger.debug("不必要传感器已禁用")

    def _restore_sensors(self):
        """恢复传感器"""
        try:
            self.daemon.adb_shell(
                "settings put sensor_force_sleep accelerometer 0",
                capture=False
            )
            self.daemon.adb_shell(
                "settings put sensor_force_sleep gyroscope 0",
                capture=False
            )
            logger.debug("传感器已恢复")

        except Exception as e:
            logger.debug(f"恢复传感器失败: {e}")

    def check_battery_and_adjust(self):
        """手动检查电池并调整（供外部调用）"""
        self._update_battery_status()
        if self.config["auto_enabled"]:
            self._auto_adjust()

    def get_battery_status(self) -> BatteryStatus:
        """获取电池状态"""
        return self.battery

    def get_status(self) -> Dict:
        """获取省电管理器状态"""
        return {
            "power_saving_enabled": self.power_saving_enabled,
            "current_mode": self.current_mode.value,
            "battery_level": self.battery.level,
            "is_charging": self.battery.is_charging,
            "temperature": self.battery.temperature,
            "health": self.battery.health,
            "monitoring": self.monitoring,
            "config": self.config,
        }

    def set_config(self, key: str, value):
        """设置配置项"""
        if key in self.config:
            self.config[key] = value
            logger.info(f"配置已更新: {key} = {value}")
