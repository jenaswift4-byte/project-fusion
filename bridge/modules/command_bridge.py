"""
MQTT 命令通道 + 智能家居 MCU 接口

功能:
  - 通过 MQTT 发送控制命令到手机/设备
  - 设备注册与管理
  - 命令队列与状态跟踪
  - MCU 串口通信接口预留
  - 场景联动引擎

Topic 设计:
  命令:
    fusion/cmd/{targetId}              — 单设备命令
    fusion/cmd/{targetId}/response     — 命令响应
    fusion/cmd/group/{groupName}       — 设备组命令
    fusion/cmd/broadcast               — 广播命令
  
  设备:
    fusion/devices/{deviceId}/state    — 设备状态上报
    fusion/devices/registry            — 设备注册表
  
  场景:
    fusion/scene/activate/{sceneName}  — 激活场景
    fusion/scene/deactivate/{sceneName}— 停用场景
"""

import json
import time
import logging
import threading
import uuid
from typing import Dict, List, Optional, Callable, Any
from dataclasses import dataclass, field
from enum import Enum

logger = logging.getLogger(__name__)


class CommandStatus(Enum):
    PENDING = "pending"
    SENT = "sent"
    DELIVERED = "delivered"
    ACKNOWLEDGED = "acknowledged"
    COMPLETED = "completed"
    TIMEOUT = "timeout"
    FAILED = "failed"


@dataclass
class Command:
    """控制命令"""
    id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    target: str = ""                # 目标设备/组 ID
    action: str = ""                # 动作名称
    params: Dict[str, Any] = field(default_factory=dict)
    source: str = "pc"              # 命令来源
    status: CommandStatus = CommandStatus.PENDING
    created_at: float = field(default_factory=time.time)
    sent_at: Optional[float] = None
    responded_at: Optional[float] = None
    response: Optional[Dict] = None
    timeout_seconds: int = 30

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "target": self.target,
            "action": self.action,
            "params": self.params,
            "source": self.source,
            "status": self.status.value,
            "created_at": self.created_at,
            "sent_at": self.sent_at,
            "responded_at": self.responded_at,
            "response": self.response,
        }

    @staticmethod
    def from_dict(data: Dict) -> "Command":
        cmd = Command()
        cmd.id = data.get("id", cmd.id)
        cmd.target = data.get("target", "")
        cmd.action = data.get("action", "")
        cmd.params = data.get("params", {})
        cmd.source = data.get("source", "pc")
        cmd.status = CommandStatus(data.get("status", "pending"))
        cmd.created_at = data.get("created_at", time.time())
        cmd.sent_at = data.get("sent_at")
        cmd.responded_at = data.get("responded_at")
        cmd.response = data.get("response")
        cmd.timeout_seconds = data.get("timeout_seconds", 30)
        return cmd


@dataclass
class Scene:
    """场景定义"""
    name: str
    description: str = ""
    actions: List[Dict[str, Any]] = field(default_factory=list)
    conditions: List[Dict[str, Any]] = field(default_factory=list)
    enabled: bool = True
    last_triggered: Optional[float] = None


@dataclass
class DeviceRegistration:
    """设备注册信息"""
    device_id: str
    device_name: str = ""
    device_type: str = "unknown"  # phone/tablet/mcu/sensor
    capabilities: List[str] = field(default_factory=list)
    state: Dict[str, Any] = field(default_factory=dict)
    registered_at: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    online: bool = True


