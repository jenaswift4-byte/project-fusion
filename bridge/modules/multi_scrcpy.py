"""
多设备 Scrcpy 窗口管理器
管理多个废旧手机的 Scrcpy 投屏窗口，实现分布式摄像头阵列

功能:
  - 多设备 Scrcpy 窗口自动布局 (网格排列)
  - 单设备窗口控制 (置顶/最小化/聚焦/关闭)
  - 摄像头开关 (通过 ADB 控制手机摄像头)
  - 窗口排列方案: 网格/堆叠/水平/垂直
  - MQTT 命令接收 (fusion/camera/{deviceId}/command)
  - 设备自动发现 (通过 ADB devices)

使用场景:
  - 3 台废旧手机作为安防监控摄像头
  - Scrcpy 窗口阵列实时查看
  - 点击窗口切换主摄像头
"""

import subprocess
import threading
import logging
import time
import os
from typing import Dict, List, Optional, Tuple, Any
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class ScrcpyInstance:
    """单个 Scrcpy 实例"""
    device_serial: str
    device_name: str
    process: Optional[subprocess.Popen] = None
    window_title: str = ""
    window_hwnd: int = 0
    x: int = 0
    y: int = 0
    width: int = 480
    height: int = 854
    running: bool = False
    camera_enabled: bool = True

    # 摄像头相关
    camera_facing: str = "back"  # front/back
    recording: bool = False


@dataclass
class LayoutConfig:
    """窗口布局配置"""
    strategy: str = "grid"       # grid/stack/horizontal/vertical
    margin: int = 10             # 窗口间距
    monitor_index: int = 0       # 显示器索引
    base_width: int = 480        # 单窗口基础宽度
    base_height: int = 854       # 单窗口基础高度
    max_columns: int = 3          # 网格最大列数


