"""
摄像头流桥接模块 — 接收手机摄像头帧流并处理

功能:
  - 通过 WS 接收手机摄像头的 JPEG 帧流
  - 实时显示 (OpenCV 窗口 / 浏览器 Dashboard)
  - 录制视频 (保存为 MP4)
  - 定时截图 (保存为 JPEG)
  - Dashboard HTTP API 控制
  - MQTT 命令触发

协议:
  PC → Phone (WS):
    {"type": "camera_control", "action": "start", "camera_id": 0, "width": 640, "height": 480, "quality": 60, "fps": 10}
    {"type": "camera_control", "action": "stop"}
    {"type": "camera_control", "action": "switch"}
    {"type": "camera_control", "action": "snapshot"}
    {"type": "camera_control", "action": "info"}

  Phone → PC (WS):
    {"type": "camera_frame", "seq": N, "camera_id": 0, "width": W, "height": H, "data": "base64_jpeg", "ts": ...}
    {"type": "camera_snapshot", ...}  (单帧高质量截图)
    {"type": "camera_status", "status": "started|stopped|error", "message": "..."}
    {"type": "camera_info", "cameras": {...}, "streaming": true, "current_camera": 0}
"""

import base64
import io
import json
import logging
import os
import threading
import time
from datetime import datetime
from typing import Callable, Dict, List, Optional, Any

logger = logging.getLogger(__name__)


