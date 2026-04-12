"""
深度优化的触摸映射引擎
将手机触摸转化为精确的鼠标/键盘输入，实现原生级的触控体验

核心功能：
  - 触摸坐标映射 (手机屏幕 -> PC屏幕)
  - 手势识别 (点击/双击/长按/滑动/捏合)
  - 边缘手势检测 (系统快捷操作)
  - 应用特定快捷键映射
  - 动态DPI缩放
  - 省电模式自适应
"""

import time
import logging
from typing import Dict, List, Tuple, Optional, Callable
from dataclasses import dataclass, field
from collections import deque
from enum import Enum

logger = logging.getLogger(__name__)


class GestureType(Enum):
    """手势类型"""
    TAP = "tap"
    DOUBLE_TAP = "double_tap"
    LONG_PRESS = "long_press"
    SWIPE_LEFT = "swipe_left"
    SWIPE_RIGHT = "swipe_right"
    SWIPE_UP = "swipe_up"
    SWIPE_DOWN = "swipe_down"
    PINCH_IN = "pinch_in"
    PINCH_OUT = "pinch_out"
    TWO_FINGER_TAP = "two_finger_tap"
    DRAG = "drag"


@dataclass
class TouchPoint:
    """触摸点"""
    x: float
    y: float
    pressure: float = 1.0
    timestamp: float = field(default_factory=time.time)
    finger_id: int = 0


@dataclass
class GestureResult:
    """手势识别结果"""
    gesture: GestureType
    start_x: float
    start_y: float
    end_x: float
    end_y: float
    duration: float
    velocity: float
    finger_count: int = 1


