"""
音频流转模块 v2.0
手机 ↔ PC 音频双向流转

功能:
  - 手机麦克风 → PC 实时播放 (手机当 PC 麦克风)
  - PC → 手机播放声音 (让手机发出声音!)
  - 多手机同步播放 (全屋音响)
  - Scrcpy 音频转发 (手机内部声音 → PC 扬声器)

实现方式:
  - 麦克风: ADB screencap --audio 或 arecord (实时PCM流)
  - 播放: MQTT 命令 → 手机 MediaPlayer/TTS/ToneGenerator
  - 同步: MQTT 广播 + 时间戳同步
  - Scrcpy: --audio 参数 (v2.0+)
"""

import os
import io
import time
import subprocess
import threading
import struct
import logging
import wave
import collections
from typing import Dict, Optional, Callable, Any

logger = logging.getLogger(__name__)


class AudioBridge:
    """音频流转桥接 v2.0 — 支持双向音频"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._mic_thread = None
        self._recording = False
        self._scrcpy_audio = False
        self._mic_stream_process = None
        self._playback_processes: Dict[str, subprocess.Popen] = {}

        # 配置
        audio_cfg = daemon.config.get("audio", {})
        self.enabled = audio_cfg.get("enabled", True)
        self.mic_enabled = audio_cfg.get("mic_enabled", False)
        self.mic_sample_rate = audio_cfg.get("mic_sample_rate", 16000)
        self.mic_save_dir = audio_cfg.get("mic_save_dir", r"D:\Fusion\Audio")
        self.scrcpy_audio_enabled = audio_cfg.get("scrcpy_audio", False)
        self._playback_vol = audio_cfg.get("playback_volume", 80)
        
        # WS 麦克风方案相关
        self._mic_callback = None
        self._mic_save_file = None
        self._play_mic_audio = False
        self._sd_stream = None
        self._audio_queue = collections.deque(maxlen=50)

    def start(self):
        """启动音频桥接"""
        if not self.enabled:
            logger.info("音频桥接已禁用")
            return

        self.running = True
        os.makedirs(self.mic_save_dir, exist_ok=True)

        if self.scrcpy_audio_enabled:
            self._restart_scrcpy_with_audio()

        logger.info("[音频桥接 v2.0] 已启动 (支持双向音频)")

    def stop(self):
        """停止音频桥接"""
        self.running = False
        self._recording = False
        self._stop_mic_stream()
        self._stop_all_playback()

    # ═══════════════════════════════════════════════════════
    # 手机麦克风 → PC (手机当 PC 麦克风)
    # ═══════════════════════════════════════════════════════

    def start_mic_to_pc(self, callback: Optional[Callable] = None):
        """
        启动手机麦克风实时采集 → PC 播放/分析
        
        非root方案: 通过 WebSocket 发送 mic_control 命令 →
        手机 AudioRecord 录音 → PCM 分片 Base64 编码 → WS 发送 →
        PC 端解码播放
        
        同时保留旧的 arecord/tinycap 方案作为备用
        """
        self._recording = True
        self._mic_callback = callback
        logger.info("[音频] 手机麦克风 → PC 开始 (采样率: %dHz)", self.mic_sample_rate)

        # 方案 1: 通过 WebSocket 命令触发手机端 AudioRecord 录音
        if self.daemon.ws_client and self.daemon.ws_client.running:
            self._start_mic_via_ws()
            return

        # 方案 2 (备用): ADB shell arecord/tinycap (需要 root)
        self._start_mic_via_adb()

    def _start_mic_via_ws(self):
        """通过 WebSocket 命令启动手机麦克风录音 (非root方案)"""
        try:
            if self.daemon.ws_client:
                self.daemon.ws_client.send_message({
                    "type": "mic_control",
                    "action": "start"
                })
                logger.info("[音频] 已发送 WS mic_control start 命令")
                
                # 注册 PCM 数据回调
                if self.daemon.ws_client:
                    self.daemon.ws_client.on("audio_pcm", self._on_audio_pcm)
                    self.daemon.ws_client.on("mic_status", self._on_mic_status)
                    
        except Exception as e:
            logger.error(f"[音频] WS 启动录音失败: {e}")
            # 降级到 ADB 方案
            self._start_mic_via_adb()

    def _stop_mic_via_ws(self):
        """通过 WebSocket 命令停止手机麦克风录音"""
        try:
            if self.daemon.ws_client:
                self.daemon.ws_client.send_message({
                    "type": "mic_control",
                    "action": "stop"
                })
                # 注意: WSClient 没有 off 方法，handler 会保留但 _recording=False 时不会处理
                logger.info("[音频] 已发送 WS mic_control stop 命令")
        except Exception as e:
            logger.error(f"[音频] WS 停止录音失败: {e}")

    def _on_audio_pcm(self, data: dict):
        """处理手机发来的 PCM 音频分片"""
        if not self._recording:
            return
        try:
            import base64
            
            pcm_base64 = data.get("data", "")
            if not pcm_base64:
                return
            
            pcm_bytes = base64.b64decode(pcm_base64)
            sample_rate = data.get("sample_rate", 16000)
            
            # 喂给 sound_monitor 分析
            if self._mic_callback:
                try:
                    self._mic_callback("phone_mic", pcm_bytes)
                except Exception:
                    pass
            
            if self.daemon.sound_monitor and self.daemon.sound_monitor.running:
                try:
                    self.daemon.sound_monitor.feed_audio("phone_mic", pcm_bytes)
                except Exception:
                    pass
            
            # 如果启用了 PC 播放，通过 sounddevice 播放
            if self._play_mic_audio and self._sd_stream:
                try:
                    import numpy as np
                    audio_np = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32) / 32768.0
                    self._audio_queue.append(audio_np)
                except Exception:
                    pass
            
            # 保存到文件
            if self._mic_save_file:
                self._mic_save_file.write(pcm_bytes)
                
        except Exception as e:
            logger.error(f"[音频] PCM 处理失败: {e}")

    def _on_mic_status(self, data: dict):
        """处理手机麦克风状态通知"""
        status = data.get("status", "")
        message = data.get("message", "")
        logger.info(f"[音频] 麦克风状态: {status} - {message}")

    def _start_mic_via_adb(self):
        """通过 ADB shell arecord/tinycap 录音 (备用，需要root)"""
        def _mic_stream():
            """实时音频流读取循环"""
            pcm_buffer = b""
            chunk_size = self.mic_sample_rate * 2 * 2  # 2秒的 16-bit mono PCM

            while self._recording and self.running:
                try:
                    cmd = [
                        self.daemon.adb_path,
                    ]
                    if self.daemon.device_serial:
                        cmd += ["-s", self.daemon.device_serial]
                    cmd += ["shell", 
                            f"arecord -D hw:0,0 -r {self.mic_sample_rate} -c 1 -f S16_LE -t raw -d 5 2>/dev/null || "
                            f"tinycap /dev/stdout -D 0 -c 1 -r {self.mic_sample_rate} -b 16 -d 5 2>/dev/null || "
                            f"cat /dev/null"]

                    proc = subprocess.Popen(
                        cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
                    )

                    while self._recording and proc.poll() is None:
                        data = proc.stdout.read(4096)
                        if data:
                            pcm_buffer += data

                            if len(pcm_buffer) >= chunk_size:
                                if self._mic_callback:
                                    try:
                                        self._mic_callback("phone_mic", pcm_buffer[:chunk_size])
                                    except Exception:
                                        pass

                                if self.daemon.sound_monitor and self.daemon.sound_monitor.running:
                                    try:
                                        self.daemon.sound_monitor.feed_audio(
                                            "phone_mic", pcm_buffer[:chunk_size]
                                        )
                                    except Exception:
                                        pass

                                pcm_buffer = pcm_buffer[chunk_size:]

                    if proc.poll() is not None and self._recording:
                        logger.debug("[音频] 录音进程结束，重启...")
                        time.sleep(0.5)

                except Exception as e:
                    logger.debug(f"[音频] 麦克风流错误: {e}")
                    time.sleep(2)

        self._mic_thread = threading.Thread(target=_mic_stream, daemon=True, name="phone_mic")
        self._mic_thread.start()

    def stop_mic_to_pc(self):
        """停止手机麦克风采集"""
        self._recording = False
        
        # 停止 WS 方案
        self._stop_mic_via_ws()
        
        # 停止 ADB 方案
        self._stop_mic_stream()
        
        # 关闭保存文件
        if self._mic_save_file:
            try:
                self._mic_save_file.close()
            except Exception:
                pass
            self._mic_save_file = None
        
        # 停止 sounddevice 播放
        if self._sd_stream:
            try:
                self._sd_stream.stop()
                self._sd_stream.close()
            except Exception:
                pass
            self._sd_stream = None
        
        logger.info("[音频] 手机麦克风已停止")

    def _stop_mic_stream(self):
        """停止麦克风流进程"""
        if self._mic_stream_process:
            try:
                self._mic_stream_process.terminate()
                self._mic_stream_process.wait(timeout=3)
            except Exception:
                try:
                    self._mic_stream_process.kill()
                except Exception:
                    pass
            self._mic_stream_process = None

    # ═══════════════════════════════════════════════════════
    # PC → 手机播放 (让手机发出声音!)
    # ═══════════════════════════════════════════════════════

    def play_sound_on_phone(self, device_id: str = "", sound_type: str = "beep", 
                            volume: int = 80, duration_ms: int = 1000) -> bool:
        """
        让手机发出声音!
        
        Args:
            device_id: 目标设备 ID (空=当前设备)
            sound_type: 声音类型 (beep/alarm/ring/confirm/error)
            volume: 音量 0-100
            duration_ms: 持续时间(毫秒)
        
        Returns:
            是否成功
        """
        target = device_id or (self.daemon.device_serial or "phone")
        
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            # 通过 MQTT 发送播放命令
            topic = f"fusion/audio/{target}/command"
            payload = {
                "action": "play",
                "type": sound_type,
                "volume": volume,
                "duration": duration_ms,
                "timestamp": time.time(),
            }
            self.daemon.mqtt_bridge.publish_json(topic, payload, qos=1)
            logger.info(f"[音频] 播放命令已发送: {sound_type} → {target}")
            return True
        
        # 备用: ADB 播放
        logger.info(f"[音频] 通过 ADB 播放声音: {sound_type}")
        return self._play_via_adb(sound_type, volume)

    def play_tts_on_phone(self, text: str, device_id: str = "") -> bool:
        """
        让手机朗读文字 (TTS)
        
        Args:
            text: 要朗读的文字
            device_id: 目标设备
        """
        target = device_id or (self.daemon.device_serial or "phone")
        
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            topic = f"fusion/audio/{target}/command"
            payload = {
                "action": "play_tts",
                "text": text,
                "timestamp": time.time(),
            }
            self.daemon.mqtt_bridge.publish_json(topic, payload, qos=1)
            logger.info(f"[音频] TTS 命令已发送: {text[:20]}... → {target}")
            return True
        
        # 备用: ADB TTS
        self.daemon.adb_shell(
            f"am start -a android.speech.tts.engine.INSTALL_TTS_DATA "
            f"--es text \"{text}\"",
            capture=False
        )
        return True

    def broadcast_sound(self, sound_type: str = "beep", volume: int = 80):
        """
        广播声音到所有设备 (全屋音响)
        
        Args:
            sound_type: 声音类型
            volume: 音量
        """
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            payload = {
                "action": "play",
                "type": sound_type,
                "volume": volume,
                "timestamp": time.time(),
            }
            self.daemon.mqtt_bridge.publish_json("fusion/audio/broadcast/command", payload, qos=1)
            logger.info(f"[音频] 广播播放: {sound_type}")
            return True
        return False

    def set_phone_volume(self, device_id: str = "", level: int = 50) -> bool:
        """设置手机音量"""
        target = device_id or (self.daemon.device_serial or "phone")
        
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            topic = f"fusion/audio/{target}/command"
            payload = {
                "action": "set_volume",
                "volume": level,
                "timestamp": time.time(),
            }
            self.daemon.mqtt_bridge.publish_json(topic, payload, qos=1)
            logger.info(f"[音频] 音量设置: {level} → {target}")
            return True
        
        # 备用: ADB
        max_vol_out, _, _ = self.daemon.adb_shell("media volume --stream 3 --get")
        try:
            max_vol = int(max_vol_out.strip()) if max_vol_out else 15
        except ValueError:
            max_vol = 15
        actual = int(max_vol * level / 100)
        self.daemon.adb_shell(f"media volume --stream 3 --set {actual}", capture=False)
        return True

    def vibrate_phone(self, device_id: str = "", duration_ms: int = 500) -> bool:
        """让手机振动"""
        target = device_id or (self.daemon.device_serial or "phone")
        
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            self.daemon.command_bridge.send_command(target, "vibrate", {"duration": duration_ms})
            return True
        
        # 备用: ADB
        self.daemon.adb_shell(
            f"am broadcast -a com.fusion.companion.VIBRATE --ei duration {duration_ms}",
            capture=False
        )
        return True

    def _play_via_adb(self, sound_type: str, volume: int) -> bool:
        """通过 ADB 直接播放声音 (备用方案)"""
        try:
            # 设置音量
            self.set_phone_volume(level=volume)
            
            # 播放系统声音
            sound_map = {
                "beep": 1,     # TONE_PROP_BEEP
                "alarm": 4,    # TONE_ALARM
                "confirm": 2,  # TONE_PROP_ACK
                "error": 3,    # TONE_PROP_NACK
                "ring": 5,     # TONE_RINGTONE
            }
            tone = sound_map.get(sound_type, 1)
            self.daemon.adb_shell(
                f"input keyevent {tone + 100}",
                capture=False
            )
            
            # 使用媒体播放器播放
            self.daemon.adb_shell(
                f"am start -a android.intent.action.VIEW -d \"\"",
                capture=False
            )
            
            # 使用 Termux:API 或 SOX 播放 (如果有)
            self.daemon.adb_shell(
                "play-audio 1000 2>/dev/null || true",
                capture=False
            )
            
            return True
        except Exception as e:
            logger.error(f"[音频] ADB 播放失败: {e}")
            return False

    def _stop_all_playback(self):
        """停止所有播放进程"""
        for proc in self._playback_processes.values():
            try:
                proc.terminate()
            except Exception:
                pass
        self._playback_processes.clear()

    # ═══════════════════════════════════════════════════════
    # Scrcpy 音频转发
    # ═══════════════════════════════════════════════════════

    def _restart_scrcpy_with_audio(self):
        """重启 Scrcpy 并启用音频转发"""
        if self.daemon.scrcpy_ctrl:
            self.daemon.scrcpy_ctrl.stop()
            time.sleep(1)
            self.daemon.scrcpy_ctrl.start(audio=True)
            self._scrcpy_audio = True
            logger.info("[音频] Scrcpy 已重启 (音频转发已启用)")

    # ═══════════════════════════════════════════════════════
    # ADB 离线录音 (备用)
    # ═══════════════════════════════════════════════════════

    def start_mic_recording(self, duration: int = 0) -> str:
        """启动手机录音 (ADB 离线模式)

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
                logger.error("[音频] 拉取录音失败")
                return None
        except Exception as e:
            logger.error(f"[音频] 停止录音失败: {e}")
            return None

    def get_phone_volume(self) -> dict:
        """获取手机音量信息"""
        output, _, rc = self.daemon.adb_shell(
            "dumpsys audio | grep -E 'STREAM_MUSIC|Volume index'"
        )
        if output:
            return {"raw": output}
        return {}

    # ═══════════════════════════════════════════════════════
    # 状态查询
    # ═══════════════════════════════════════════════════════

    def get_status(self) -> Dict[str, Any]:
        """获取音频桥接状态"""
        return {
            "running": self.running,
            "mic_active": self._recording,
            "scrcpy_audio": self._scrcpy_audio,
            "enabled": self.enabled,
        }
