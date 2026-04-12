"""
音频流转模块
手机音频资源 → PC 可用

手机作为 PC 外设扩展板:
  - 手机麦克风 → PC 麦克风输入 (手机当 PC 麦克风)
  - 手机扬声器 ← PC 音频输出 (可选: PC 音频推到手机播放)
  - Scrcpy 音频转发 (手机声音在 PC 扬声器播放)

实现方式:
  - 麦克风: ADB 录音 → pull → PC 播放 (延迟较高)
  - 更优: WebSocket 实时音频流 (需要 APK 支持)
  - Scrcpy: 已有 --audio 参数 (v2.0+)
  - WO Mic: 第三方方案参考
"""

import os
import time
import subprocess
import threading
import logging

logger = logging.getLogger(__name__)


class AudioBridge:
    """音频流转桥接"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._mic_thread = None
        self._recording = False
        self._scrcpy_audio = False

        # 配置
        audio_cfg = daemon.config.get("audio", {})
        self.enabled = audio_cfg.get("enabled", True)
        self.mic_enabled = audio_cfg.get("mic_enabled", False)
        self.mic_sample_rate = audio_cfg.get("mic_sample_rate", 44100)
        self.mic_save_dir = audio_cfg.get("mic_save_dir", r"D:\Fusion\Audio")
        self.scrcpy_audio_enabled = audio_cfg.get("scrcpy_audio", False)

    def start(self):
        """启动音频桥接"""
        if not self.enabled:
            logger.info("音频桥接已禁用")
            return

        self.running = True

        if self.scrcpy_audio_enabled:
            self._restart_scrcpy_with_audio()

        if self.mic_enabled:
            logger.info("手机麦克风转发已启动 (ADB 录音模式)")
            self._mic_thread = threading.Thread(target=self._mic_loop, daemon=True)
            self._mic_thread.start()
        else:
            logger.info("音频桥接已启动 (手机麦克风默认关闭，可通过热键启用)")

    def stop(self):
        """停止音频桥接"""
        self.running = False
        self._recording = False

    def start_mic_recording(self, duration: int = 0) -> str:
        """启动手机录音

        Args:
            duration: 录音时长(秒)，0 = 持续直到 stop

        Returns:
            手机端录音文件路径
        """
        phone_path = "/sdcard/Music/fusion_mic.wav"
        self._recording = True
        logger.info("[音频] 开始手机录音 (通过 ADB)")
        return phone_path

    def stop_mic_recording(self) -> str:
        """停止录音并拉取到 PC"""
        self._recording = False
        phone_path = "/sdcard/Music/fusion_mic.wav"
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        local_path = os.path.join(self.mic_save_dir, f"mic_{timestamp}.wav")
        os.makedirs(self.mic_save_dir, exist_ok=True)

        try:
            device_arg = ["-s", self.daemon.device_serial] if self.daemon.device_serial else []
            result = subprocess.run(
                [self.daemon.adb_path] + device_arg + ["pull", phone_path, local_path],
                capture_output=True, text=True, timeout=30,
            )
            if result.returncode == 0 and os.path.exists(local_path):
                self.daemon.adb_shell(f"rm {phone_path}", capture=False)
                logger.info(f"[音频] 录音已保存: {local_path}")
                return local_path
            else:
                logger.error(f"[音频] 拉取录音失败")
                return None
        except Exception as e:
            logger.error(f"[音频] 停止录音失败: {e}")
            return None

    def _restart_scrcpy_with_audio(self):
        """重启 Scrcpy 并启用音频转发"""
        if self.daemon.scrcpy_ctrl:
            self.daemon.scrcpy_ctrl.stop()
            time.sleep(1)
            config = self.daemon.config.copy()
            config["scrcpy"]["audio"] = True
            self.daemon.scrcpy_ctrl.start()
            self._scrcpy_audio = True
            logger.info("[音频] Scrcpy 已重启 (音频转发已启用)")

    def _mic_loop(self):
        """麦克风录音循环 (ADB 方案 - 延迟较高)"""
        while self.running and self.mic_enabled:
            try:
                phone_path = "/sdcard/Music/fusion_mic_tmp.mp4"
                self.daemon.adb_shell(
                    f"screenrecord --time-limit 5 {phone_path}",
                    capture=True,
                )
                if not self.running:
                    break

                timestamp = time.strftime("%Y%m%d_%H%M%S_%f")
                local_path = os.path.join(self.mic_save_dir, f"mic_{timestamp}.mp4")
                os.makedirs(self.mic_save_dir, exist_ok=True)

                device_arg = ["-s", self.daemon.device_serial] if self.daemon.device_serial else []
                subprocess.run(
                    [self.daemon.adb_path] + device_arg + ["pull", phone_path, local_path],
                    capture_output=True, timeout=15,
                )
                self.daemon.adb_shell(f"rm {phone_path}", capture=False)

            except Exception as e:
                logger.debug(f"[音频] 麦克风循环错误: {e}")
                time.sleep(2)

    def get_phone_volume(self) -> dict:
        """获取手机音量信息"""
        output, _, rc = self.daemon.adb_shell(
            "dumpsys audio | grep -E 'STREAM_MUSIC|Volume index'"
        )
        if output:
            return {"raw": output}
        return {}