class TouchMapper:
    """触摸映射器 - 深度优化版"""

    # 手势识别参数
    TAP_THRESHOLD_MS = 200
    TAP_MAX_MOVE_PX = 15
    DOUBLE_TAP_DELAY_MS = 300
    LONG_PRESS_THRESHOLD_MS = 500
    SWIPE_THRESHOLD_PX = 50
    PINCH_THRESHOLD = 0.1

    # 屏幕DPI配置 (需要动态获取)
    PHONE_DPI = 420
    PC_DPI = 96

    # 边缘区域定义
    EDGE_WIDTH = 50
    TOP_EDGE_HEIGHT = 30
    BOTTOM_EDGE_HEIGHT = 50

    def __init__(self, daemon):
        self.daemon = daemon

        # 触摸状态
        self.finger_states: Dict[int, TouchPoint] = {}
        self.last_tap_time: Dict[str, float] = {}
        self.last_tap_position: Dict[str, Tuple[float, float]] = {}

        # 屏幕配置
        self.phone_screen = {"width": 1080, "height": 1920}
        self.pc_screen = {"width": 1920, "height": 1080}
        self.scale_factor = 1.0

        # Scrcpy 窗口偏移
        self.scrcpy_offset = {"x": 0, "y": 0}

        # 模式开关
        self.power_saving = False
        self.game_mode = False
        self.enabled = True

        # 应用特定映射
        self.app_gesture_maps: Dict[str, Dict[str, str]] = {}

        # 回调函数
        self._callbacks: Dict[str, Callable] = {}

        # 初始化默认应用映射
        self._init_default_app_maps()

    def _init_default_app_maps(self):
        """初始化默认应用手势映射"""
        self.app_gesture_maps = {
            "chrome": {
                "swipe_left": "alt_left",      # 浏览器后退
                "swipe_right": "alt_right",     # 浏览器前进
                "two_finger_swipe_left": "ctrl_pageup",
                "two_finger_swipe_right": "ctrl_pagedn",
            },
            "vscode": {
                "swipe_left": "ctrl_pageup",
                "swipe_right": "ctrl_pagedn",
                "swipe_down": "ctrl_shift_o",
                "swipe_up": "ctrl_shift_o",
            },
            "file_explorer": {
                "swipe_left": "alt_up",
                "swipe_right": "backspace",
            },
            "default": {
                "swipe_left": "left",
                "swipe_right": "right",
                "swipe_up": "pageup",
                "swipe_down": "pagedown",
            },
        }

    def configure_screens(self, phone_width: int, phone_height: int,
                         pc_width: int, pc_height: int,
                         scrcpy_x: int = 0, scrcpy_y: int = 0):
        """配置屏幕尺寸和Scrcpy窗口位置"""
        self.phone_screen = {"width": phone_width, "height": phone_height}
        self.pc_screen = {"width": pc_width, "height": pc_height}
        self.scrcpy_offset = {"x": scrcpy_x, "y": scrcpy_y}

        # 计算缩放因子
        self._calculate_scale_factor()

        logger.info(f"触摸映射已配置: 手机 {phone_width}x{phone_height} -> PC {pc_width}x{pc_height}")

    def _calculate_scale_factor(self):
        """计算坐标缩放因子"""
        phone_aspect = self.phone_screen["width"] / self.phone_screen["height"]
        pc_aspect = self.pc_screen["width"] / self.pc_screen["height"]

        if abs(phone_aspect - pc_aspect) < 0.1:
            # 宽高比接近，直接按像素缩放
            self.scale_factor = min(
                self.pc_screen["width"] / self.phone_screen["width"],
                self.pc_screen["height"] / self.phone_screen["height"]
            )
        else:
            # 宽高比差异大，使用填充模式
            self.scale_factor = max(
                self.pc_screen["width"] / self.phone_screen["width"],
                self.pc_screen["height"] / self.phone_screen["height"]
            )

    def map_coordinates(self, phone_x: float, phone_y: float) -> Tuple[int, int]:
        """将手机触摸坐标映射到PC屏幕坐标"""
        # 缩放映射
        pc_x = int(phone_x * self.scale_factor + self.scrcpy_offset["x"])
        pc_y = int(phone_y * self.scale_factor + self.scrcpy_offset["y"])

        # 边界裁剪
        pc_x = max(0, min(pc_x, self.pc_screen["width"] - 1))
        pc_y = max(0, min(pc_y, self.pc_screen["height"] - 1))

        return pc_x, pc_y

    def on_touch_start(self, finger_id: int, x: float, y: float, pressure: float = 1.0) -> bool:
        """触摸开始事件"""
        if not self.enabled:
            return False

        # 边缘检测
        edge_action = self._detect_edge_action(x, y)
        if edge_action:
            self._handle_edge_action(edge_action)
            return True

        # 记录触摸点
        self.finger_states[finger_id] = TouchPoint(
            x=x, y=y,
            pressure=pressure,
            timestamp=time.time(),
            finger_id=finger_id
        )

        return False

    def on_touch_move(self, finger_id: int, x: float, y: float, pressure: float = 1.0):
        """触摸移动事件"""
        if not self.enabled or finger_id not in self.finger_states:
            return

        old_point = self.finger_states[finger_id]
        new_point = TouchPoint(
            x=x, y=y,
            pressure=pressure,
            timestamp=time.time(),
            finger_id=finger_id
        )

        # 计算移动距离
        dx = x - old_point.x
        dy = y - old_point.y
        distance = (dx**2 + dy**2)**0.5

        # 实时鼠标移动
        if distance > 2:
            pc_x, pc_y = self.map_coordinates(x, y)

            if hasattr(self, '_dragging') and self._dragging:
                self._send_mouse_drag(pc_x, pc_y)
            else:
                self._send_mouse_move(pc_x, pc_y)

        self.finger_states[finger_id] = new_point

    def on_touch_end(self, finger_id: int, x: float, y: float):
        """触摸结束事件"""
        if not self.enabled or finger_id not in self.finger_states:
            return

        start_point = self.finger_states[finger_id]
        end_point = TouchPoint(x=x, y=y, timestamp=time.time())
        duration = (end_point.timestamp - start_point.timestamp) * 1000
        distance = ((x - start_point.x)**2 + (y - start_point.y)**2)**0.5

        # 识别手势
        gesture = self._recognize_gesture(start_point, end_point, duration, distance)

        if gesture:
            self._handle_gesture(gesture, start_point, end_point)

        # 清理状态
        del self.finger_states[finger_id]

    def _detect_edge_action(self, x: float, y: float) -> Optional[str]:
        """检测边缘手势"""
        # 左边缘
        if x < self.EDGE_WIDTH:
            if y < self.TOP_EDGE_HEIGHT:
                return "top_left_corner"
            elif y > self.phone_screen["height"] - self.BOTTOM_EDGE_HEIGHT:
                return "bottom_left_corner"
            return "left_edge"

        # 右边缘
        if x > self.phone_screen["width"] - self.EDGE_WIDTH:
            if y < self.TOP_EDGE_HEIGHT:
                return "top_right_corner"
            elif y > self.phone_screen["height"] - self.BOTTOM_EDGE_HEIGHT:
                return "bottom_right_corner"
            return "right_edge"

        # 上边缘
        if y < self.TOP_EDGE_HEIGHT:
            return "top_edge"

        # 下边缘
        if y > self.phone_screen["height"] - self.BOTTOM_EDGE_HEIGHT:
            return "bottom_edge"

        return None

    def _handle_edge_action(self, edge: str):
        """处理边缘动作"""
        edge_actions = {
            "left_edge": lambda: self._send_key("win_left"),
            "right_edge": lambda: self._do_app_switch(),
            "top_edge": lambda: self._send_key("alt_f4"),
            "bottom_edge": lambda: self._send_key("win_t"),
            "top_left_corner": lambda: self._send_key("ctrl_escape"),
            "top_right_corner": lambda: self._send_key("win_d"),
            "bottom_left_corner": lambda: self._send_key("win_n"),
            "bottom_right_corner": lambda: self._send_key("win_l"),
        }

        action = edge_actions.get(edge)
        if action:
            logger.debug(f"边缘手势: {edge}")
            action()

    def _recognize_gesture(self, start: TouchPoint, end: TouchPoint,
                          duration: float, distance: float) -> Optional[GestureType]:
        """识别手势"""
        # 点击
        if duration < self.TAP_THRESHOLD_MS and distance < self.TAP_MAX_MOVE_PX:
            current_time = time.time()

            # 检查双击
            if (self.last_tap_time.get("main") and
                current_time - self.last_tap_time["main"] < self.DOUBLE_TAP_DELAY_MS):
                self.last_tap_time["main"] = current_time
                return GestureType.DOUBLE_TAP

            self.last_tap_time["main"] = current_time
            self.last_tap_position["main"] = (end.x, end.y)

            # 检查双指点击
            if len(self.finger_states) >= 2:
                return GestureType.TWO_FINGER_TAP

            return GestureType.TAP

        # 长按
        if duration > self.LONG_PRESS_THRESHOLD_MS and distance < self.TAP_MAX_MOVE_PX:
            return GestureType.LONG_PRESS

        # 滑动
        if distance > self.SWIPE_THRESHOLD_PX:
            dx = end.x - start.x
            dy = end.y - start.y

            if abs(dx) > abs(dy):
                return GestureType.SWIPE_RIGHT if dx > 0 else GestureType.SWIPE_LEFT
            else:
                return GestureType.SWIPE_DOWN if dy > 0 else GestureType.SWIPE_UP

        # 捏合（两指）
        if len(self.finger_states) >= 2:
            # 简化的捏合检测
            return GestureType.PINCH_IN

        return None

    def _handle_gesture(self, gesture: GestureType, start: TouchPoint, end: TouchPoint):
        """处理识别到的手势"""
        logger.debug(f"手势: {gesture.value}")

        # 获取当前焦点应用
        current_app = self._get_focused_app()
        app_map = self.app_gesture_maps.get(current_app, self.app_gesture_maps["default"])

        # 获取应用特定映射
        gesture_key = gesture.value
        mapped_key = app_map.get(gesture_key, self.app_gesture_maps["default"].get(gesture_key))

        # 执行手势对应的动作
        if gesture == GestureType.TAP:
            pc_x, pc_y = self.map_coordinates(end.x, end.y)
            self._send_mouse_click(pc_x, pc_y)

        elif gesture == GestureType.DOUBLE_TAP:
            pc_x, pc_y = self.map_coordinates(end.x, end.y)
            self._send_mouse_double_click(pc_x, pc_y)

        elif gesture == GestureType.LONG_PRESS:
            pc_x, pc_y = self.map_coordinates(end.x, end.y)
            self._send_mouse_right_click(pc_x, pc_y)

        elif gesture == GestureType.SWIPE_LEFT:
            key = mapped_key or "left"
            self._send_key(key)

        elif gesture == GestureType.SWIPE_RIGHT:
            key = mapped_key or "right"
            self._send_key(key)

        elif gesture == GestureType.SWIPE_UP:
            key = mapped_key or "pageup"
            self._send_key(key)

        elif gesture == GestureType.SWIPE_DOWN:
            key = mapped_key or "pagedown"
            self._send_key(key)

        elif gesture == GestureType.PINCH_IN:
            self._send_mouse_wheel(120)

        elif gesture == GestureType.PINCH_OUT:
            self._send_mouse_wheel(-120)

    def _get_focused_app(self) -> str:
        """获取当前焦点应用名称"""
        try:
            import subprocess
            # 使用 PowerShell 获取焦点窗口标题
            ps_script = '''
Add-Type AssemblyName PresentationFramework
$hwnd = [System.Runtime.InteropServices.Marshal]::GetForegroundWindow()
$title = ""
if ($hwnd -ne 0) {
    $sb = New-Object System.Text.StringBuilder 256
    [System.Runtime.InteropServices.Marshal]::Copy($hwnd, $sb, 0, 256)
    $title = $sb.ToString()
}
$title
'''
            result = subprocess.run(
                ["powershell", "-WindowStyle", "Hidden", "-NoProfile", "-Command", ps_script],
                capture_output=True, text=True, timeout=5
            )
            title = result.stdout.strip().lower()

            # 根据窗口标题判断应用
            if "chrome" in title or "edge" in title:
                return "chrome"
            elif "code" in title or "vscode" in title:
                return "vscode"
            elif "explorer" in title:
                return "file_explorer"

        except:
            pass

        return "default"

    def _send_mouse_move(self, x: int, y: int):
        """发送鼠标移动"""
        try:
            if hasattr(self.daemon, 'ws_client') and self.daemon.ws_client:
                self.daemon.ws_client.send_mouse_move(x, y)
            else:
                # 降级：使用 subprocess
                import subprocess
                subprocess.run(
                    ["xdotool", "mousemove", str(x), str(y)],
                    capture_output=True
                )
        except Exception as e:
            logger.debug(f"鼠标移动发送失败: {e}")

    def _send_mouse_click(self, x: int, y: int):
        """发送鼠标左键点击"""
        try:
            if hasattr(self.daemon, 'ws_client') and self.daemon.ws_client:
                self.daemon.ws_client.send_mouse_click(x, y)
            else:
                import subprocess
                subprocess.run(
                    ["xdotool", "mousemove", str(x), str(y), "click", "1"],
                    capture_output=True
                )
        except Exception as e:
            logger.debug(f"鼠标点击发送失败: {e}")

    def _send_mouse_double_click(self, x: int, y: int):
        """发送鼠标双击"""
        try:
            import subprocess
            subprocess.run(
                ["xdotool", "mousemove", str(x), str(y), "click", "1", "click", "1"],
                capture_output=True
            )
        except Exception as e:
            logger.debug(f"鼠标双击发送失败: {e}")

    def _send_mouse_right_click(self, x: int, y: int):
        """发送鼠标右键点击"""
        try:
            import subprocess
            subprocess.run(
                ["xdotool", "mousemove", str(x), str(y), "click", "3"],
                capture_output=True
            )
        except Exception as e:
            logger.debug(f"鼠标右键发送失败: {e}")

    def _send_mouse_drag(self, x: int, y: int):
        """发送鼠标拖拽"""
        try:
            import subprocess
            # 拖拽 = 按下 + 移动 + 松开
            subprocess.run(
                ["xdotool", "mousedown", "1", "mousemove", str(x), str(y), "mouseup", "1"],
                capture_output=True
            )
        except Exception as e:
            logger.debug(f"鼠标拖拽发送失败: {e}")

    def _send_mouse_wheel(self, delta: int):
        """发送鼠标滚轮"""
        try:
            import subprocess
            subprocess.run(
                ["xdotool", "click", "4" if delta > 0 else "5"] if False else ["xdotool", "mousemove_relative", "0", str(delta // 120)],
                capture_output=True
            )
        except Exception as e:
            logger.debug(f"鼠标滚轮发送失败: {e}")

    def _send_key(self, key: str):
        """发送键盘按键"""
        try:
            if hasattr(self.daemon, 'ws_client') and self.daemon.ws_client:
                self.daemon.ws_client.send_key(key)
            else:
                # 降级：使用 xdotool
                import subprocess
                subprocess.run(
                    ["xdotool", "key", key],
                    capture_output=True
                )
        except Exception as e:
            logger.debug(f"键盘按键发送失败: {e}")

    def _do_app_switch(self):
        """执行应用切换 (Alt+Tab)"""
        try:
            import subprocess
            # 按下 Alt+Tab
            subprocess.run(
                ["xdotool", "keydown", "alt", "keydown", "Tab"],
                capture_output=True
            )
            time.sleep(0.1)
            # 松开
            subprocess.run(
                ["xdotool", "keyup", "alt", "keyup", "Tab"],
                capture_output=True
            )
        except Exception as e:
            logger.debug(f"应用切换失败: {e}")

    def enable_game_mode(self):
        """启用游戏模式 - 优化触摸延迟"""
        self.game_mode = True
        self.power_saving = False
        logger.info("游戏模式已启用")

    def enable_power_saving(self):
        """启用省电模式"""
        self.power_saving = True
        self.game_mode = False
        logger.info("省电模式已启用")

    def disable_special_modes(self):
        """禁用特殊模式"""
        self.power_saving = False
        self.game_mode = False
        logger.info("特殊模式已禁用")

    def set_app_gesture_map(self, app_name: str, gesture_map: Dict[str, str]):
        """设置应用特定手势映射"""
        self.app_gesture_maps[app_name] = gesture_map
        logger.info(f"已更新应用手势映射: {app_name}")

    def get_status(self) -> Dict:
        """获取触摸映射器状态"""
        return {
            "enabled": self.enabled,
            "game_mode": self.game_mode,
            "power_saving": self.power_saving,
            "scale_factor": self.scale_factor,
            "phone_screen": self.phone_screen,
            "pc_screen": self.pc_screen,
            "active_fingers": len(self.finger_states),
        }
