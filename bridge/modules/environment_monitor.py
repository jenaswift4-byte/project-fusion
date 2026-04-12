"""
环境感知监控模块
通过传感器数据实现智能场景联动和环境监控

手机作为环境感知设备:
  - 实时监控环境传感器数据
  - 支持智能场景触发 (到家/离家/电影时间/噪音告警等)
  - 数据推送到 Home Assistant
  - 支持 MQTT 协议通信
  - 场景配置化，易于扩展
"""

import time
import threading
import logging
import json
from typing import Dict, List, Optional, Any, Callable
from datetime import datetime

logger = logging.getLogger(__name__)


class EnvironmentMonitor:
    """环境感知监控器"""

    SENSORS = {
        "light": {
            "name": "光线传感器",
            "cmd": "cat /sys/class/sensors/light_sensor/lux",
            "unit": "lux",
            "interval_ms": 1000,
            "thresholds": {
                "dark": 50,
                "dim": 200,
                "normal": 500,
                "bright": 1000
            }
        },
        "proximity": {
            "name": "距离传感器",
            "cmd": "cat /sys/class/sensors/proximity_sensor/proximity",
            "unit": "cm",
            "interval_ms": 500,
            "thresholds": {
                "near": 2,
                "far": 10
            }
        },
        "accelerometer": {
            "name": "加速度传感器",
            "cmd": "cat /sys/class/sensors/accelerometer_0/value",
            "unit": "m/s²",
            "interval_ms": 100,
            "thresholds": {}
        },
        "noise": {
            "name": "噪音传感器",
            "cmd": "media.volume --get",
            "unit": "dB",
            "interval_ms": 2000,
            "thresholds": {
                "quiet": 40,
                "normal": 60,
                "loud": 80,
                "very_loud": 100
            }
        },
        "steps": {
            "name": "计步传感器",
            "cmd": "dumpsys stepcounter",
            "unit": "steps",
            "interval_ms": 5000,
            "thresholds": {}
        }
    }

    PRESET_SCENES = {
        "arrive_home": {
            "trigger": {
                "type": "bluetooth",
                "device": "",
                "condition": "near"
            },
            "actions": [
                {"type": "log", "message": "检测到回家"},
                {"type": "notification", "title": "欢迎回家", "text": "已自动开启智能场景"}
            ]
        },
        "leave_desk": {
            "trigger": {
                "type": "proximity",
                "sensor": "proximity",
                "condition": "far",
                "duration_ms": 30000
            },
            "actions": [
                {"type": "log", "message": "离开工位"},
                {"type": "notification", "title": "离开提醒", "text": "记得带走手机"}
            ]
        },
        "movie_time": {
            "trigger": {
                "type": "compound",
                "conditions": [
                    {"sensor": "light", "operator": "<", "value": 50},
                    {"type": "time_range", "start": "18:00", "end": "23:00"}
                ]
            },
            "actions": [
                {"type": "log", "message": "电影时间"},
                {"type": "notification", "title": "电影模式", "text": "灯光已调暗"}
            ]
        },
        "noise_alert": {
            "trigger": {
                "type": "threshold",
                "sensor": "noise",
                "operator": ">",
                "value": 85,
                "duration_ms": 5000
            },
            "actions": [
                {"type": "log", "message": "噪音告警"},
                {"type": "notification", "title": "环境告警", "text": "环境过于嘈杂"},
                {"type": "ha_push", "sensor": "noise", "alert": True}
            ]
        }
    }

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._monitor_threads: Dict[str, threading.Thread] = {}
        self._stop_events: Dict[str, threading.Event] = {}

        self._sensor_data: Dict[str, Any] = {}
        self._last_values: Dict[str, float] = {}
        self._scene_state: Dict[str, Any] = {}

        self._custom_scenes: Dict[str, Dict[str, Any]] = {}
        self._scene_handlers: Dict[str, List[Callable]] = {}

        self._mqtt_client = None
        self._mqtt_config = daemon.config.get("home_assistant", {}).get("mqtt", {})
        self._ha_config = daemon.config.get("home_assistant", {})

        self._alert_callbacks: List[Callable] = []
        self._scene_callbacks: List[Callable] = []

        self._init_mqtt()

    def _init_mqtt(self):
        """初始化 MQTT 连接"""
        if not self._mqtt_config.get("enabled", False):
            logger.info("[环境监控] MQTT 未启用")
            return

        try:
            import paho.mqtt.client as mqtt
        except ImportError:
            logger.warning("[环境监控] paho-mqtt 未安装，MQTT 功能不可用")
            return

        try:
            self._mqtt_client = mqtt.Client()
            self._mqtt_client.on_connect = self._on_mqtt_connect
            self._mqtt_client.on_disconnect = self._on_mqtt_disconnect
            self._mqtt_client.on_message = self._on_mqtt_message

            broker = self._mqtt_config.get("broker", "localhost")
            port = self._mqtt_config.get("port", 1883)
            username = self._mqtt_config.get("username", "")
            password = self._mqtt_config.get("password", "")

            if username and password:
                self._mqtt_client.username_pw_set(username, password)

            self._mqtt_client.connect(broker, port, 60)
            self._mqtt_client.loop_start()

            logger.info(f"[环境监控] MQTT 连接中: {broker}:{port}")
        except Exception as e:
            logger.error(f"[环境监控] MQTT 连接失败: {e}")
            self._mqtt_client = None

    def _on_mqtt_connect(self, client, userdata, flags, rc):
        """MQTT 连接回调"""
        if rc == 0:
            logger.info("[环境监控] MQTT 已连接")
            for sensor in self.SENSORS.keys():
                topic = f"fusion/sensors/{sensor}/config"
                client.subscribe(topic)
        else:
            logger.warning(f"[环境监控] MQTT 连接失败: {rc}")

    def _on_mqtt_disconnect(self, client, userdata, rc):
        """MQTT 断开回调"""
        logger.warning(f"[环境监控] MQTT 断开连接: {rc}")

    def _on_mqtt_message(self, client, userdata, msg):
        """MQTT 消息回调"""
        try:
            payload = json.loads(msg.payload.decode())
            logger.debug(f"[环境监控] MQTT 消息: {msg.topic} -> {payload}")
        except Exception as e:
            logger.debug(f"[环境监控] MQTT 消息解析失败: {e}")

    def start_monitoring(self):
        """启动监控线程"""
        if self.running:
            logger.warning("[环境监控] 监控已在运行")
            return

        self.running = True
        logger.info("[环境监控] 启动环境监控...")

        for sensor_name, sensor_config in self.SENSORS.items():
            self._start_sensor_monitor(sensor_name, sensor_config)

        for scene_name in self.PRESET_SCENES.keys():
            self._init_scene_state(scene_name)

        logger.info(f"[环境监控] 已启动 {len(self._monitor_threads)} 个传感器监控线程")

    def _start_sensor_monitor(self, sensor_name: str, sensor_config: Dict[str, Any]):
        """启动单个传感器的监控线程

        Args:
            sensor_name: 传感器名称
            sensor_config: 传感器配置
        """
        stop_event = threading.Event()
        self._stop_events[sensor_name] = stop_event

        thread = threading.Thread(
            target=self._sensor_monitor_loop,
            args=(sensor_name, sensor_config, stop_event),
            daemon=True
        )
        self._monitor_threads[sensor_name] = thread
        thread.start()

        logger.debug(f"[环境监控] 启动 {sensor_name} 监控线程 (间隔: {sensor_config['interval_ms']}ms)")

    def _sensor_monitor_loop(self, sensor_name: str, sensor_config: Dict[str, Any], stop_event: threading.Event):
        """传感器监控循环

        Args:
            sensor_name: 传感器名称
            sensor_config: 传感器配置
            stop_event: 停止事件
        """
        interval_sec = sensor_config["interval_ms"] / 1000.0

        while not stop_event.is_set():
            try:
                value = self._read_sensor_value(sensor_name, sensor_config)
                if value is not None:
                    timestamp = time.time()
                    self._on_sensor_data(sensor_name, value, timestamp)
            except Exception as e:
                logger.debug(f"[环境监控] 读取传感器失败 ({sensor_name}): {e}")

            stop_event.wait(interval_sec)

    def _read_sensor_value(self, sensor_name: str, sensor_config: Dict[str, Any]) -> Optional[float]:
        """读取传感器值

        Args:
            sensor_name: 传感器名称
            sensor_config: 传感器配置

        Returns:
            传感器值，读取失败返回 None
        """
        cmd = sensor_config.get("cmd", "")
        if not cmd:
            return None

        output, _, rc = self.daemon.adb_shell(cmd)
        if rc != 0 or not output:
            return None

        try:
            value_str = output.strip().split()[-1]
            if sensor_name == "steps":
                parts = value_str.split(",")
                value = float(parts[0]) if parts else 0.0
            else:
                value = float(value_str)
            return value
        except (ValueError, IndexError):
            return None

    def _on_sensor_data(self, sensor: str, value: float, timestamp: float):
        """处理传感器数据

        Args:
            sensor: 传感器名称
            value: 传感器值
            timestamp: 时间戳
        """
        old_value = self._last_values.get(sensor)

        self._sensor_data[sensor] = {
            "value": value,
            "timestamp": timestamp,
            "unit": self.SENSORS[sensor].get("unit", ""),
            "previous": old_value
        }
        self._last_values[sensor] = value

        self._check_alerts(sensor, value)

        self._check_smart_scenes(sensor, value)

        self._push_to_home_assistant(sensor, value)

        logger.debug(f"[环境监控] {sensor}: {value} {self.SENSORS[sensor].get('unit', '')}")

    def _check_smart_scenes(self, sensor: str, value: float):
        """检查智能场景触发条件

        Args:
            sensor: 传感器名称
            value: 传感器值
        """
        for scene_name, scene_config in self.PRESET_SCENES.items():
            self._evaluate_scene(scene_name, scene_config, sensor, value)

        for scene_name, scene_config in self._custom_scenes.items():
            self._evaluate_scene(scene_name, scene_config, sensor, value)

    def _evaluate_scene(self, scene_name: str, scene_config: Dict[str, Any], sensor: str, value: float):
        """评估场景是否满足触发条件

        Args:
            scene_name: 场景名称
            scene_config: 场景配置
            sensor: 触发传感器名称
            value: 传感器值
        """
        trigger = scene_config.get("trigger", {})
        trigger_type = trigger.get("type", "")

        if trigger_type == "threshold":
            if not self._check_threshold_condition(trigger, sensor, value):
                return

        elif trigger_type == "compound":
            if not self._check_compound_condition(trigger, sensor, value):
                return

        elif trigger_type == "bluetooth":
            if not self._check_bluetooth_condition(trigger):
                return

        elif trigger_type == "proximity":
            if sensor != trigger.get("sensor"):
                return
            if not self._check_proximity_condition(trigger, value):
                return

        else:
            return

        state_key = f"scene_{scene_name}"
        last_triggered = self._scene_state.get(state_key, {}).get("last_triggered", 0)
        cooldown_ms = trigger.get("cooldown_ms", 60000)

        if time.time() * 1000 - last_triggered < cooldown_ms:
            return

        self._execute_scene(scene_name)
        self._scene_state[state_key] = {
            "last_triggered": time.time() * 1000,
            "trigger_value": value
        }

    def _check_threshold_condition(self, trigger: Dict[str, Any], sensor: str, value: float) -> bool:
        """检查阈值条件

        Args:
            trigger: 触发器配置
            sensor: 传感器名称
            value: 传感器值

        Returns:
            是否满足条件
        """
        if trigger.get("sensor") != sensor:
            return False

        operator = trigger.get("operator", ">")
        threshold = trigger.get("value", 0)

        if operator == ">":
            return value > threshold
        elif operator == "<":
            return value < threshold
        elif operator == ">=":
            return value >= threshold
        elif operator == "<=":
            return value <= threshold
        elif operator == "==":
            return value == threshold

        return False

    def _check_compound_condition(self, trigger: Dict[str, Any], sensor: str, value: float) -> bool:
        """检查复合条件

        Args:
            trigger: 触发器配置
            sensor: 传感器名称
            value: 传感器值

        Returns:
            是否满足所有条件
        """
        conditions = trigger.get("conditions", [])
        if not conditions:
            return False

        for condition in conditions:
            if condition.get("type") == "time_range":
                if not self._check_time_range(condition):
                    return False
            else:
                cond_sensor = condition.get("sensor")
                if cond_sensor != sensor:
                    continue

                operator = condition.get("operator", ">")
                threshold = condition.get("value", 0)

                if operator == "<":
                    if not (value < threshold):
                        return False
                elif operator == ">":
                    if not (value > threshold):
                        return False

        return True

    def _check_time_range(self, condition: Dict[str, Any]) -> bool:
        """检查时间范围条件

        Args:
            condition: 时间范围条件配置

        Returns:
            当前时间是否在范围内
        """
        now = datetime.now()
        current_time = now.strftime("%H:%M")

        start = condition.get("start", "00:00")
        end = condition.get("end", "23:59")

        if start <= end:
            return start <= current_time <= end
        else:
            return current_time >= start or current_time <= end

    def _check_proximity_condition(self, trigger: Dict[str, Any], value: float) -> bool:
        """检查距离条件

        Args:
            trigger: 触发器配置
            value: 传感器值

        Returns:
            是否满足条件
        """
        condition = trigger.get("condition", "far")
        near_threshold = self.SENSORS.get("proximity", {}).get("thresholds", {}).get("near", 2)

        if condition == "near":
            return value <= near_threshold
        elif condition == "far":
            return value > near_threshold

        return False

    def _check_bluetooth_condition(self, trigger: Dict[str, Any]) -> bool:
        """检查蓝牙条件

        Args:
            trigger: 触发器配置

        Returns:
            是否满足条件
        """
        device = trigger.get("device", "")
        if not device:
            device = self.daemon.config.get("bluetooth", {}).get("paired_device", "")

        if not device:
            return False

        output, _, rc = self.daemon.adb_shell("dumpsys bluetooth")
        if rc != 0 or not output:
            return False

        condition = trigger.get("condition", "near")
        device_found = device.lower() in output.lower()

        if condition == "near":
            return device_found
        elif condition == "far":
            return not device_found

        return False

    def _execute_scene(self, scene_name: str):
        """执行场景动作

        Args:
            scene_name: 场景名称
        """
        scene_config = self.PRESET_SCENES.get(scene_name) or self._custom_scenes.get(scene_name)
        if not scene_config:
            return

        logger.info(f"[环境监控] 触发场景: {scene_name}")

        actions = scene_config.get("actions", [])
        for action in actions:
            self._execute_action(action)

        for callback in self._scene_callbacks:
            try:
                callback(scene_name, scene_config)
            except Exception as e:
                logger.error(f"[环境监控] 场景回调执行失败 ({scene_name}): {e}")

    def _execute_action(self, action: Dict[str, Any]):
        """执行单个动作

        Args:
            action: 动作配置
        """
        action_type = action.get("type", "")

        if action_type == "log":
            message = action.get("message", "")
            logger.info(f"[环境监控] 场景日志: {message}")

        elif action_type == "notification":
            title = action.get("title", "")
            text = action.get("text", "")
            from bridge.utils.win32_toast import send_toast
            send_toast(title=title, text=text, app_name="Project Fusion")

        elif action_type == "ha_push":
            sensor = action.get("sensor", "")
            alert = action.get("alert", False)
            if sensor and sensor in self._sensor_data:
                value = self._sensor_data[sensor].get("value")
                self._push_to_home_assistant(sensor, value, alert)

        elif action_type == "mqtt":
            topic = action.get("topic", "")
            payload = action.get("payload", {})
            if self._mqtt_client:
                try:
                    self._mqtt_client.publish(topic, json.dumps(payload))
                except Exception as e:
                    logger.error(f"[环境监控] MQTT 发布失败: {e}")

    def _check_alerts(self, sensor: str, value: float):
        """检查告警阈值

        Args:
            sensor: 传感器名称
            value: 传感器值
        """
        sensor_config = self.SENSORS.get(sensor, {})
        thresholds = sensor_config.get("thresholds", {})

        if not thresholds:
            return

        alert_triggered = False
        alert_level = "normal"

        if "very_loud" in thresholds and value > thresholds["very_loud"]:
            alert_triggered = True
            alert_level = "very_loud"
        elif "loud" in thresholds and value > thresholds["loud"]:
            alert_triggered = True
            alert_level = "loud"

        if alert_triggered:
            logger.warning(f"[环境监控] 告警 [{sensor}]: {value} ({alert_level})")

            for callback in self._alert_callbacks:
                try:
                    callback(sensor, value, alert_level)
                except Exception as e:
                    logger.error(f"[环境监控] 告警回调执行失败 ({sensor}): {e}")

    def _push_to_home_assistant(self, sensor: str, value: float, alert: bool = False):
        """推送数据到 Home Assistant

        Args:
            sensor: 传感器名称
            value: 传感器值
            alert: 是否为告警数据
        """
        if not self._ha_config.get("enabled", False):
            return

        ha_host = self._ha_config.get("host", "")
        ha_token = self._ha_config.get("token", "")

        if not ha_host or not ha_token:
            return

        sensor_config = self.SENSORS.get(sensor, {})
        sensor_name = sensor_config.get("name", sensor)
        unit = sensor_config.get("unit", "")

        entity_id = f"sensor.fusion_{sensor}"
        state_topic = f"homeassistant/sensor/{entity_id}/state"
        attributes_topic = f"homeassistant/sensor/{entity_id}/attributes"

        payload_state = json.dumps({
            "state": value,
            "unit_of_measurement": unit,
            "friendly_name": f"Fusion {sensor_name}",
            "device_class": self._get_device_class(sensor)
        })

        payload_attributes = json.dumps({
            "sensor_type": sensor,
            "alert": alert,
            "timestamp": datetime.now().isoformat()
        })

        if self._mqtt_client and self._mqtt_client.is_connected():
            try:
                self._mqtt_client.publish(state_topic, payload_state)
                self._mqtt_client.publish(attributes_topic, payload_attributes)
                logger.debug(f"[环境监控] HA 推送: {sensor} = {value}")
            except Exception as e:
                logger.debug(f"[环境监控] HA MQTT 推送失败: {e}")
        else:
            self._http_push_to_ha(ha_host, ha_token, entity_id, value, unit, sensor_name)

    def _http_push_to_ha(self, host: str, token: str, entity_id: str, value: float, unit: str, sensor_name: str):
        """通过 HTTP 推送数据到 Home Assistant

        Args:
            host: Home Assistant 主机地址
            token: 访问令牌
            entity_id: 实体 ID
            value: 传感器值
            unit: 单位
            sensor_name: 传感器显示名称
        """
        try:
            import urllib.request
            import urllib.error

            url = f"{host}/api/states/{entity_id}"
            headers = {
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json"
            }

            data = json.dumps({
                "state": value,
                "attributes": {
                    "unit_of_measurement": unit,
                    "friendly_name": f"Fusion {sensor_name}",
                    "source": "project_fusion"
                }
            }).encode("utf-8")

            request = urllib.request.Request(url, data=data, headers=headers, method="POST")
            urllib.request.urlopen(request, timeout=5)
            logger.debug(f"[环境监控] HA HTTP 推送成功: {sensor_name} = {value}")
        except Exception as e:
            logger.debug(f"[环境监控] HA HTTP 推送失败: {e}")

    def _get_device_class(self, sensor: str) -> str:
        """获取传感器的设备类

        Args:
            sensor: 传感器名称

        Returns:
            Home Assistant 设备类
        """
        device_classes = {
            "light": "illuminance",
            "noise": "sound_pressure",
            "proximity": "distance",
            "accelerometer": "acceleration"
        }
        return device_classes.get(sensor, "")

    def _init_scene_state(self, scene_name: str):
        """初始化场景状态

        Args:
            scene_name: 场景名称
        """
        self._scene_state[f"scene_{scene_name}"] = {
            "last_triggered": 0,
            "trigger_value": None,
            "active": False
        }

    def create_smart_scene(self, trigger: Dict[str, Any], actions: List[Dict[str, Any]], scene_name: Optional[str] = None) -> str:
        """创建自定义智能场景

        Args:
            trigger: 触发条件配置
            actions: 动作列表配置
            scene_name: 场景名称，默认自动生成

        Returns:
            创建的场景名称
        """
        if scene_name is None:
            scene_name = f"custom_scene_{len(self._custom_scenes) + 1}"

        self._custom_scenes[scene_name] = {
            "trigger": trigger,
            "actions": actions,
            "created_at": datetime.now().isoformat()
        }

        self._init_scene_state(scene_name)

        logger.info(f"[环境监控] 创建自定义场景: {scene_name}")
        return scene_name

    def on_scene_triggered(self, callback: Callable[[str, Dict[str, Any]], None]):
        """注册场景触发回调

        Args:
            callback: 回调函数，签名: (scene_name: str, scene_config: dict) -> None
        """
        self._scene_callbacks.append(callback)

    def on_alert(self, callback: Callable[[str, float, str], None]):
        """注册告警回调

        Args:
            callback: 回调函数，签名: (sensor: str, value: float, level: str) -> None
        """
        self._alert_callbacks.append(callback)

    def get_sensor_data(self, sensor: Optional[str] = None) -> Dict[str, Any]:
        """获取传感器数据

        Args:
            sensor: 传感器名称，None 表示获取所有

        Returns:
            传感器数据字典
        """
        if sensor:
            return self._sensor_data.get(sensor, {})
        return self._sensor_data.copy()

    def stop_monitoring(self):
        """停止所有监控线程"""
        if not self.running:
            return

        self.running = False
        logger.info("[环境监控] 停止环境监控...")

        for sensor_name, stop_event in self._stop_events.items():
            stop_event.set()
            logger.debug(f"[环境监控] 停止 {sensor_name} 监控线程")

        for thread in self._monitor_threads.values():
            thread.join(timeout=2)

        if self._mqtt_client:
            self._mqtt_client.loop_stop()
            self._mqtt_client.disconnect()

        self._monitor_threads.clear()
        self._stop_events.clear()

        logger.info("[环境监控] 环境监控已停止")
