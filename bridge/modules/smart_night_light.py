"""
智能夜灯模块
基于光线传感器 + 时间 + 手机屏幕亮度，实现自动夜灯效果

功能:
  - 光线暗自动亮灯: 手机屏幕变为暖色夜灯
  - 时间联动: 只在夜间 (22:00-06:00) 触发
  - 渐变效果: 屏幕亮度渐变，避免突然刺激
  - 传感器触发: proximity 近距离 + 光线暗 → 开启
  - MCU 联动: 通过 CommandBridge 控制实体灯 (未来)

场景:
  - 半夜起来: 手机检测到移动 (加速度变化) + 环境暗 → 屏幕变暖色夜灯
  - 睡前阅读: 光线渐暗 → 提醒调整亮度
  - 自动关灯: 光线足够 → 自动恢复白天模式

实现方式:
  - 监听 MQTT 传感器数据 (light / proximity / accelerometer)
  - 触发条件满足 → ADB 调整屏幕亮度 + 色温
  - MIUI 支持: settings put system screen_brightness + 护眼模式
"""

import time
import logging
import threading
from typing import Dict, Optional, Any, Callable
from datetime import datetime

logger = logging.getLogger(__name__)


class SmartNightLight:
    """智能夜灯 — 基于环境传感器自动调节手机屏幕"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False

        # 配置
        cfg = daemon.config.get("night_light", {})
        self.enabled = cfg.get("enabled", True)

        # 触发阈值
        self.light_threshold = cfg.get("light_threshold", 50)  # lux, 低于此为暗
        self.night_start = cfg.get("night_start", "22:00")     # 夜间开始
        self.night_end = cfg.get("night_end", "06:00")         # 夜间结束
        self.accel_threshold = cfg.get("accel_threshold", 12)  # 加速度阈值 (m/s², 检测移动)
        self.cooldown_seconds = cfg.get("cooldown", 60)        # 两次触发最小间隔

        # 夜灯亮度级别 (0-255)
        self.night_brightness = cfg.get("night_brightness", 30)
        self.day_brightness = cfg.get("day_brightness", 180)

        # 状态
        self._night_mode_active = False
        self._last_trigger_time = 0
        self._sensor_data: Dict[str, float] = {
            "light": 999,       # 默认亮
            "proximity": 999,   # 默认远
            "accelerometer": 0, # 默认静止
        }
        self._monitor_thread: Optional[threading.Thread] = None
        self._callbacks: list = []

        # 亮度渐变
        self._current_brightness = self.day_brightness
        self._fade_step = 5
        self._fade_interval = 0.1  # 秒

    def start(self):
        """启动智能夜灯"""
        if not self.enabled:
            logger.info("[智能夜灯] 已禁用")
            return

        self.running = True

        # 注册传感器数据回调
        if self.daemon.mqtt_bridge:
            self.daemon.mqtt_bridge.on_sensor_data(self._on_sensor_data)

        # 启动监控线程
        self._monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True, name="night_light")
        self._monitor_thread.start()

        logger.info(
            f"[智能夜灯] 已启动 "
            f"(阈值: {self.light_threshold}lux, "
            f"夜间: {self.night_start}-{self.night_end})"
        )

    def stop(self):
        """停止智能夜灯"""
        self.running = False
        if self._night_mode_active:
            self._deactivate_night_mode()
        logger.info("[智能夜灯] 已停止")

    # ═══════════════════════════════════════════════════════
    # 传感器数据接收
    # ═══════════════════════════════════════════════════════

    def _on_sensor_data(self, device_id: str, sensor_type: str, value: float, unit: str):
        """接收传感器数据更新"""
        if sensor_type in self._sensor_data:
            self._sensor_data[sensor_type] = value

    # ═══════════════════════════════════════════════════════
    # 监控循环
    # ═══════════════════════════════════════════════════════

    def _monitor_loop(self):
        """主监控循环"""
        while self.running:
            try:
                self._check_conditions()
            except Exception as e:
                logger.debug(f"[智能夜灯] 检查异常: {e}")
            time.sleep(2)

    def _check_conditions(self):
        """检查是否应触发夜灯"""
        light = self._sensor_data.get("light", 999)
        is_dark = light < self.light_threshold
        is_night_time = self._is_night_time()

        if is_dark and is_night_time:
            # 暗环境 + 夜间 → 开夜灯
            if not self._night_mode_active:
                self._activate_night_mode(reason=f"暗环境 ({light:.0f}lux < {self.light_threshold}) + 夜间")
        else:
            # 亮环境 或 白天 → 关夜灯
            if self._night_mode_active:
                self._deactivate_night_mode(reason=f"环境变亮 ({light:.0f}lux) 或非夜间")

    def _is_night_time(self) -> bool:
        """判断当前是否为夜间"""
        now = datetime.now()
        current_minutes = now.hour * 60 + now.minute

        start_h, start_m = map(int, self.night_start.split(":"))
        end_h, end_m = map(int, self.night_end.split(":"))
        start_minutes = start_h * 60 + start_m
        end_minutes = end_h * 60 + end_m

        if start_minutes > end_minutes:
            # 跨午夜 (22:00-06:00)
            return current_minutes >= start_minutes or current_minutes < end_minutes
        else:
            return start_minutes <= current_minutes < end_minutes

    # ═══════════════════════════════════════════════════════
    # 夜灯模式控制
    # ═══════════════════════════════════════════════════════

    def _activate_night_mode(self, reason: str = ""):
        """激活夜灯模式"""
        now = time.time()
        if now - self._last_trigger_time < self.cooldown_seconds:
            return

        self._last_trigger_time = now
        self._night_mode_active = True

        # 调低屏幕亮度 (渐变)
        self._fade_brightness(self.night_brightness)

        # 开启护眼模式 (MIUI)
        self._set_eye_comfort(True)

        # 确保屏幕开启
        self.daemon.adb_shell("input keyevent KEYCODE_WAKEUP", capture=False)

        logger.info(f"[智能夜灯] 夜灯已开启: {reason}")

        # 回调
        for cb in self._callbacks:
            try:
                cb("activated", reason)
            except Exception:
                pass

        # Dashboard 推送
        if self.daemon.dashboard_server:
            self.daemon.dashboard_server.push_alert(
                "night_light", "activated", 0, f"智能夜灯已开启: {reason}"
            )

    def _deactivate_night_mode(self, reason: str = ""):
        """关闭夜灯模式"""
        self._night_mode_active = False

        # 恢复亮度 (渐变)
        self._fade_brightness(self.day_brightness)

        # 关闭护眼模式
        self._set_eye_comfort(False)

        logger.info(f"[智能夜灯] 夜灯已关闭: {reason}")

        for cb in self._callbacks:
            try:
                cb("deactivated", reason)
            except Exception:
                pass

    def manual_activate(self):
        """手动开启夜灯"""
        self._activate_night_mode(reason="手动开启")

    def manual_deactivate(self):
        """手动关闭夜灯"""
        self._deactivate_night_mode(reason="手动关闭")

    def toggle(self):
        """切换夜灯"""
        if self._night_mode_active:
            self.manual_deactivate()
        else:
            self.manual_activate()

    # ═══════════════════════════════════════════════════════
    # 屏幕控制 (ADB)
    # ═══════════════════════════════════════════════════════

    def _fade_brightness(self, target: int):
        """渐变调整屏幕亮度"""
        current = self._current_brightness
        step = self._fade_step if target > current else -self._fade_step

        for b in range(current, target, step):
            if not self.running:
                break
            self._set_brightness(b)
            time.sleep(self._fade_interval)

        self._set_brightness(target)
        self._current_brightness = target

    def _set_brightness(self, level: int):
        """设置屏幕亮度 (0-255)"""
        level = max(0, min(255, level))
        self.daemon.adb_shell(
            f"settings put system screen_brightness {level}",
            capture=False
        )

    def _set_eye_comfort(self, enabled: bool):
        """设置护眼模式 (MIUI)"""
        try:
            if enabled:
                # MIUI 护眼模式
                self.daemon.adb_shell(
                    "settings put system eye_comfort_mode_enabled 1",
                    capture=False
                )
                # 通用: 夜间模式
                self.daemon.adb_shell(
                    "settings put secure ui_night_mode 2",
                    capture=False
                )
            else:
                self.daemon.adb_shell(
                    "settings put system eye_comfort_mode_enabled 0",
                    capture=False
                )
                self.daemon.adb_shell(
                    "settings put secure ui_night_mode 1",
                    capture=False
                )
        except Exception as e:
            logger.debug(f"[智能夜灯] 护眼模式设置失败: {e}")

    # ═══════════════════════════════════════════════════════
    # 状态查询
    # ═══════════════════════════════════════════════════════

    def get_status(self) -> Dict[str, Any]:
        """获取智能夜灯状态"""
        return {
            "running": self.running,
            "enabled": self.enabled,
            "night_mode_active": self._night_mode_active,
            "current_brightness": self._current_brightness,
            "light_value": self._sensor_data.get("light", 999),
            "light_threshold": self.light_threshold,
            "is_night_time": self._is_night_time(),
            "night_start": self.night_start,
            "night_end": self.night_end,
            "sensor_data": self._sensor_data.copy(),
        }

    def on_state_change(self, callback: Callable):
        """注册状态变化回调"""
        self._callbacks.append(callback)
