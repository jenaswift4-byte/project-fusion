"""
全屋音响模块
多台手机同步播放音频，组成分布式音响系统

功能:
  - 多设备同步播放 (MQTT 广播 + NTP 校时)
  - 播放列表管理 (内置音效 + 本地音频文件)
  - 音量统一控制 (全屋 / 分区)
  - 音频源切换 (TTS / 音效 / 流媒体 URL)
  - 定时播放 (闹钟 / 睡眠定时器)

实现方式:
  - 同步: MQTT 广播 fusion/audio/broadcast/command + 时间戳
  - 手机端: MQTTClientService 收到命令 → MediaPlayer 播放
  - 音效: ToneGenerator 生成 (beep/alarm/confirm/error/ring)
  - TTS: TextToSpeech 朗读
  - URL: Intent 打开流媒体 URL

注意:
  - 全屋播放会让手机发出声音！自习室/安静场合请勿使用
"""

import time
import logging
import json
import threading
from typing import Dict, List, Optional, Any
from datetime import datetime

logger = logging.getLogger(__name__)


class WholeHomeAudio:
    """全屋音响 — 多手机同步播放"""

    # 内置音效列表
    SOUND_TYPES = {
        "beep": "提示音",
        "alarm": "警报",
        "confirm": "确认",
        "ring": "铃声",
        "error": "错误",
        "doorbell": "门铃",
        "chime": "报时",
        "notification": "通知",
    }

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False

        # 配置
        cfg = daemon.config.get("whole_home_audio", {})
        self.enabled = cfg.get("enabled", True)
        self.default_volume = cfg.get("default_volume", 50)
        self.sync_tolerance_ms = cfg.get("sync_tolerance_ms", 200)

        # 播放状态
        self._current_playback: Dict[str, Any] = {}
        self._play_history: List[Dict] = []
        self._zone_volumes: Dict[str, int] = {}  # zone_name -> volume

        # 定时任务
        self._alarms: List[Dict] = []
        self._sleep_timer: Optional[float] = None  # Unix timestamp
        self._alarm_thread: Optional[threading.Thread] = None

        # 回调
        self._on_playback_callbacks: List = []

    def start(self):
        """启动全屋音响"""
        if not self.enabled:
            logger.info("[全屋音响] 已禁用")
            return

        self.running = True

        # 启动定时任务检查线程
        self._alarm_thread = threading.Thread(target=self._alarm_check_loop, daemon=True, name="alarm_check")
        self._alarm_thread.start()

        logger.info("[全屋音响] 已启动 (默认音量: %d%%)", self.default_volume)

    def stop(self):
        """停止全屋音响"""
        self.running = False
        # 停止所有播放
        self.stop_all()
        logger.info("[全屋音响] 已停止")

    # ═══════════════════════════════════════════════════════
    # 广播播放
    # ═══════════════════════════════════════════════════════

    def broadcast_sound(self, sound_type: str = "beep", volume: int = 0) -> bool:
        """
        全屋广播音效

        Args:
            sound_type: 音效类型 (beep/alarm/confirm/ring/error/doorbell/chime/notification)
            volume: 音量 0-100 (0=使用默认)

        Returns:
            是否成功
        """
        vol = volume or self.default_volume

        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            payload = {
                "action": "play",
                "type": sound_type,
                "volume": vol,
                "timestamp": time.time() * 1000,  # 毫秒时间戳用于同步
                "source": "whole_home",
            }
            self.daemon.mqtt_bridge.publish_json("fusion/audio/broadcast/command", payload, qos=1)

            self._record_playback("sound", sound_type, vol, "broadcast")
            logger.info(f"[全屋音响] 广播音效: {sound_type} (音量: {vol}%)")
            return True

        # 降级: 逐个设备发 MQTT 命令
        return self._fallback_broadcast(sound_type, vol)

    def broadcast_tts(self, text: str, volume: int = 0) -> bool:
        """
        全屋 TTS 朗读

        Args:
            text: 要朗读的文字
            volume: 音量 0-100

        Returns:
            是否成功
        """
        vol = volume or self.default_volume

        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            payload = {
                "action": "play_tts",
                "text": text,
                "volume": vol,
                "timestamp": time.time() * 1000,
                "source": "whole_home",
            }
            self.daemon.mqtt_bridge.publish_json("fusion/audio/broadcast/command", payload, qos=1)

            self._record_playback("tts", text[:30], vol, "broadcast")
            logger.info(f"[全屋音响] 广播 TTS: {text[:30]}... (音量: {vol}%)")
            return True

        return False

    def broadcast_url(self, url: str, volume: int = 0) -> bool:
        """
        全屋播放流媒体 URL

        Args:
            url: 音频/视频 URL
            volume: 音量 0-100

        Returns:
            是否成功
        """
        vol = volume or self.default_volume

        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            payload = {
                "action": "play_url",
                "url": url,
                "volume": vol,
                "timestamp": time.time() * 1000,
                "source": "whole_home",
            }
            self.daemon.mqtt_bridge.publish_json("fusion/audio/broadcast/command", payload, qos=1)

            self._record_playback("url", url[:60], vol, "broadcast")
            logger.info(f"[全屋音响] 广播 URL: {url[:60]}... (音量: {vol}%)")
            return True

        return False

    # ═══════════════════════════════════════════════════════
    # 单设备/分区播放
    # ═══════════════════════════════════════════════════════

    def play_on_device(self, device_id: str, sound_type: str = "beep", volume: int = 0) -> bool:
        """在指定设备上播放音效"""
        vol = volume or self.default_volume

        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            topic = f"fusion/audio/{device_id}/command"
            payload = {
                "action": "play",
                "type": sound_type,
                "volume": vol,
                "timestamp": time.time() * 1000,
            }
            self.daemon.mqtt_bridge.publish_json(topic, payload, qos=1)

            self._record_playback("sound", sound_type, vol, device_id)
            logger.info(f"[全屋音响] 设备播放: {device_id} / {sound_type}")
            return True

        return False

    def play_in_zone(self, zone: str, sound_type: str = "beep", volume: int = 0) -> bool:
        """
        在指定区域播放 (区域=设备组)

        Args:
            zone: 区域名 (living_room/bedroom/kitchen 等)
            sound_type: 音效类型
            volume: 音量
        """
        vol = volume or self.zone_volume(zone)

        # 通过命令桥的设备组功能
        if self.daemon.command_bridge:
            return self.daemon.command_bridge.group_command(
                zone, "play_sound",
                {"type": sound_type, "volume": vol}
            )
        return False

    # ═══════════════════════════════════════════════════════
    # 音量控制
    # ═══════════════════════════════════════════════════════

    def set_all_volume(self, level: int) -> bool:
        """设置全屋音量"""
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            payload = {
                "action": "set_volume",
                "volume": level,
                "timestamp": time.time() * 1000,
            }
            self.daemon.mqtt_bridge.publish_json("fusion/audio/broadcast/command", payload, qos=1)
            self.default_volume = level
            logger.info(f"[全屋音响] 全屋音量: {level}%")
            return True
        return False

    def set_zone_volume(self, zone: str, level: int):
        """设置区域音量"""
        self._zone_volumes[zone] = level
        logger.info(f"[全屋音响] 区域音量: {zone} → {level}%")

    def zone_volume(self, zone: str) -> int:
        """获取区域音量"""
        return self._zone_volumes.get(zone, self.default_volume)

    # ═══════════════════════════════════════════════════════
    # 播放控制
    # ═══════════════════════════════════════════════════════

    def stop_all(self) -> bool:
        """停止全屋播放"""
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            payload = {
                "action": "stop",
                "timestamp": time.time() * 1000,
            }
            self.daemon.mqtt_bridge.publish_json("fusion/audio/broadcast/command", payload, qos=1)
            self._current_playback = {}
            logger.info("[全屋音响] 全屋停止播放")
            return True
        return False

    def pause_all(self) -> bool:
        """暂停全屋播放"""
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            payload = {
                "action": "pause",
                "timestamp": time.time() * 1000,
            }
            self.daemon.mqtt_bridge.publish_json("fusion/audio/broadcast/command", payload, qos=1)
            logger.info("[全屋音响] 全屋暂停")
            return True
        return False

    def resume_all(self) -> bool:
        """恢复全屋播放"""
        if self.daemon.mqtt_bridge and self.daemon.mqtt_bridge.running:
            payload = {
                "action": "resume",
                "timestamp": time.time() * 1000,
            }
            self.daemon.mqtt_bridge.publish_json("fusion/audio/broadcast/command", payload, qos=1)
            logger.info("[全屋音响] 全屋恢复")
            return True
        return False

    # ═══════════════════════════════════════════════════════
    # 定时任务 (闹钟 / 睡眠定时器)
    # ═══════════════════════════════════════════════════════

    def set_alarm(self, time_str: str, sound_type: str = "alarm", volume: int = 80,
                  label: str = "") -> str:
        """
        设置闹钟

        Args:
            time_str: 时间 (HH:MM 格式)
            sound_type: 音效类型
            volume: 音量
            label: 闹钟标签

        Returns:
            闹钟 ID
        """
        alarm_id = f"alarm_{int(time.time())}"
        h, m = map(int, time_str.split(":"))

        alarm = {
            "id": alarm_id,
            "hour": h,
            "minute": m,
            "sound_type": sound_type,
            "volume": volume,
            "label": label or f"闹钟 {time_str}",
            "enabled": True,
            "created_at": time.time(),
        }
        self._alarms.append(alarm)
        logger.info(f"[全屋音响] 闹钟已设置: {time_str} ({label or '无标签'})")
        return alarm_id

    def remove_alarm(self, alarm_id: str):
        """删除闹钟"""
        self._alarms = [a for a in self._alarms if a["id"] != alarm_id]

    def set_sleep_timer(self, minutes: int) -> bool:
        """
        设置睡眠定时器 (分钟后自动停止播放)

        Args:
            minutes: 分钟数

        Returns:
            是否成功
        """
        self._sleep_timer = time.time() + minutes * 60
        logger.info(f"[全屋音响] 睡眠定时器: {minutes} 分钟")
        return True

    def cancel_sleep_timer(self):
        """取消睡眠定时器"""
        self._sleep_timer = None
        logger.info("[全屋音响] 睡眠定时器已取消")

    def _alarm_check_loop(self):
        """闹钟检查循环"""
        while self.running:
            try:
                now = datetime.now()
                current_h = now.hour
                current_m = now.minute

                # 检查闹钟
                for alarm in self._alarms:
                    if alarm["enabled"] and alarm["hour"] == current_h and alarm["minute"] == current_m:
                        self.broadcast_sound(alarm["sound_type"], alarm["volume"])
                        # TTS 报时
                        self.broadcast_tts(f"现在是{current_h}点{current_m}分。{alarm['label']}")
                        alarm["enabled"] = False  # 触发一次后禁用

                # 检查睡眠定时器
                if self._sleep_timer and time.time() >= self._sleep_timer:
                    self.stop_all()
                    self._sleep_timer = None
                    logger.info("[全屋音响] 睡眠定时器触发，已停止播放")

            except Exception as e:
                logger.debug(f"[全屋音响] 定时任务检查异常: {e}")

            time.sleep(30)  # 30秒检查一次

    # ═══════════════════════════════════════════════════════
    # 内部方法
    # ═══════════════════════════════════════════════════════

    def _fallback_broadcast(self, sound_type: str, volume: int) -> bool:
        """降级方案: 逐设备发送命令"""
        if not self.daemon.command_bridge:
            return False

        devices = self.daemon.command_bridge.get_devices_by_type("phone")
        for device_id in devices:
            self.daemon.command_bridge.send_command(
                device_id, "play_sound",
                {"type": sound_type, "volume": volume}
            )
        return True

    def _record_playback(self, play_type: str, content: str, volume: int, target: str):
        """记录播放历史"""
        self._current_playback = {
            "type": play_type,
            "content": content,
            "volume": volume,
            "target": target,
            "started_at": time.time(),
        }
        self._play_history.append(self._current_playback.copy())
        if len(self._play_history) > 50:
            self._play_history.pop(0)

    # ═══════════════════════════════════════════════════════
    # 状态查询
    # ═══════════════════════════════════════════════════════

    def get_status(self) -> Dict[str, Any]:
        """获取全屋音响状态"""
        return {
            "running": self.running,
            "enabled": self.enabled,
            "default_volume": self.default_volume,
            "current_playback": self._current_playback,
            "zones": dict(self._zone_volumes),
            "alarms": [
                {k: v for k, v in a.items() if k != "created_at"}
                for a in self._alarms if a["enabled"]
            ],
            "sleep_timer": (
                f"{int(self._sleep_timer - time.time()) // 60} 分钟后"
                if self._sleep_timer else None
            ),
            "sound_types": self.SOUND_TYPES,
            "history_count": len(self._play_history),
        }

    def get_history(self, limit: int = 10) -> list:
        """获取播放历史"""
        return self._play_history[-limit:]