class CameraStreamBridge:
    """摄像头流桥接 — 接收、显示、录制"""

    def __init__(self, config: dict = None):
        self.config = config or {}
        self._adb_path = self.config.get("adb", {}).get("path", "adb")
        self._ws_client = None
        self._mqtt_client = None

        # 流状态
        self._streaming = False
        self._frame_count = 0
        self._last_frame_time = 0
        self._fps_actual = 0.0
        self._fps_samples: List[float] = []
        self._camera_info: Dict = {}
        self._current_camera = 0

        # 录制
        self._recording = False
        self._recording_dir = self.config.get("camera", {}).get("recording_dir", "camera_recordings")
        self._recording_writer = None
        self._recording_start = 0
        self._recording_frames = 0

        # 定时截图
        self._snapshot_dir = self.config.get("camera", {}).get("snapshot_dir", "camera_snapshots")
        self._snapshot_interval = self.config.get("camera", {}).get("snapshot_interval", 0)  # 0=关闭
        self._last_snapshot_time = 0

        # 显示
        self._show_window = self.config.get("camera", {}).get("show_window", False)

        # 回调
        self._on_frame_callbacks: List[Callable] = []
        self._on_status_callbacks: List[Callable] = []

        # 最新帧缓存 (给 Dashboard 用)
        self._latest_frame_b64: Optional[str] = None
        self._latest_frame_time: float = 0
        self._frame_lock = threading.Lock()

    # ═══════════════════════════════════════════════════════
    # 初始化 & 生命周期
    # ═══════════════════════════════════════════════════════

    def start(self, ws_client=None, mqtt_client=None):
        """启动摄像头桥接"""
        self._ws_client = ws_client
        self._mqtt_client = mqtt_client

        # 注册 WS 事件处理
        if ws_client:
            ws_client.on("camera_frame", self._on_camera_frame)
            ws_client.on("camera_snapshot", self._on_camera_snapshot)
            ws_client.on("camera_status", self._on_camera_status)
            ws_client.on("camera_info", self._on_camera_info)

        logger.info("[CameraBridge] 摄像头流桥接已启动")

    def stop(self):
        """停止摄像头桥接"""
        if self._streaming:
            self.stop_stream()
        if self._recording:
            self.stop_recording()
        logger.info("[CameraBridge] 摄像头流桥接已停止")

    # ═══════════════════════════════════════════════════════
    # 流控制
    # ═══════════════════════════════════════════════════════

    def start_stream(self, camera_id: int = 0, width: int = 640, height: int = 480,
                     quality: int = 60, fps: int = 10) -> bool:
        """启动摄像头流"""
        cmd = {
            "type": "camera_control",
            "action": "start",
            "camera_id": camera_id,
            "width": width,
            "height": height,
            "quality": quality,
            "fps": fps,
        }
        if self._ws_client and self._ws_client.send(cmd):
            self._streaming = True
            self._frame_count = 0
            self._current_camera = camera_id
            logger.info(f"[CameraBridge] 启动摄像头流: camera={camera_id}, {width}x{height}, Q={quality}, fps={fps}")
            return True
        else:
            logger.error("[CameraBridge] 发送摄像头启动命令失败")
            return False

    def stop_stream(self) -> bool:
        """停止摄像头流"""
        cmd = {"type": "camera_control", "action": "stop"}
        if self._ws_client and self._ws_client.send(cmd):
            self._streaming = False
            logger.info("[CameraBridge] 停止摄像头流")
            return True
        return False

    def switch_camera(self) -> bool:
        """切换前后摄像头"""
        cmd = {"type": "camera_control", "action": "switch"}
        if self._ws_client and self._ws_client.send(cmd):
            self._current_camera = 1 - self._current_camera
            logger.info(f"[CameraBridge] 切换摄像头: camera={self._current_camera}")
            return True
        return False

    def take_snapshot(self) -> bool:
        """请求高质量截图"""
        cmd = {"type": "camera_control", "action": "snapshot"}
        if self._ws_client and self._ws_client.send(cmd):
            logger.info("[CameraBridge] 截图请求已发送")
            return True
        return False

    def get_camera_info(self) -> bool:
        """查询摄像头信息"""
        cmd = {"type": "camera_control", "action": "info"}
        if self._ws_client and self._ws_client.send(cmd):
            return True
        return False

    # ═══════════════════════════════════════════════════════
    # 录制
    # ═══════════════════════════════════════════════════════

    def start_recording(self, filename: str = None) -> bool:
        """开始录制视频"""
        if not self._streaming:
            logger.warning("[CameraBridge] 未在流传输中，无法录制")
            return False
        if self._recording:
            logger.warning("[CameraBridge] 已在录制中")
            return False

        try:
            import cv2
        except ImportError:
            logger.error("[CameraBridge] 录制需要 opencv-python: pip install opencv-python")
            return False

        os.makedirs(self._recording_dir, exist_ok=True)

        if not filename:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"camera_recording_{timestamp}.mp4"

        filepath = os.path.join(self._recording_dir, filename)

        # 获取帧尺寸 (从最新帧或默认)
        width, height = 640, 480
        with self._frame_lock:
            if self._latest_frame_b64:
                try:
                    img_data = base64.b64decode(self._latest_frame_b64)
                    import numpy as np
                    nparr = np.frombuffer(img_data, np.uint8)
                    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                    if img is not None:
                        height, width = img.shape[:2]
                except Exception:
                    pass

        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        self._recording_writer = cv2.VideoWriter(filepath, fourcc, 10.0, (width, height))

        if not self._recording_writer.isOpened():
            logger.error("[CameraBridge] 无法创建录制文件")
            return False

        self._recording = True
        self._recording_start = time.time()
        self._recording_frames = 0
        logger.info(f"[CameraBridge] 开始录制: {filepath}")
        return True

    def stop_recording(self) -> Optional[str]:
        """停止录制，返回文件路径"""
        if not self._recording:
            return None

        self._recording = False
        filepath = None

        if self._recording_writer:
            filepath = self._recording_writer.getFilename() if hasattr(self._recording_writer, 'getFilename') else None
            self._recording_writer.release()
            self._recording_writer = None

        duration = time.time() - self._recording_start if self._recording_start else 0
        logger.info(f"[CameraBridge] 录制停止: {self._recording_frames} 帧, {duration:.1f}s, {filepath}")
        return filepath

    # ═══════════════════════════════════════════════════════
    # WS 事件处理
    # ═══════════════════════════════════════════════════════

    def _on_camera_frame(self, data: dict):
        """处理摄像头帧"""
        b64_data = data.get("data", "")
        if not b64_data:
            return

        self._frame_count += 1
        now = time.time()

        # 帧率计算
        self._fps_samples.append(now)
        # 保留最近 30 个采样
        if len(self._fps_samples) > 30:
            self._fps_samples = self._fps_samples[-30:]
        if len(self._fps_samples) >= 2:
            elapsed = self._fps_samples[-1] - self._fps_samples[0]
            if elapsed > 0:
                self._fps_actual = (len(self._fps_samples) - 1) / elapsed

        # 缓存最新帧 (给 Dashboard 用)
        with self._frame_lock:
            self._latest_frame_b64 = b64_data
            self._latest_frame_time = now

        # 录制
        if self._recording and self._recording_writer:
            self._write_frame(b64_data)

        # 定时截图
        if self._snapshot_interval > 0 and now - self._last_snapshot_time >= self._snapshot_interval:
            self._save_snapshot(b64_data)
            self._last_snapshot_time = now

        # 显示窗口
        if self._show_window:
            self._show_frame(b64_data)

        # 回调
        for cb in self._on_frame_callbacks:
            try:
                cb(data)
            except Exception as e:
                logger.debug(f"[CameraBridge] 帧回调异常: {e}")

        # 日志节流
        if self._frame_count % 100 == 0:
            logger.debug(f"[CameraBridge] 帧流: {self._frame_count} 帧, 实际 {self._fps_actual:.1f} fps")

    def _on_camera_snapshot(self, data: dict):
        """处理摄像头截图"""
        b64_data = data.get("data", "")
        if not b64_data:
            return

        self._save_snapshot(b64_data)
        logger.info(f"[CameraBridge] 收到截图: seq={data.get('seq', 0)}")

    def _on_camera_status(self, data: dict):
        """处理摄像头状态通知"""
        status = data.get("status", "")
        message = data.get("message", "")

        if status == "stopped":
            self._streaming = False
            if self._recording:
                self.stop_recording()

        logger.info(f"[CameraBridge] 摄像头状态: {status} - {message}")

        for cb in self._on_status_callbacks:
            try:
                cb(status, message)
            except Exception:
                pass

    def _on_camera_info(self, data: dict):
        """处理摄像头信息"""
        self._camera_info = data.get("cameras", {})
        self._current_camera = data.get("current_camera", 0)
        logger.info(f"[CameraBridge] 摄像头信息: {json.dumps(self._camera_info)}")

    # ═══════════════════════════════════════════════════════
    # 内部工具
    # ═══════════════════════════════════════════════════════

    def _save_snapshot(self, b64_data: str) -> Optional[str]:
        """保存截图到文件"""
        try:
            os.makedirs(self._snapshot_dir, exist_ok=True)
            img_data = base64.b64decode(b64_data)
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
            filename = f"snapshot_{timestamp}.jpg"
            filepath = os.path.join(self._snapshot_dir, filename)

            with open(filepath, 'wb') as f:
                f.write(img_data)

            logger.info(f"[CameraBridge] 截图保存: {filepath} ({len(img_data)} bytes)")
            return filepath
        except Exception as e:
            logger.error(f"[CameraBridge] 保存截图失败: {e}")
            return None

    def _write_frame(self, b64_data: str):
        """写入帧到录制文件"""
        try:
            import cv2
            import numpy as np

            img_data = base64.b64decode(b64_data)
            nparr = np.frombuffer(img_data, np.uint8)
            img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

            if img is not None:
                self._recording_writer.write(img)
                self._recording_frames += 1
        except Exception as e:
            logger.debug(f"[CameraBridge] 录制帧写入异常: {e}")

    def _show_frame(self, b64_data: str):
        """用 OpenCV 窗口显示帧"""
        try:
            import cv2
            import numpy as np

            img_data = base64.b64decode(b64_data)
            nparr = np.frombuffer(img_data, np.uint8)
            img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

            if img is not None:
                cv2.imshow("Fusion Camera", img)
                cv2.waitKey(1)  # 必须调用才能更新窗口
        except ImportError:
            logger.debug("[CameraBridge] opencv-python 未安装，无法显示窗口")
            self._show_window = False
        except Exception as e:
            logger.debug(f"[CameraBridge] 显示帧异常: {e}")

    # ═══════════════════════════════════════════════════════
    # 回调注册
    # ═══════════════════════════════════════════════════════

    def on_frame(self, callback: Callable):
        """注册帧回调"""
        self._on_frame_callbacks.append(callback)

    def on_status(self, callback: Callable):
        """注册状态回调"""
        self._on_status_callbacks.append(callback)

    # ═══════════════════════════════════════════════════════
    # 状态查询
    # ═══════════════════════════════════════════════════════

    def get_status(self) -> Dict[str, Any]:
        """获取当前状态"""
        return {
            "streaming": self._streaming,
            "recording": self._recording,
            "frame_count": self._frame_count,
            "fps_actual": round(self._fps_actual, 1),
            "current_camera": self._current_camera,
            "camera_info": self._camera_info,
            "recording_duration": round(time.time() - self._recording_start, 1) if self._recording and self._recording_start else 0,
            "recording_frames": self._recording_frames,
            "has_latest_frame": self._latest_frame_b64 is not None,
            "latest_frame_age": round(time.time() - self._latest_frame_time, 2) if self._latest_frame_time else -1,
        }

    def get_latest_frame(self) -> Optional[str]:
        """获取最新帧的 Base64 JPEG (给 Dashboard 用)"""
        with self._frame_lock:
            return self._latest_frame_b64

    def get_latest_frame_bytes(self) -> Optional[bytes]:
        """获取最新帧的原始 JPEG bytes"""
        with self._frame_lock:
            if self._latest_frame_b64:
                return base64.b64decode(self._latest_frame_b64)
            return None
