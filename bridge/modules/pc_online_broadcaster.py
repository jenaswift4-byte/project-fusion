"""
PC 在线状态广播模块
通过 MQTT 定期发布 PC 在线状态，让手机端能感知 PC 上下线

配合 Android 端 PCOnlineDetector 使用:
  - PC 端定期发布心跳到 fusion/pc/status
  - 手机端订阅该主题，检测 PC 在线/离线
  - PC 上下线时自动触发模式切换

Topic:
  fusion/pc/status — PC 状态 (JSON: {online, ip, hostname, uptime, battery})
  fusion/pc/mode  — PC 模式 (online/offline/away)
"""

import json
import time
import socket
import logging
import threading
from typing import Optional, Callable, List, Dict, Any

logger = logging.getLogger(__name__)


class PCOnlineBroadcaster:
    """
    PC 在线状态广播器

    集成到 main.py:
        broadcaster = PCOnlineBroadcaster(mqtt_bridge)
        broadcaster.start()
    """

    def __init__(self, mqtt_bridge=None, config: dict = None):
        self.mqtt_bridge = mqtt_bridge
        self.config = config or {}

        self.running = False
        self._thread: Optional[threading.Thread] = None
        self._start_time = time.time()

        # 配置
        self.heartbeat_interval = self.config.get("heartbeat_interval", 15)  # 秒
        self.pc_name = self.config.get("pc_name", socket.gethostname())

        # 状态
        self.is_online = False
        self.current_mode = "online"  # online/away/offline

        # 回调
        self._on_mode_change_callbacks: List[Callable[[str, str], None]] = []

    def set_mqtt_bridge(self, mqtt_bridge):
        """设置 MQTT Bridge"""
        self.mqtt_bridge = mqtt_bridge

    def start(self) -> bool:
        """启动心跳广播"""
        if self.running:
            return True

        self.running = True
        self.is_online = True

        self._thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self._thread.start()

        # 立即发布一次在线状态
        self._publish_status()
        self._publish_mode("online")

        logger.info(f"[PCOnline] 已启动 - 心跳间隔: {self.heartbeat_interval}s")
        return True

    def stop(self):
        """停止心跳广播"""
        if not self.running:
            return

        # 发布离线状态
        self._publish_mode("offline")
        self.running = False
        self.is_online = False

        logger.info("[PCOnline] 已停止")

    def set_mode(self, mode: str):
        """设置 PC 模式"""
        if mode not in ("online", "away", "offline"):
            logger.warning(f"[PCOnline] 无效模式: {mode}")
            return

        old_mode = self.current_mode
        self.current_mode = mode
        self._publish_mode(mode)

        if old_mode != mode:
            logger.info(f"[PCOnline] 模式切换: {old_mode} -> {mode}")
            for cb in self._on_mode_change_callbacks:
                try:
                    cb(old_mode, mode)
                except Exception:
                    pass

    def on_mode_change(self, callback: Callable[[str, str], None]):
        """注册模式切换回调: callback(old_mode, new_mode)"""
        self._on_mode_change_callbacks.append(callback)

    def get_status(self) -> Dict[str, Any]:
        """获取当前状态"""
        return {
            "online": self.is_online,
            "mode": self.current_mode,
            "pc_name": self.pc_name,
            "uptime": int(time.time() - self._start_time),
            "heartbeat_interval": self.heartbeat_interval,
        }

    # ═══════════════════════════════════════════════════════
    # 内部实现
    # ═══════════════════════════════════════════════════════

    def _heartbeat_loop(self):
        """心跳广播循环"""
        while self.running:
            try:
                self._publish_status()
            except Exception as e:
                logger.debug(f"[PCOnline] 心跳发布失败: {e}")

            # 等待下次心跳
            for _ in range(self.heartbeat_interval):
                if not self.running:
                    break
                time.sleep(1)

    def _publish_status(self):
        """发布 PC 状态"""
        if not self.mqtt_bridge:
            return

        # 获取本机 IP
        local_ip = "127.0.0.1"
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
        except Exception:
            pass

        payload = {
            "online": True,
            "mode": self.current_mode,
            "hostname": self.pc_name,
            "ip": local_ip,
            "uptime": int(time.time() - self._start_time),
            "timestamp": int(time.time() * 1000),
        }

        self.mqtt_bridge.publish_json("fusion/pc/status", payload, qos=1)

    def _publish_mode(self, mode: str):
        """发布模式变更"""
        if not self.mqtt_bridge:
            return

        payload = {
            "mode": mode,
            "previous_mode": self.current_mode,
            "hostname": self.pc_name,
            "timestamp": int(time.time() * 1000),
        }

        self.mqtt_bridge.publish_json("fusion/pc/mode", payload, qos=1)

        # 同时更新全局 MQTT mode topic
        self.mqtt_bridge.set_mode(mode)