class MultiScrcpyManager:
    """
    多设备 Scrcpy 管理器

    典型用法:
        manager = MultiScrcpyManager(scrcpy_dir, adb_path)
        manager.start_all(['serial1', 'serial2', 'serial3'])
        manager.arrange_windows()
    """

    def __init__(self, scrcpy_dir: str, adb_path: str = "adb", config: dict = None):
        self.scrcpy_dir = scrcpy_dir
        self.scrcpy_exe = os.path.join(scrcpy_dir, "scrcpy.exe")
        self.adb_path = adb_path
        self.config = config or {}

        self.instances: Dict[str, ScrcpyInstance] = {}
        self.layout = LayoutConfig(
            strategy=self.config.get("layout_strategy", "grid"),
            margin=self.config.get("layout_margin", 10),
            base_width=self.config.get("window_width", 480),
            base_height=self.config.get("window_height", 854),
            max_columns=self.config.get("max_columns", 3),
        )

        self._lock = threading.Lock()
        self._callbacks: Dict[str, List] = {
            "device_added": [],
            "device_removed": [],
            "camera_status": [],
            "layout_changed": [],
        }

    # ═══════════════════════════════════════════════════════
    # 设备管理
    # ═══════════════════════════════════════════════════════

    def discover_devices(self) -> List[str]:
        """通过 ADB 发现已连接设备"""
        try:
            result = subprocess.run(
                [self.adb_path, "devices", "-l"],
                capture_output=True, text=True, timeout=10,
            )
            devices = []
            for line in result.stdout.strip().split("\n")[1:]:
                if "device" in line and not line.startswith("List"):
                    serial = line.split()[0]
                    devices.append(serial)
                    logger.info(f"[ScrcpyMgr] 发现设备: {serial}")
            return devices
        except Exception as e:
            logger.error(f"[ScrcpyMgr] 发现设备失败: {e}")
            return []

    def get_device_name(self, serial: str) -> str:
        """获取设备型号名称"""
        try:
            result = subprocess.run(
                [self.adb_path, "-s", serial, "shell", "getprop ro.product.model"],
                capture_output=True, text=True, timeout=5,
            )
            return result.stdout.strip() or serial
        except Exception:
            return serial

    def add_device(self, serial: str, name: str = None) -> bool:
        """添加设备到管理列表"""
        with self._lock:
            if serial in self.instances:
                logger.warning(f"[ScrcpyMgr] 设备已存在: {serial}")
                return False

            device_name = name or self.get_device_name(serial)
            instance = ScrcpyInstance(
                device_serial=serial,
                device_name=device_name,
                window_title=f"Camera - {device_name}",
                width=self.layout.base_width,
                height=self.layout.base_height,
            )
            self.instances[serial] = instance
            logger.info(f"[ScrcpyMgr] 添加设备: {device_name} ({serial})")

        self._fire_callback("device_added", serial, device_name)
        return True

    def remove_device(self, serial: str) -> bool:
        """移除设备"""
        with self._lock:
            instance = self.instances.pop(serial, None)
            if instance and instance.running:
                self._stop_instance(instance)

        if instance:
            logger.info(f"[ScrcpyMgr] 移除设备: {instance.device_name}")
            self._fire_callback("device_removed", serial)
            return True
        return False

    # ═══════════════════════════════════════════════════════
    # Scrcpy 控制
    # ═══════════════════════════════════════════════════════

    def start_device(self, serial: str) -> bool:
        """启动单个设备的 Scrcpy 窗口"""
        with self._lock:
            instance = self.instances.get(serial)
            if not instance:
                logger.error(f"[ScrcpyMgr] 设备不存在: {serial}")
                return False
            if instance.running:
                logger.warning(f"[ScrcpyMgr] 设备已在运行: {serial}")
                return True

            return self._start_instance(instance)

    def stop_device(self, serial: str) -> bool:
        """停止单个设备的 Scrcpy"""
        with self._lock:
            instance = self.instances.get(serial)
            if not instance:
                return False
            return self._stop_instance(instance)

    def start_all(self, serials: List[str] = None) -> int:
        """批量启动所有设备"""
        if serials is None:
            serials = list(self.instances.keys())

        started = 0
        for serial in serials:
            if serial not in self.instances:
                self.add_device(serial)
            if self.start_device(serial):
                started += 1
            time.sleep(0.5)  # 错开启动时间

        if started > 0:
            time.sleep(2)  # 等待窗口创建
            self.arrange_windows()

        logger.info(f"[ScrcpyMgr] 已启动 {started}/{len(serials)} 个设备")
        return started

    def stop_all(self):
        """停止所有设备"""
        with self._lock:
            for serial, instance in list(self.instances.items()):
                if instance.running:
                    self._stop_instance(instance)
        logger.info("[ScrcpyMgr] 所有设备已停止")

    def _start_instance(self, instance: ScrcpyInstance) -> bool:
        """启动单个 Scrcpy 实例"""
        if not os.path.exists(self.scrcpy_exe):
            logger.error(f"[ScrcpyMgr] Scrcpy 不存在: {self.scrcpy_exe}")
            return False

        try:
            cmd = [
                self.scrcpy_exe,
                "-s", instance.device_serial,
                f"--window-title={instance.window_title}",
                f"--window-x={instance.x}",
                f"--window-y={instance.y}",
                f"--window-width={instance.width}",
                f"--window-height={instance.height}",
                "--window-borderless",
                "--no-audio",  # 摄像头模式不需要音频
                "--turn-screen-off",
                "--stay-awake",
            ]

            # 摄像头优化
            max_fps = self.config.get("camera_max_fps", 15)
            cmd.append(f"--max-fps={max_fps}")
            cmd.append(f"--video-bit-rate={self.config.get('camera_bit_rate', '2M')}")

            logger.info(f"[ScrcpyMgr] 启动: {instance.device_name}")
            instance.process = subprocess.Popen(
                cmd,
                cwd=self.scrcpy_dir,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            instance.running = True
            return True

        except Exception as e:
            logger.error(f"[ScrcpyMgr] 启动失败 {instance.device_name}: {e}")
            return False

    def _stop_instance(self, instance: ScrcpyInstance) -> bool:
        """停止单个 Scrcpy 实例"""
        if instance.process and instance.process.poll() is None:
            instance.process.terminate()
            try:
                instance.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                instance.process.kill()
        instance.running = False
        instance.process = None
        logger.info(f"[ScrcpyMgr] 已停止: {instance.device_name}")
        return True

    # ═══════════════════════════════════════════════════════
    # 窗口布局
    # ═══════════════════════════════════════════════════════

    def arrange_windows(self, strategy: str = None):
        """重新排列所有窗口"""
        strategy = strategy or self.layout.strategy
        running = [(s, inst) for s, inst in self.instances.items() if inst.running]

        if not running:
            return

        if strategy == "grid":
            self._layout_grid(running)
        elif strategy == "stack":
            self._layout_stack(running)
        elif strategy == "horizontal":
            self._layout_horizontal(running)
        elif strategy == "vertical":
            self._layout_vertical(running)

        # 应用窗口位置 (通过重启 Scrcpy 实现，因为 Scrcpy 启动时固定位置)
        # 或者使用 Win32 API 移动已有窗口
        for serial, instance in running:
            if instance.running and instance.process:
                self._move_window(instance)

        self._fire_callback("layout_changed", strategy, len(running))
        logger.info(f"[ScrcpyMgr] 窗口布局: {strategy} ({len(running)} 个窗口)")

    def _layout_grid(self, running: List[Tuple[str, ScrcpyInstance]]):
        """网格布局"""
        count = len(running)
        cols = min(count, self.layout.max_columns)
        rows = (count + cols - 1) // cols
        margin = self.layout.margin

        # 计算窗口尺寸 (适应屏幕)
        screen_w = 1920  # TODO: 获取实际屏幕分辨率
        screen_h = 1080

        cell_w = (screen_w - margin * (cols + 1)) // cols
        cell_h = (screen_h - margin * (rows + 1)) // rows

        # 保持宽高比
        aspect = self.layout.base_width / self.layout.base_height
        if cell_w / cell_h > aspect:
            cell_w = int(cell_h * aspect)
        else:
            cell_h = int(cell_w / aspect)

        for i, (serial, instance) in enumerate(running):
            row = i // cols
            col = i % cols
            instance.x = margin + col * (cell_w + margin)
            instance.y = margin + row * (cell_h + margin)
            instance.width = cell_w
            instance.height = cell_h

    def _layout_stack(self, running: List[Tuple[str, ScrcpyInstance]]):
        """堆叠布局 (所有窗口重叠，偏移几个像素)"""
        offset = 30
        for i, (serial, instance) in enumerate(running):
            instance.x = 100 + i * offset
            instance.y = 100 + i * offset

    def _layout_horizontal(self, running: List[Tuple[str, ScrcpyInstance]]):
        """水平排列"""
        screen_w = 1920
        margin = self.layout.margin
        cell_w = (screen_w - margin * (len(running) + 1)) // len(running)
        cell_h = int(cell_w / (self.layout.base_width / self.layout.base_height))

        for i, (serial, instance) in enumerate(running):
            instance.x = margin + i * (cell_w + margin)
            instance.y = margin
            instance.width = cell_w
            instance.height = cell_h

    def _layout_vertical(self, running: List[Tuple[str, ScrcpyInstance]]):
        """垂直排列"""
        screen_h = 1080
        margin = self.layout.margin
        cell_h = (screen_h - margin * (len(running) + 1)) // len(running)
        cell_w = int(cell_h * (self.layout.base_width / self.layout.base_height))

        for i, (serial, instance) in enumerate(running):
            instance.x = margin
            instance.y = margin + i * (cell_h + margin)
            instance.width = cell_w
            instance.height = cell_h

    def _move_window(self, instance: ScrcpyInstance):
        """使用 Win32 API 移动窗口"""
        try:
            import ctypes
            hwnd = self._find_window_by_title(instance.window_title)
            if hwnd:
                ctypes.windll.user32.MoveWindow(
                    hwnd, instance.x, instance.y,
                    instance.width, instance.height, True
                )
                instance.window_hwnd = hwnd
        except Exception as e:
            logger.debug(f"[ScrcpyMgr] 移动窗口失败: {e}")

    def _find_window_by_title(self, title: str) -> int:
        """通过标题查找窗口句柄"""
        try:
            import ctypes
            hwnd = ctypes.windll.user32.FindWindowW(None, title)
            return hwnd
        except Exception:
            return 0

    # ═══════════════════════════════════════════════════════
    # 摄像头控制
    # ═══════════════════════════════════════════════════════

    def open_camera(self, serial: str) -> bool:
        """打开设备摄像头 (通过 ADB intent)"""
        instance = self.instances.get(serial)
        if not instance:
            return False

        try:
            # 使用系统相机 intent
            self._adb_shell(serial, [
                "am", "start", "-a", "android.media.action.STILL_IMAGE_CAMERA",
                "--ez", "android.intent.extra.USE_FRONT_CAMERA",
                "false" if instance.camera_facing == "back" else "true",
            ])
            instance.camera_enabled = True
            self._fire_callback("camera_status", serial, True)
            logger.info(f"[ScrcpyMgr] 摄像头已开启: {instance.device_name}")
            return True
        except Exception as e:
            logger.error(f"[ScrcpyMgr] 开启摄像头失败: {e}")
            return False

    def close_camera(self, serial: str) -> bool:
        """关闭设备摄像头"""
        instance = self.instances.get(serial)
        if not instance:
            return False

        try:
            self._adb_shell(serial, ["input", "keyevent", "KEYCODE_BACK"])
            instance.camera_enabled = False
            self._fire_callback("camera_status", serial, False)
            logger.info(f"[ScrcpyMgr] 摄像头已关闭: {instance.device_name}")
            return True
        except Exception as e:
            logger.error(f"[ScrcpyMgr] 关闭摄像头失败: {e}")
            return False

    def toggle_camera(self, serial: str) -> bool:
        """切换摄像头开关"""
        instance = self.instances.get(serial)
        if not instance:
            return False
        if instance.camera_enabled:
            return self.close_camera(serial)
        else:
            return self.open_camera(serial)

    def switch_facing(self, serial: str):
        """切换前后摄像头"""
        instance = self.instances.get(serial)
        if not instance:
            return

        was_running = instance.camera_enabled
        if was_running:
            self.close_camera(serial)

        instance.camera_facing = "front" if instance.camera_facing == "back" else "back"

        if was_running:
            self.open_camera(serial)

        logger.info(f"[ScrcpyMgr] 切换摄像头方向: {instance.device_name} -> {instance.camera_facing}")

    # ═══════════════════════════════════════════════════════
    # 截图 / 录制
    # ═══════════════════════════════════════════════════════

    def capture_screenshot(self, serial: str, save_dir: str = None) -> Optional[str]:
        """截取指定设备的屏幕"""
        save_dir = save_dir or self.config.get("screenshot_dir", "D:\\Fusion\\Camera")
        os.makedirs(save_dir, exist_ok=True)

        instance = self.instances.get(serial)
        name = instance.device_name if instance else serial
        filename = f"camera_{name}_{int(time.time())}.png"
        filepath = os.path.join(save_dir, filename)

        try:
            subprocess.run(
                [self.adb_path, "-s", serial, "exec-out", "screencap", "-p"],
                capture_output=True, timeout=10,
                stdout=open(filepath, "wb"),
            )
            logger.info(f"[ScrcpyMgr] 截图保存: {filepath}")
            return filepath
        except Exception as e:
            logger.error(f"[ScrcpyMgr] 截图失败: {e}")
            return None

    def capture_all(self, save_dir: str = None) -> List[str]:
        """截取所有设备屏幕"""
        results = []
        for serial in self.instances:
            path = self.capture_screenshot(serial, save_dir)
            if path:
                results.append(path)
        return results

    # ═══════════════════════════════════════════════════════
    # 状态查询
    # ═══════════════════════════════════════════════════════

    def get_status(self) -> Dict[str, Any]:
        """获取所有设备状态"""
        return {
            serial: {
                "name": inst.device_name,
                "running": inst.running,
                "camera_enabled": inst.camera_enabled,
                "camera_facing": inst.camera_facing,
                "position": {"x": inst.x, "y": inst.y, "w": inst.width, "h": inst.height},
                "window_title": inst.window_title,
            }
            for serial, inst in self.instances.items()
        }

    def get_running_count(self) -> int:
        """获取运行中设备数"""
        return sum(1 for inst in self.instances.values() if inst.running)

    def capture_screenshot(self, device_serial: str = None) -> Optional[str]:
        """
        截取指定设备的屏幕截图，返回 Base64 编码的 PNG 图片
        
        Args:
            device_serial: 设备序列号，None 则截取第一个运行中的设备
        
        Returns:
            Base64 编码的 PNG，失败返回 None
        """
        target = device_serial
        if not target:
            running = [s for s, i in self.instances.items() if i.running]
            if not running:
                # 没有多设备实例，用 daemon 的默认设备
                if self.config:
                    target = None  # 使用默认 ADB 设备
                else:
                    return None
            else:
                target = running[0]
        
        try:
            import base64
            import tempfile
            
            # 在 PC 端创建临时文件
            with tempfile.NamedTemporaryFile(suffix='.png', delete=False) as f:
                tmp_path = f.name
            
            # ADB screencap
            adb_cmd = [self.adb_path]
            if target:
                adb_cmd += ["-s", target]
            adb_cmd += ["exec-out", "screencap", "-p"]
            
            result = subprocess.run(
                adb_cmd, capture_output=True, timeout=10,
            )
            
            if result.returncode == 0 and len(result.stdout) > 1000:
                with open(tmp_path, 'wb') as f:
                    f.write(result.stdout)
                
                # 转为 Base64
                with open(tmp_path, 'rb') as f:
                    b64 = base64.b64encode(f.read()).decode()
                
                os.unlink(tmp_path)
                logger.info(f"[ScrcpyMgr] 截图成功: {target or 'default'} ({len(b64)} chars)")
                return b64
            else:
                logger.warning(f"[ScrcpyMgr] 截图失败: returncode={result.returncode}")
                if os.path.exists(tmp_path):
                    os.unlink(tmp_path)
                return None
                
        except Exception as e:
            logger.error(f"[ScrcpyMgr] 截图异常: {e}")
            return None

    # ═══════════════════════════════════════════════════════
    # 回调
    # ═══════════════════════════════════════════════════════

    def on(self, event: str, callback):
        """注册事件回调"""
        if event in self._callbacks:
            self._callbacks[event].append(callback)

    def _fire_callback(self, event: str, *args):
        for cb in self._callbacks.get(event, []):
            try:
                cb(*args)
            except Exception as e:
                logger.debug(f"[ScrcpyMgr] 回调异常 ({event}): {e}")

    # ═══════════════════════════════════════════════════════
    # 工具方法
    # ═══════════════════════════════════════════════════════

    def _adb_shell(self, serial: str, args: List[str], timeout: int = 10) -> Tuple[str, str, int]:
        """执行 ADB shell 命令"""
        cmd = [self.adb_path, "-s", serial, "shell"] + args
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            return result.stdout.strip(), result.stderr.strip(), result.returncode
        except subprocess.TimeoutExpired:
            return "", "Timeout", 1
        except Exception as e:
            return "", str(e), 1

    def cleanup(self):
        """清理所有资源"""
        self.stop_all()
        self.instances.clear()
