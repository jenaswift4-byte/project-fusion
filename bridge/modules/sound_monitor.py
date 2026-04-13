"""
声音监测模块
PC 端通过 WebSocket 接收手机麦克风音频流，进行静音分析

功能:
  - WebSocket 接收音频流 (PCM 16-bit)
  - 音量级别计算 (RMS)
  - 异常声音检测 (阈值告警)
  - 声音事件记录
  - 不发出声音，只做分析

场景:
  - 婴儿哭声检测
  - 玻璃破碎检测 (高频尖锐声)
  - 敲门检测
  - 环境噪音监控

注意: 此模块只做分析，不发出声音
"""

import struct
import math
import logging
import time
import threading
from typing import Dict, List, Optional, Callable, Any
from collections import deque

logger = logging.getLogger(__name__)


class AudioAnalyzer:
    """音频分析器 - 纯计算，不发声"""

    def __init__(self, sample_rate: int = 16000, channels: int = 1):
        self.sample_rate = sample_rate
        self.channels = channels

        # 音量历史 (RMS 值)
        self._volume_history: deque = deque(maxlen=300)  # ~10秒 @ 30fps
        self._volume_lock = threading.Lock()

        # 异常检测配置
        self.alert_threshold_db = 80  # dB 告警阈值
        self.sustained_alert_seconds = 3  # 持续告警时间
        self._alert_start_time: Optional[float] = None
        self._alert_active = False

        # 频谱分析缓冲
        self._fft_buffer: deque = deque(maxlen=sample_rate * 2)  # 2秒缓冲

        # 回调
        self._on_volume_callbacks: List[Callable[[float, float], None]] = []
        self._on_alert_callbacks: List[Callable[[str, float], None]] = []

    def analyze_pcm(self, pcm_data: bytes) -> Dict[str, Any]:
        """
        分析 PCM 音频数据 (16-bit signed, little-endian)

        Returns:
            {
                "rms": float,        # RMS 音量
                "db": float,         # 分贝值
                "peak": float,       # 峰值
                "zero_crossings": int,  # 过零率 (频率估计)
                "alert": bool,       # 是否触发告警
            }
        """
        if len(pcm_data) < 4:
            return {"rms": 0, "db": -100, "peak": 0, "zero_crossings": 0, "alert": False}

        try:
            # 解码 PCM 16-bit
            num_samples = len(pcm_data) // 2
            samples = struct.unpack(f"<{num_samples}h", pcm_data[:num_samples * 2])

            # RMS 计算
            sum_sq = sum(s * s for s in samples)
            rms = math.sqrt(sum_sq / len(samples)) if samples else 0

            # 分贝 (参考值 32767 = 最大 16-bit 值)
            db = 20 * math.log10(rms / 32767) if rms > 0 else -100

            # 峰值
            peak = max(abs(s) for s in samples) if samples else 0

            # 过零率 (简易频率估计)
            zero_crossings = 0
            for i in range(1, len(samples)):
                if (samples[i] >= 0) != (samples[i - 1] >= 0):
                    zero_crossings += 1

            # 存储到缓冲
            with self._volume_lock:
                self._volume_history.append(rms)

            # 告警检测
            alert = self._check_alert(db)

            # 通知回调
            for cb in self._on_volume_callbacks:
                try:
                    cb(rms, db)
                except Exception:
                    pass

            if alert:
                for cb in self._on_alert_callbacks:
                    try:
                        cb("noise", db)
                    except Exception:
                        pass

            return {
                "rms": round(rms, 2),
                "db": round(db, 1),
                "peak": peak,
                "zero_crossings": zero_crossings,
                "alert": alert,
            }

        except Exception as e:
            logger.debug(f"[SoundMonitor] PCM 分析失败: {e}")
            return {"rms": 0, "db": -100, "peak": 0, "zero_crossings": 0, "alert": False}

    def analyze_frequency(self, pcm_data: bytes) -> Dict[str, Any]:
        """
        频率分析 (简易 FFT)
        
        Returns:
            {
                "dominant_freq": float,  # 主频率 Hz
                "spectral_centroid": float,  # 频谱重心
                "high_energy_ratio": float,  # 高频能量比 (>4000Hz)
            }
        """
        if len(pcm_data) < 256:
            return {"dominant_freq": 0, "spectral_centroid": 0, "high_energy_ratio": 0}

        try:
            import numpy as np
            samples = np.frombuffer(pcm_data[:len(pcm_data) // 2 * 2], dtype=np.int16).astype(np.float64)

            # FFT
            fft = np.abs(np.fft.rfft(samples))
            freqs = np.fft.rfftfreq(len(samples), 1.0 / self.sample_rate)

            # 主频率
            max_idx = np.argmax(fft[1:]) + 1  # 跳过 DC 分量
            dominant_freq = freqs[max_idx]

            # 频谱重心
            if fft.sum() > 0:
                spectral_centroid = np.sum(freqs * fft) / np.sum(fft)
            else:
                spectral_centroid = 0

            # 高频能量比 (>4000Hz)
            high_mask = freqs > 4000
            high_energy = np.sum(fft[high_mask] ** 2)
            total_energy = np.sum(fft ** 2)
            high_energy_ratio = high_energy / total_energy if total_energy > 0 else 0

            return {
                "dominant_freq": round(dominant_freq, 1),
                "spectral_centroid": round(spectral_centroid, 1),
                "high_energy_ratio": round(high_energy_ratio, 4),
            }

        except ImportError:
            # 没有 numpy，返回空值
            return {"dominant_freq": 0, "spectral_centroid": 0, "high_energy_ratio": 0}
        except Exception as e:
            logger.debug(f"[SoundMonitor] 频率分析失败: {e}")
            return {"dominant_freq": 0, "spectral_centroid": 0, "high_energy_ratio": 0}

    def _check_alert(self, current_db: float) -> bool:
        """检查是否触发告警"""
        now = time.time()

        if current_db > self.alert_threshold_db:
            if self._alert_start_time is None:
                self._alert_start_time = now
            elif now - self._alert_start_time >= self.sustained_alert_seconds:
                if not self._alert_active:
                    self._alert_active = True
                    return True
        else:
            self._alert_start_time = None
            if self._alert_active:
                self._alert_active = False

        return False

    def get_current_volume_db(self) -> float:
        """获取当前音量 (dB)"""
        with self._volume_lock:
            if not self._volume_history:
                return -100
            rms = self._volume_history[-1]
            return round(20 * math.log10(rms / 32767), 1) if rms > 0 else -100

    def get_volume_history(self, count: int = 60) -> List[float]:
        """获取音量历史 (dB)"""
        with self._volume_lock:
            return [
                round(20 * math.log10(rms / 32767), 1) if rms > 0 else -100
                for rms in list(self._volume_history)[-count:]
            ]

    def set_alert_threshold(self, db: float):
        """设置告警阈值"""
        self.alert_threshold_db = db
        logger.info(f"[SoundMonitor] 告警阈值: {db} dB")

    def on_volume(self, callback: Callable[[float, float], None]):
        """注册音量回调: callback(rms, db)"""
        self._on_volume_callbacks.append(callback)

    def on_alert(self, callback: Callable[[str, float], None]):
        """注册告警回调: callback(alert_type, db)"""
        self._on_alert_callbacks.append(callback)


class SoundMonitor:
    """
    声音监控模块 (供 main.py 集成)

    支持两种模式:
    1. PC 麦克风实时采集 (默认) - 直接采集 PC 环境声音
    2. WebSocket 接收手机音频流 - 通过 feed_audio() 手动喂入数据

    不发出声音，只做分析。
    """

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self.analyzer = AudioAnalyzer()
        self._device_monitors: Dict[str, Dict] = {}
        self._alert_callbacks: List[Callable] = []

        # PC 麦克风采集配置
        self._mic_stream = None
        self._mic_thread: Optional[threading.Thread] = None
        self._mic_sample_rate = 16000
        self._mic_channels = 1
        self._mic_chunk_size = 1024  # 每次读取的采样数 (~64ms @ 16kHz)

    def start(self) -> bool:
        """启动声音监控 (PC 麦克风采集)"""
        self.running = True
        self.analyzer.on_alert(self._on_alert)

        # 启动 PC 麦克风采集
        try:
            self._start_mic_capture()
            logger.info("[SoundMonitor] 已启动 (PC 麦克风实时采集)")
        except Exception as e:
            logger.warning(f"[SoundMonitor] PC 麦克风启动失败: {e}，仅支持外部音频喂入")
            logger.info("[SoundMonitor] 已启动 (仅外部音频模式)")

        return True

    def _start_mic_capture(self):
        """启动 PC 麦克风实时采集"""
        try:
            import sounddevice as sd

            def audio_callback(indata, frames, time_info, status):
                """音频回调 (在独立线程中执行)"""
                if status:
                    logger.debug(f"[SoundMonitor] 麦克风状态: {status}")
                # indata shape: (frames, channels) -> 转为 bytes (PCM 16-bit)
                pcm = indata.astype('int16').tobytes()
                result = self.feed_audio("pc_mic", pcm)

            self._mic_stream = sd.InputStream(
                samplerate=self._mic_sample_rate,
                channels=self._mic_channels,
                dtype='int16',
                blocksize=self._mic_chunk_size,
                callback=audio_callback,
            )
            self._mic_stream.start()
            logger.info(f"[SoundMonitor] PC 麦克风已启动 (采样率: {self._mic_sample_rate}Hz)")

        except ImportError:
            logger.warning("[SoundMonitor] sounddevice 未安装，无法采集 PC 麦克风")
            logger.info("[SoundMonitor] 安装方法: pip install sounddevice")
        except Exception as e:
            logger.error(f"[SoundMonitor] PC 麦克风启动失败: {e}")

    def _stop_mic_capture(self):
        """停止 PC 麦克风采集"""
        if self._mic_stream:
            try:
                self._mic_stream.stop()
                self._mic_stream.close()
            except Exception:
                pass
            self._mic_stream = None
            logger.info("[SoundMonitor] PC 麦克风已停止")

    def stop(self):
        """停止声音监控"""
        self.running = False
        self._stop_mic_capture()
        logger.info("[SoundMonitor] 已停止")

    def feed_audio(self, device_id: str, pcm_data: bytes) -> Dict[str, Any]:
        """
        接收音频数据进行分析 (供 WebSocket 回调调用)

        Args:
            device_id: 设备 ID
            pcm_data: PCM 16-bit 音频数据
        """
        if not self.running:
            return {}

        result = self.analyzer.analyze_pcm(pcm_data)

        # 存储设备级数据
        self._device_monitors[device_id] = {
            "last_volume_db": result["db"],
            "last_peak": result["peak"],
            "alert": result["alert"],
            "timestamp": time.time(),
        }

        return result

    def get_status(self) -> Dict[str, Any]:
        """获取监控状态"""
        return {
            "running": self.running,
            "current_db": self.analyzer.get_current_volume_db(),
            "alert_active": self.analyzer._alert_active,
            "alert_threshold": self.analyzer.alert_threshold_db,
            "devices": self._device_monitors.copy(),
            "volume_history": self.analyzer.get_volume_history(60),
        }

    def set_alert_threshold(self, db: float):
        """设置告警阈值"""
        self.analyzer.set_alert_threshold(db)

    def on_alert(self, callback):
        """注册告警回调"""
        self._alert_callbacks.append(callback)

    def _on_alert(self, alert_type: str, db: float):
        """内部告警处理"""
        logger.warning(f"[SoundMonitor] 告警: {alert_type}, {db:.1f} dB")
        for cb in self._alert_callbacks:
            try:
                cb(alert_type, db)
            except Exception:
                pass
