"""
声音监测模块 v2.0
支持手机麦克风 + PC 麦克风双源音频分析

功能:
  - PC 麦克风实时采集 (sounddevice)
  - 手机麦克风音频流接收 (MQTT feed_audio)
  - 音量级别计算 (RMS)
  - 异常声音检测 (阈值告警)
  - 声音事件记录
  - 频率分析 (简易 FFT)
  - PC 扬声器告警 (可选: 检测到异常时 PC 发出提示音)

场景:
  - 婴儿哭声检测 (高频 + 持续)
  - 玻璃破碎检测 (高频尖锐声)
  - 敲门检测 (低频冲击)
  - 环境噪音监控
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
    """音频分析器"""

    def __init__(self, sample_rate: int = 16000, channels: int = 1):
        self.sample_rate = sample_rate
        self.channels = channels

        # 音量历史 (RMS 值)
        self._volume_history: deque = deque(maxlen=300)
        self._volume_lock = threading.Lock()

        # 异常检测配置
        self.alert_threshold_db = 80
        self.sustained_alert_seconds = 3
        self._alert_start_time: Optional[float] = None
        self._alert_active = False

        # 频谱分析缓冲
        self._fft_buffer: deque = deque(maxlen=sample_rate * 2)

        # 回调
        self._on_volume_callbacks: List[Callable[[float, float], None]] = []
        self._on_alert_callbacks: List[Callable[[str, float], None]] = []

    def analyze_pcm(self, pcm_data: bytes) -> Dict[str, Any]:
        """分析 PCM 音频数据 (16-bit signed, little-endian)"""
        if len(pcm_data) < 4:
            return {"rms": 0, "db": -100, "peak": 0, "zero_crossings": 0, "alert": False}

        try:
            num_samples = len(pcm_data) // 2
            samples = struct.unpack(f"<{num_samples}h", pcm_data[:num_samples * 2])

            sum_sq = sum(s * s for s in samples)
            rms = math.sqrt(sum_sq / len(samples)) if samples else 0
            db = 20 * math.log10(rms / 32767) if rms > 0 else -100
            peak = max(abs(s) for s in samples) if samples else 0

            zero_crossings = 0
            for i in range(1, len(samples)):
                if (samples[i] >= 0) != (samples[i - 1] >= 0):
                    zero_crossings += 1

            with self._volume_lock:
                self._volume_history.append(rms)

            alert = self._check_alert(db)

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
        """频率分析 (简易 FFT)"""
        if len(pcm_data) < 256:
            return {"dominant_freq": 0, "spectral_centroid": 0, "high_energy_ratio": 0}

        try:
            import numpy as np
            samples = np.frombuffer(pcm_data[:len(pcm_data) // 2 * 2], dtype=np.int16).astype(np.float64)
            fft = np.abs(np.fft.rfft(samples))
            freqs = np.fft.rfftfreq(len(samples), 1.0 / self.sample_rate)
            max_idx = np.argmax(fft[1:]) + 1
            dominant_freq = freqs[max_idx]
            if fft.sum() > 0:
                spectral_centroid = np.sum(freqs * fft) / np.sum(fft)
            else:
                spectral_centroid = 0
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
            return {"dominant_freq": 0, "spectral_centroid": 0, "high_energy_ratio": 0}
        except Exception as e:
            logger.debug(f"[SoundMonitor] 频率分析失败: {e}")
            return {"dominant_freq": 0, "spectral_centroid": 0, "high_energy_ratio": 0}

    def _check_alert(self, current_db: float) -> bool:
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
        with self._volume_lock:
            if not self._volume_history:
                return -100
            rms = self._volume_history[-1]
            return round(20 * math.log10(rms / 32767), 1) if rms > 0 else -100

    def get_volume_history(self, count: int = 60) -> List[float]:
        with self._volume_lock:
            return [
                round(20 * math.log10(rms / 32767), 1) if rms > 0 else -100
                for rms in list(self._volume_history)[-count:]
            ]

    def set_alert_threshold(self, db: float):
        self.alert_threshold_db = db
        logger.info(f"[SoundMonitor] 告警阈值: {db} dB")

    def on_volume(self, callback: Callable[[float, float], None]):
        self._on_volume_callbacks.append(callback)

    def on_alert(self, callback: Callable[[str, float], None]):
        self._on_alert_callbacks.append(callback)


class SoundMonitor:
    """
    声音监控模块 v2.0
    
    支持两种模式:
    1. PC 麦克风实时采集 (默认)
    2. 手机麦克风音频流 (通过 feed_audio() 或 MQTT audio topic 接收)
    """

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self.analyzer = AudioAnalyzer()
        self._device_monitors: Dict[str, Dict] = {}
        self._alert_callbacks: List[Callable] = []
        self._alert_count = 0
        self._last_alert_time = 0

        # PC 麦克风采集配置
        self._mic_stream = None
        self._mic_thread: Optional[threading.Thread] = None
        self._mic_sample_rate = 16000
        self._mic_channels = 1
        self._mic_chunk_size = 1024

        # PC 提示音配置
        self._play_alert_sound = True

    def start(self) -> bool:
        """启动声音监控"""
        self.running = True
        self.analyzer.on_alert(self._on_alert)

        # 启动 PC 麦克风采集
        try:
            self._start_mic_capture()
            logger.info("[SoundMonitor v2.0] 已启动 (PC 麦克风 + 手机 MQTT 双源)")
        except Exception as e:
            logger.warning(f"[SoundMonitor] PC 麦克风启动失败: {e}，仅支持外部音频喂入")
            logger.info("[SoundMonitor v2.0] 已启动 (仅外部音频模式)")

        return True

    def _start_mic_capture(self):
        """启动 PC 麦克风实时采集"""
        try:
            import sounddevice as sd

            def audio_callback(indata, frames, time_info, status):
                if status:
                    logger.debug(f"[SoundMonitor] 麦克风状态: {status}")
                pcm = indata.astype('int16').tobytes()
                self.feed_audio("pc_mic", pcm)

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
        except Exception as e:
            logger.error(f"[SoundMonitor] PC 麦克风启动失败: {e}")

    def _stop_mic_capture(self):
        if self._mic_stream:
            try:
                self._mic_stream.stop()
                self._mic_stream.close()
            except Exception:
                pass
            self._mic_stream = None

    def stop(self):
        self.running = False
        self._stop_mic_capture()
        logger.info("[SoundMonitor] 已停止")

    def feed_audio(self, device_id: str, pcm_data: bytes) -> Dict[str, Any]:
        """
        接收音频数据进行分析
        
        供以下来源调用:
        - PC 麦克风回调
        - AudioBridge.start_mic_to_pc() 的手机麦克风流
        - WebSocket 回调 (Termux Bridge)
        """
        if not self.running:
            return {}

        result = self.analyzer.analyze_pcm(pcm_data)

        self._device_monitors[device_id] = {
            "last_volume_db": result["db"],
            "last_peak": result["peak"],
            "alert": result["alert"],
            "timestamp": time.time(),
        }

        return result

    def get_status(self) -> Dict[str, Any]:
        return {
            "running": self.running,
            "current_db": self.analyzer.get_current_volume_db(),
            "alert_active": self.analyzer._alert_active,
            "alert_threshold": self.analyzer.alert_threshold_db,
            "alert_count": self._alert_count,
            "devices": self._device_monitors.copy(),
            "volume_history": self.analyzer.get_volume_history(60),
        }

    def set_alert_threshold(self, db: float):
        self.analyzer.set_alert_threshold(db)

    def on_alert(self, callback):
        self._alert_callbacks.append(callback)

    def _on_alert(self, alert_type: str, db: float):
        """内部告警处理"""
        self._alert_count += 1
        now = time.time()
        
        # 防止告警风暴: 同一告警 10秒内只触发一次
        if now - self._last_alert_time < 10:
            logger.debug(f"[SoundMonitor] 告警节流 (距上次: {now - self._last_alert_time:.1f}s)")
            return
        self._last_alert_time = now

        logger.warning(f"[SoundMonitor] 告警 #{self._alert_count}: {alert_type}, {db:.1f} dB")
        
        for cb in self._alert_callbacks:
            try:
                cb(alert_type, db)
            except Exception:
                pass

    def play_pc_alert(self):
        """在 PC 扬声器播放提示音 (Windows)"""
        try:
            import winsound
            threading.Thread(
                target=lambda: winsound.Beep(880, 300),
                daemon=True
            ).start()
            logger.info("[SoundMonitor] PC 提示音已播放")
        except Exception as e:
            logger.debug(f"[SoundMonitor] PC 提示音失败: {e}")