class CommandBridge:
    """
    MQTT 命令通道 + 智能家居接口
    
    集成到 main.py 使用:
        cmd_bridge = CommandBridge(daemon)
        cmd_bridge.set_mqtt_bridge(mqtt_bridge)
        cmd_bridge.start()
    """

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False

        # MQTT Bridge 引用 (后续通过 set_mqtt_bridge 设置)
        self._mqtt_bridge = None

        # 命令管理
        self._commands: Dict[str, Command] = {}  # cmd_id -> Command
        self._pending_commands: Dict[str, Command] = {}  # target -> Command
        self._command_lock = threading.Lock()
        self._command_timeout_thread: Optional[threading.Thread] = None

        # 设备注册表
        self._devices: Dict[str, DeviceRegistration] = {}

        # 场景
        self._scenes: Dict[str, Scene] = {}
        self._setup_default_scenes()

        # 回调
        self._on_command_response: List[Callable] = []
        self._on_device_state_change: List[Callable] = []
        self._on_scene_triggered: List[Callable] = []

    def set_mqtt_bridge(self, mqtt_bridge):
        """设置 MQTT Bridge 引用"""
        self._mqtt_bridge = mqtt_bridge

    def start(self) -> bool:
        """启动命令通道"""
        self.running = True

        # 启动命令超时检查
        self._command_timeout_thread = threading.Thread(target=self._timeout_check_loop, daemon=True)
        self._command_timeout_thread.start()

        logger.info("[CmdBridge] 已启动")
        return True

    def stop(self):
        """停止命令通道"""
        self.running = False
        logger.info("[CmdBridge] 已停止")

    # ═══════════════════════════════════════════════════════
    # 命令发送
    # ═══════════════════════════════════════════════════════

    def send_command(self, target: str, action: str, params: Dict = None,
                     timeout: int = 30, wait_response: bool = True) -> Command:
        """
        发送命令到目标设备

        Args:
            target: 目标设备 ID (支持 group/xxx 设备组)
            action: 动作名称 (如 "turn_on", "set_value", "capture")
            params: 动作参数
            timeout: 超时时间 (秒)
            wait_response: 是否等待响应
        """
        # 设备组展开
        if target.startswith("group/"):
            group_name = target.split("/", 1)[1]
            devices = self.get_group_devices(group_name)
            for device_id in devices:
                self._send_single_command(device_id, action, params, timeout, wait_response)
            return Command(target=target, action=action, params=params or {},
                          status=CommandStatus.SENT, sent_at=time.time())
        
        return self._send_single_command(target, action, params, timeout, wait_response)
    
    def _send_single_command(self, target: str, action: str, params: Dict,
                             timeout: int, wait_response: bool) -> Command:
        """发送单设备命令"""
        cmd = Command(
            target=target,
            action=action,
            params=params or {},
            timeout_seconds=timeout,
        )

        with self._command_lock:
            self._commands[cmd.id] = cmd
            if wait_response:
                self._pending_commands[target] = cmd

        # 通过 MQTT 发送
        topic = f"fusion/cmd/{target}"
        payload = cmd.to_dict()
        cmd.status = CommandStatus.SENT
        cmd.sent_at = time.time()

        if self._mqtt_bridge:
            self._mqtt_bridge.publish_json(topic, payload, qos=1)
            logger.info(f"[CmdBridge] 命令已发送: {target}/{action} (id={cmd.id})")
        else:
            logger.warning(f"[CmdBridge] MQTT Bridge 未设置，命令仅在本地记录")
            cmd.status = CommandStatus.FAILED

        return cmd

    def broadcast_command(self, action: str, params: Dict = None):
        """广播命令到所有在线设备"""
        payload = {
            "action": action,
            "params": params or {},
            "source": "pc",
            "timestamp": int(time.time() * 1000),
        }
        if self._mqtt_bridge:
            self._mqtt_bridge.publish_json("fusion/cmd/broadcast", payload)
            logger.info(f"[CmdBridge] 广播命令: {action}")

    def group_command(self, group_name: str, action: str, params: Dict = None):
        """发送命令到设备组"""
        devices = self.get_group_devices(group_name)
        for device_id in devices:
            self.send_command(device_id, action, params)

    # ═══════════════════════════════════════════════════════
    # 命令响应处理
    # ═══════════════════════════════════════════════════════

    def handle_response(self, cmd_id: str, response: Dict):
        """处理命令响应"""
        with self._command_lock:
            cmd = self._commands.get(cmd_id)
            if not cmd:
                logger.warning(f"[CmdBridge] 未知命令响应: {cmd_id}")
                return

            cmd.status = CommandStatus.ACKNOWLEDGED
            cmd.responded_at = time.time()
            cmd.response = response
            self._pending_commands.pop(cmd.target, None)

        logger.info(f"[CmdBridge] 命令响应: {cmd_id} -> {response}")

        for cb in self._on_command_response:
            try:
                cb(cmd)
            except Exception:
                pass

    # ═══════════════════════════════════════════════════════
    # 设备管理
    # ═══════════════════════════════════════════════════════

    def register_device(self, device_id: str, name: str = "", device_type: str = "unknown",
                       capabilities: List[str] = None):
        """注册设备"""
        device = DeviceRegistration(
            device_id=device_id,
            device_name=name or device_id,
            device_type=device_type,
            capabilities=capabilities or [],
        )
        self._devices[device_id] = device
        logger.info(f"[CmdBridge] 设备注册: {device_id} ({device_type})")

        # 广播注册信息
        if self._mqtt_bridge:
            self._mqtt_bridge.publish_json("fusion/devices/registry", {
                "action": "registered",
                "device_id": device_id,
                "device_name": device.device_name,
                "device_type": device.device_type,
                "capabilities": device.capabilities,
            })

    def unregister_device(self, device_id: str):
        """注销设备"""
        self._devices.pop(device_id, None)
        logger.info(f"[CmdBridge] 设备注销: {device_id}")

    def update_device_state(self, device_id: str, state: Dict):
        """更新设备状态"""
        device = self._devices.get(device_id)
        if device:
            device.state.update(state)
            device.last_seen = time.time()
            for cb in self._on_device_state_change:
                try:
                    cb(device_id, state)
                except Exception:
                    pass

    def get_device(self, device_id: str) -> Optional[Dict]:
        """获取设备信息"""
        device = self._devices.get(device_id)
        if device:
            return {
                "device_id": device.device_id,
                "device_name": device.device_name,
                "device_type": device.device_type,
                "capabilities": device.capabilities,
                "state": device.state,
                "online": device.online,
                "last_seen": device.last_seen,
            }
        return None

    def get_all_devices(self) -> Dict[str, Dict]:
        """获取所有设备"""
        return {did: self.get_device(did) for did in self._devices}

    def get_devices_by_type(self, device_type: str) -> List[str]:
        """按类型获取设备 ID 列表"""
        return [did for did, d in self._devices.items() if d.device_type == device_type]

    def get_group_devices(self, group_name: str) -> List[str]:
        """获取设备组"""
        # 内置组
        if group_name == "all":
            return list(self._devices.keys())
        elif group_name == "phones":
            return self.get_devices_by_type("phone")
        elif group_name == "cameras":
            return self.get_devices_by_type("camera")
        elif group_name == "sensors":
            return self.get_devices_by_type("sensor")
        elif group_name == "mcu":
            return self.get_devices_by_type("mcu")
        return []

    # ═══════════════════════════════════════════════════════
    # 场景管理
    # ═══════════════════════════════════════════════════════

    def _setup_default_scenes(self):
        """设置默认场景"""
        self._scenes["all_lights_off"] = Scene(
            name="all_lights_off",
            description="关闭所有灯光",
            actions=[
                {"target": "group/mcu", "action": "all_lights_off"},
            ],
        )
        self._scenes["night_mode"] = Scene(
            name="night_mode",
            description="夜间模式",
            actions=[
                {"target": "group/mcu", "action": "dim_lights", "params": {"level": 20}},
                {"target": "group/phones", "action": "set_mode", "params": {"mode": "silent"}},
            ],
            conditions=[
                {"type": "time", "after": "22:00", "before": "06:00"},
            ],
        )
        self._scenes["away_mode"] = Scene(
            name="away_mode",
            description="离家模式",
            actions=[
                {"target": "group/cameras", "action": "start_recording"},
                {"target": "group/phones", "action": "arm_sensors"},
                {"target": "group/mcu", "action": "simulate_presence"},
            ],
        )
        self._scenes["movie_mode"] = Scene(
            name="movie_mode",
            description="影院模式",
            actions=[
                {"target": "group/phones", "action": "set_volume", "params": {"level": 10}},
                {"target": "group/mcu", "action": "dim_lights", "params": {"level": 5}},
            ],
        )
        self._scenes["party_mode"] = Scene(
            name="party_mode",
            description="派对模式",
            actions=[
                {"target": "group/phones", "action": "play_sound", "params": {"type": "ring"}},
                {"target": "group/mcu", "action": "color_cycle"},
            ],
        )
        self._scenes["alert_mode"] = Scene(
            name="alert_mode",
            description="警报模式",
            actions=[
                {"target": "group/phones", "action": "vibrate", "params": {"duration": 1000}},
                {"target": "group/phones", "action": "play_sound", "params": {"type": "alarm"}},
            ],
            conditions=[
                {"type": "sensor", "sensor_type": "sound", "above_db": 80},
            ],
        )

    def activate_scene(self, scene_name: str) -> bool:
        """激活场景"""
        scene = self._scenes.get(scene_name)
        if not scene:
            logger.warning(f"[CmdBridge] 场景不存在: {scene_name}")
            return False

        if not scene.enabled:
            logger.info(f"[CmdBridge] 场景已禁用: {scene_name}")
            return False

        logger.info(f"[CmdBridge] 激活场景: {scene_name}")
        scene.last_triggered = time.time()

        for action in scene.actions:
            target = action.get("target", "")
            act = action.get("action", "")
            params = action.get("params", {})
            self.send_command(target, act, params)

        # 通知
        for cb in self._on_scene_triggered:
            try:
                cb(scene_name, scene)
            except Exception:
                pass

        return True

    def create_scene(self, name: str, description: str = "", actions: List[Dict] = None):
        """创建自定义场景"""
        scene = Scene(name=name, description=description, actions=actions or [])
        self._scenes[name] = scene
        logger.info(f"[CmdBridge] 创建场景: {name}")

    def get_scenes(self) -> Dict[str, Dict]:
        """获取所有场景"""
        return {
            name: {
                "name": scene.name,
                "description": scene.description,
                "enabled": scene.enabled,
                "last_triggered": scene.last_triggered,
                "action_count": len(scene.actions),
            }
            for name, scene in self._scenes.items()
        }

    # ═══════════════════════════════════════════════════════
    # 回调注册
    # ═══════════════════════════════════════════════════════

    def on_command_response(self, callback: Callable[[Command], None]):
        """命令响应回调"""
        self._on_command_response.append(callback)

    def on_device_state_change(self, callback: Callable[[str, Dict], None]):
        """设备状态变化回调"""
        self._on_device_state_change.append(callback)

    def on_scene_triggered(self, callback: Callable[[str, Scene], None]):
        """场景触发回调"""
        self._on_scene_triggered.append(callback)

    # ═══════════════════════════════════════════════════════
    # 内部实现
    # ═══════════════════════════════════════════════════════

    def _timeout_check_loop(self):
        """命令超时检查循环"""
        while self.running:
            with self._command_lock:
                now = time.time()
                for cmd_id, cmd in list(self._commands.items()):
                    if cmd.status in (CommandStatus.SENT, CommandStatus.PENDING):
                        if cmd.sent_at and now - cmd.sent_at > cmd.timeout_seconds:
                            cmd.status = CommandStatus.TIMEOUT
                            logger.warning(f"[CmdBridge] 命令超时: {cmd_id} ({cmd.target}/{cmd.action})")
                            self._pending_commands.pop(cmd.target, None)

            time.sleep(5)

    def get_status(self) -> Dict[str, Any]:
        """获取命令通道状态"""
        with self._command_lock:
            pending = sum(1 for c in self._commands.values() if c.status in (CommandStatus.PENDING, CommandStatus.SENT))
            completed = sum(1 for c in self._commands.values() if c.status == CommandStatus.COMPLETED)
            failed = sum(1 for c in self._commands.values() if c.status in (CommandStatus.TIMEOUT, CommandStatus.FAILED))

        return {
            "running": self.running,
            "devices": len(self._devices),
            "scenes": len(self._scenes),
            "commands": {
                "total": len(self._commands),
                "pending": pending,
                "completed": completed,
                "failed": failed,
            }
        }

    # ═══════════════════════════════════════════════════════
    # MCU 串口接口预留
    # ═══════════════════════════════════════════════════════

    def send_mcu_command(self, mcu_id: str, command: str, params: Dict = None):
        """
        发送命令到 MCU 设备 (预留接口)
        
        未来可通过:
        - 串口 (pyserial)
        - USB CDC
        - ESP32 WiFi
        - BLE
        与单片机通信
        """
        self.send_command(f"mcu/{mcu_id}", command, params)
        logger.info(f"[CmdBridge] MCU 命令: mcu/{mcu_id}/{command}")

    def register_mcu_device(self, mcu_id: str, name: str, capabilities: List[str]):
        """注册 MCU 设备"""
        self.register_device(
            device_id=f"mcu/{mcu_id}",
            name=name,
            device_type="mcu",
            capabilities=capabilities + ["serial_command"],
        )
