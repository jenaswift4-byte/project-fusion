"""
MQTT Bridge 模块
PC 端 MQTT 通信中枢，负责:
  1. 内置轻量级 MQTT Broker (基于 TCP Server + MQTT 协议解析)
  2. 作为 MQTT 客户端订阅手机传感器数据
  3. 向手机发送控制命令
  4. 数据聚合和转发

设计策略:
  - 不依赖外部 Mosquitto，自建轻量 Broker
  - 手机端 MQTTClientService/SensorCollector 直接连 PC:1883
  - PC 端订阅 sensors/# 和 devices/# 主题
  - 支持 Home Assistant 集成 (后续)
  - 支持 WebSocket 转发 (将 MQTT 数据推送至 WS 客户端)

Topic 设计:
  - sensors/{deviceId}/{sensorType}  — 传感器数据 (手机 → PC)
  - devices/{deviceId}/heartbeat     — 设备心跳 (手机 → PC)
  - devices/{deviceId}               — 控制命令 (PC → 手机)
  - fusion/broadcast                 — 广播消息 (PC ↔ 手机)
  - fusion/mode                      — 模式切换 (PC → 手机)
  - fusion/scene                     — 场景触发 (PC → 手机)
  - fusion/camera/{deviceId}/command — 摄像头控制 (PC → 手机)
  - fusion/audio/{deviceId}/command  — 音频控制 (PC → 手机)
"""

import socket
import struct
import threading
import logging
import json
import time
import hashlib
from typing import Dict, List, Optional, Callable, Any, Tuple
from collections import defaultdict
from enum import IntEnum

# PC 端 MQTT 客户端 (Paho) — 用于桥接手机本地 Broker
try:
    import paho.mqtt.client as mqtt
    HAS_PAHO = True
except ImportError:
    HAS_PAHO = False

logger = logging.getLogger(__name__)


# ═══════════════════════════════════════════════════════
# MQTT 协议常量
# ═══════════════════════════════════════════════════════

class MQTTControlPacketType(IntEnum):
    CONNECT = 1
    CONNACK = 2
    PUBLISH = 3
    PUBACK = 4
    SUBSCRIBE = 8
    SUBACK = 9
    UNSUBSCRIBE = 10
    UNSUBACK = 11
    PINGREQ = 12
    PINGRESP = 13
    DISCONNECT = 14


class ConnackCode(IntEnum):
    ACCEPTED = 0
    UNACCEPTABLE_PROTOCOL = 1
    IDENTIFIER_REJECTED = 2
    SERVER_UNAVAILABLE = 3
    BAD_CREDENTIALS = 4
    NOT_AUTHORIZED = 5


class SubackCode(IntEnum):
    MAX_QOS_0 = 0
    MAX_QOS_1 = 1
    MAX_QOS_2 = 2
    FAILURE = 128


# ═══════════════════════════════════════════════════════
# MQTT 消息解析
# ═══════════════════════════════════════════════════════

def _read_utf8_string(data: bytes, offset: int) -> Tuple[str, int]:
    """读取 MQTT UTF-8 编码字符串"""
    length = struct.unpack("!H", data[offset:offset+2])[0]
    s = data[offset+2:offset+2+length].decode("utf-8", errors="replace")
    return s, offset + 2 + length


def _encode_utf8_string(s: str) -> bytes:
    """编码 MQTT UTF-8 字符串"""
    encoded = s.encode("utf-8")
    return struct.pack("!H", len(encoded)) + encoded


def _remaining_length_encode(length: int) -> bytes:
    """编码 MQTT 可变剩余长度"""
    result = bytearray()
    while True:
        byte = length % 128
        length = length // 128
        if length > 0:
            byte |= 0x80
        result.append(byte)
        if length == 0:
            break
    return bytes(result)


def _remaining_length_decode(data: bytes, offset: int) -> Tuple[int, int]:
    """解码 MQTT 可变剩余长度，返回 (length, new_offset)"""
    multiplier = 1
    value = 0
    index = offset
    while True:
        byte = data[index]
        value += (byte & 0x7F) * multiplier
        index += 1
        if (byte & 0x80) == 0:
            break
        multiplier *= 128
    return value, index


# ═══════════════════════════════════════════════════════
# MQTT Broker 实现
# ═══════════════════════════════════════════════════════

class MQTTClientSession:
    """单个 MQTT 客户端会话"""

    def __init__(self, client_id: str, socket: socket.socket, address: Tuple[str, int]):
        self.client_id = client_id
        self.socket = socket
        self.address = address
        self.connected = False
        self.subscriptions: Dict[str, int] = {}  # topic -> max qos
        self.keep_alive = 60
        self.last_activity = time.time()
        self.clean_session = True
        self.lock = threading.Lock()


class MQTTBroker:
    """
    轻量级 MQTT v3.1.1 Broker
    
    功能:
    - TCP 服务器监听端口 1883
    - CONNECT/CONNACK 握手
    - PUBLISH 消息路由 (支持通配符 +/#)
    - SUBSCRIBE/SUBACK 订阅管理
    - UNSUBSCRIBE/UNSUBACK
    - PINGREQ/PINGRESP 心跳
    - DISCONNECT 断开处理
    - Retained 消息 (最后一条保留)
    """

    def __init__(self, host: str = "0.0.0.0", port: int = 1883):
        self.host = host
        self.port = port
        self.server_socket: Optional[socket.socket] = None
        self.running = False
        self.clients: Dict[str, MQTTClientSession] = {}
        self.retained_messages: Dict[str, bytes] = {}  # topic -> payload
        self.message_callbacks: List[Callable[[str, bytes, str], None]] = []
        self.client_connect_callbacks: List[Callable[[str], None]] = []
        self.client_disconnect_callbacks: List[Callable[[str], None]] = []
        self._lock = threading.Lock()

    def on_message(self, callback: Callable[[str, bytes, str], None]):
        """注册消息回调: callback(topic, payload, client_id)"""
        self.message_callbacks.append(callback)

    def on_client_connect(self, callback: Callable[[str], None]):
        """注册客户端连接回调: callback(client_id)"""
        self.client_connect_callbacks.append(callback)

    def on_client_disconnect(self, callback: Callable[[str], None]):
        """注册客户端断开回调: callback(client_id)"""
        self.client_disconnect_callbacks.append(callback)

    def start(self) -> bool:
        """启动 MQTT Broker"""
        if self.running:
            return True

        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind((self.host, self.port))
            self.server_socket.listen(20)
            self.server_socket.settimeout(1.0)
            self.running = True

            accept_thread = threading.Thread(target=self._accept_loop, daemon=True)
            accept_thread.start()

            logger.info(f"[MQTT Broker] 已启动 - {self.host}:{self.port}")
            return True

        except OSError as e:
            logger.error(f"[MQTT Broker] 启动失败: {e}")
            self.running = False
            return False

    def stop(self):
        """停止 MQTT Broker"""
        self.running = False

        with self._lock:
            for client_id, session in list(self.clients.items()):
                try:
                    session.socket.close()
                except Exception:
                    pass
            self.clients.clear()

        if self.server_socket:
            try:
                self.server_socket.close()
            except Exception:
                pass

        logger.info("[MQTT Broker] 已停止")

    def publish(self, topic: str, payload: bytes, qos: int = 0, retain: bool = False):
        """
        Broker 内部发布消息 (不经过网络，直接路由到本地订阅者)
        可供其他模块调用，比如把 WS 收到的数据发布到 MQTT
        """
        # Retained
        if retain:
            self.retained_messages[topic] = payload

        # 路由到所有匹配的订阅者
        with self._lock:
            for client_id, session in self.clients.items():
                if not session.connected:
                    continue
                matched_qos = self._topic_matches_any(session.subscriptions.keys(), topic)
                if matched_qos is not None:
                    try:
                        self._send_publish(session.socket, topic, payload, matched_qos)
                    except Exception as e:
                        logger.debug(f"[MQTT Broker] 发送到 {client_id} 失败: {e}")

        # 触发本地回调
        for cb in self.message_callbacks:
            try:
                cb(topic, payload, "broker")
            except Exception as e:
                logger.debug(f"[MQTT Broker] 消息回调异常: {e}")

    def publish_text(self, topic: str, message: str, qos: int = 0, retain: bool = False):
        """发布文本消息"""
        self.publish(topic, message.encode("utf-8"), qos, retain)

    def get_client_count(self) -> int:
        """获取已连接客户端数"""
        with self._lock:
            return sum(1 for s in self.clients.values() if s.connected)

    def get_connected_clients(self) -> List[str]:
        """获取已连接的客户端 ID 列表"""
        with self._lock:
            return [cid for cid, s in self.clients.items() if s.connected]

    # ═══════════════════════════════════════════════════════
    # 内部实现
    # ═══════════════════════════════════════════════════════

    def _accept_loop(self):
        """接受客户端连接"""
        while self.running:
            try:
                client_sock, address = self.server_socket.accept()
                client_sock.settimeout(30)
                thread = threading.Thread(
                    target=self._handle_client,
                    args=(client_sock, address),
                    daemon=True
                )
                thread.start()
            except socket.timeout:
                continue
            except OSError:
                if self.running:
                    logger.debug("[MQTT Broker] accept 异常")
                break

    def _handle_client(self, sock: socket.socket, address: Tuple[str, int]):
        """处理单个客户端"""
        session = None
        try:
            # 第一个包必须是 CONNECT
            # 读取固定头部第一个字节 + 至少1字节剩余长度
            header = self._recv_all(sock, 2)
            if not header or len(header) < 2:
                return

            packet_type = (header[0] & 0xF0) >> 4
            if packet_type != MQTTControlPacketType.CONNECT:
                logger.warning(f"[MQTT Broker] {address}: 首包不是 CONNECT")
                return

            # 解码剩余长度 (可能需要读更多字节)
            remaining_length, _ = _remaining_length_decode(header, 1)
            remaining_data = self._recv_all(sock, remaining_length)
            if not remaining_data:
                return

            # 解析 CONNECT
            session = self._parse_connect(remaining_data, sock, address)
            if not session:
                self._send_connack(sock, ConnackCode.UNACCEPTABLE_PROTOCOL)
                return

            # 发送 CONNACK
            self._send_connack(sock, ConnackCode.ACCEPTED)
            session.connected = True
            session.last_activity = time.time()

            with self._lock:
                self.clients[session.client_id] = session

            logger.info(f"[MQTT Broker] 客户端连接: {session.client_id} ({address[0]})")

            # 回调
            for cb in self.client_connect_callbacks:
                try:
                    cb(session.client_id)
                except Exception:
                    pass

            # 发送 retained 消息
            self._send_retained_messages(session)

            # 消息循环
            while self.running and session.connected:
                sock.settimeout(max(session.keep_alive, 5))
                try:
                    header = self._recv_all(sock, 2)
                    if not header:
                        break

                    pkt_type = (header[0] & 0xF0) >> 4
                    flags = header[0] & 0x0F
                    rem_len, data_offset = _remaining_length_decode(header, 1)
                    payload = self._recv_all(sock, rem_len) if rem_len > 0 else b""

                    session.last_activity = time.time()

                    if pkt_type == MQTTControlPacketType.PUBLISH:
                        self._handle_publish(session, flags, payload)
                    elif pkt_type == MQTTControlPacketType.SUBSCRIBE:
                        self._handle_subscribe(session, payload)
                    elif pkt_type == MQTTControlPacketType.UNSUBSCRIBE:
                        self._handle_unsubscribe(session, payload)
                    elif pkt_type == MQTTControlPacketType.PINGREQ:
                        self._send_pingresp(sock)
                    elif pkt_type == MQTTControlPacketType.DISCONNECT:
                        logger.info(f"[MQTT Broker] 客户端主动断开: {session.client_id}")
                        break
                    else:
                        logger.debug(f"[MQTT Broker] 收到未知包类型: {pkt_type}")

                except socket.timeout:
                    # KeepAlive 检查
                    if time.time() - session.last_activity > session.keep_alive * 1.5:
                        logger.warning(f"[MQTT Broker] KeepAlive 超时: {session.client_id}")
                        break
                    continue
                except (ConnectionError, OSError):
                    break

        except Exception as e:
            logger.debug(f"[MQTT Broker] 客户端处理异常: {e}")
        finally:
            if session:
                session.connected = False
                with self._lock:
                    self.clients.pop(session.client_id, None)
                for cb in self.client_disconnect_callbacks:
                    try:
                        cb(session.client_id)
                    except Exception:
                        pass
                logger.info(f"[MQTT Broker] 客户端断开: {session.client_id}")
            try:
                sock.close()
            except Exception:
                pass

    def _parse_connect(self, data: bytes, sock: socket.socket, address: tuple) -> Optional[MQTTClientSession]:
        """解析 CONNECT 包"""
        try:
            offset = 0
            # 协议名
            proto_name, offset = _read_utf8_string(data, offset)
            # 协议级别
            proto_level = data[offset]; offset += 1
            # 连接标志
            connect_flags = data[offset]; offset += 1
            # Keep Alive
            keep_alive = struct.unpack("!H", data[offset:offset+2])[0]; offset += 2
            # 客户端 ID
            client_id, offset = _read_utf8_string(data, offset)

            if not client_id:
                client_id = f"client-{address[0]}-{address[1]}"

            session = MQTTClientSession(client_id, sock, address)
            session.keep_alive = keep_alive if keep_alive > 0 else 60
            session.clean_session = bool(connect_flags & 0x02)

            # 如果有用户名密码 (跳过解析，Fusion 内网不需要认证)
            has_username = bool(connect_flags & 0x80)
            has_password = bool(connect_flags & 0x40)

            return session
        except Exception as e:
            logger.error(f"[MQTT Broker] 解析 CONNECT 失败: {e}")
            return None

    def _send_connack(self, sock: socket.socket, code: ConnackCode):
        """发送 CONNACK"""
        # Fixed header: type=CONNACK(2) << 4 = 0x20, remaining=2
        packet = bytes([
            MQTTControlPacketType.CONNACK << 4, 2,
            0x00,  # Session Present = 0
            code
        ])
        sock.sendall(packet)

    def _send_pingresp(self, sock: socket.socket):
        """发送 PINGRESP"""
        sock.sendall(bytes([MQTTControlPacketType.PINGRESP << 4, 0]))

    def _handle_publish(self, session: MQTTClientSession, flags: int, data: bytes):
        """处理 PUBLISH 消息"""
        try:
            qos = (flags >> 1) & 0x03
            retain = bool(flags & 0x01)
            dup = bool(flags & 0x08)

            offset = 0
            topic, offset = _read_utf8_string(data, offset)

            # Packet ID (仅 QoS > 0)
            packet_id = None
            if qos > 0:
                packet_id = struct.unpack("!H", data[offset:offset+2])[0]
                offset += 2

            payload = data[offset:]

            # Retained
            if retain:
                self.retained_messages[topic] = payload

            # PUBACK (QoS 1)
            if qos == 1 and packet_id is not None:
                ack = struct.pack("!BBH", MQTTControlPacketType.PUBACK << 4, 2, packet_id)
                session.socket.sendall(ack)

            # 路由到其他订阅者
            with self._lock:
                for client_id, other_session in self.clients.items():
                    if client_id == session.client_id or not other_session.connected:
                        continue
                    matched_qos = self._topic_matches_any(other_session.subscriptions.keys(), topic)
                    if matched_qos is not None:
                        try:
                            self._send_publish(other_session.socket, topic, payload, min(qos, matched_qos))
                        except Exception as e:
                            logger.debug(f"[MQTT Broker] 转发到 {client_id} 失败: {e}")

            # 触发本地回调
            for cb in self.message_callbacks:
                try:
                    cb(topic, payload, session.client_id)
                except Exception as e:
                    logger.debug(f"[MQTT Broker] 消息回调异常: {e}")

            logger.debug(f"[MQTT Broker] PUBLISH: {topic} ({len(payload)}B) from {session.client_id}")

        except Exception as e:
            logger.error(f"[MQTT Broker] 处理 PUBLISH 失败: {e}")

    def _send_publish(self, sock: socket.socket, topic: str, payload: bytes, qos: int = 0):
        """发送 PUBLISH 消息"""
        topic_bytes = _encode_utf8_string(topic)
        packet_id_bytes = b""
        if qos > 0:
            packet_id_bytes = struct.pack("!H", 0)  # 简化: packet_id = 0

        variable_header = topic_bytes + packet_id_bytes
        remaining = variable_header + payload

        first_byte = MQTTControlPacketType.PUBLISH << 4
        if qos > 0:
            first_byte |= (qos << 1)

        header = bytes([first_byte]) + _remaining_length_encode(len(remaining))
        sock.sendall(header + remaining)

    def _handle_subscribe(self, session: MQTTClientSession, data: bytes):
        """处理 SUBSCRIBE"""
        try:
            offset = 0
            packet_id = struct.unpack("!H", data[offset:offset+2])[0]; offset += 2

            return_codes = []
            while offset < len(data):
                topic_filter, offset = _read_utf8_string(data, offset)
                requested_qos = data[offset]; offset += 1

                session.subscriptions[topic_filter] = requested_qos
                return_codes.append(min(requested_qos, 1))  # Broker 最大支持 QoS 1
                logger.debug(f"[MQTT Broker] {session.client_id} 订阅: {topic_filter} (QoS {requested_qos})")

            # 发送 SUBACK (type=9)
            var_header = struct.pack("!H", packet_id)
            payload = bytes(return_codes)
            packet = bytes([9 << 4, len(var_header) + len(payload)]) + \
                     var_header + payload
            session.socket.sendall(packet)

            # 发送 retained 消息
            self._send_retained_for_topic(session, topic_filter)

        except Exception as e:
            logger.error(f"[MQTT Broker] 处理 SUBSCRIBE 失败: {e}")

    def _handle_unsubscribe(self, session: MQTTClientSession, data: bytes):
        """处理 UNSUBSCRIBE"""
        try:
            offset = 0
            packet_id = struct.unpack("!H", data[offset:offset+2])[0]; offset += 2

            while offset < len(data):
                topic_filter, offset = _read_utf8_string(data, offset)
                session.subscriptions.pop(topic_filter, None)
                logger.debug(f"[MQTT Broker] {session.client_id} 取消订阅: {topic_filter}")

            # 发送 UNSUBACK (type=11)
            packet = bytes([11 << 4, 2, (packet_id >> 8) & 0xFF, packet_id & 0xFF])
            session.socket.sendall(packet)

        except Exception as e:
            logger.error(f"[MQTT Broker] 处理 UNSUBSCRIBE 失败: {e}")

    def _send_retained_messages(self, session: MQTTClientSession):
        """发送所有匹配的 retained 消息"""
        for topic, payload in self.retained_messages.items():
            matched_qos = self._topic_matches_any(session.subscriptions.keys(), topic)
            if matched_qos is not None:
                try:
                    self._send_publish(session.socket, topic, payload, matched_qos)
                except Exception:
                    pass

    def _send_retained_for_topic(self, session: MQTTClientSession, topic_filter: str):
        """发送匹配特定 topic filter 的 retained 消息"""
        for topic, payload in self.retained_messages.items():
            if self._topic_matches(topic_filter, topic):
                try:
                    self._send_publish(session.socket, topic, payload, session.subscriptions.get(topic_filter, 0))
                except Exception:
                    pass

    @staticmethod
    def _topic_matches(subscription: str, topic: str) -> bool:
        """检查 topic 是否匹配 subscription (支持 + 和 # 通配符)"""
        sub_parts = subscription.split("/")
        topic_parts = topic.split("/")

        si = 0
        ti = 0

        while si < len(sub_parts) and ti < len(topic_parts):
            if sub_parts[si] == "#":
                return True  # # 匹配剩余所有
            elif sub_parts[si] == "+":
                si += 1
                ti += 1
            elif sub_parts[si] == topic_parts[ti]:
                si += 1
                ti += 1
            else:
                return False

        # 处理结尾
        if si == len(sub_parts) and ti == len(topic_parts):
            return True
        if si == len(sub_parts) - 1 and sub_parts[si] == "#":
            return True

        return False

    def _topic_matches_any(self, subscriptions: List[str], topic: str) -> Optional[int]:
        """检查 topic 是否匹配任意订阅，返回最高 QoS 或 None"""
        best_qos = None
        for sub, qos in subscriptions.items() if isinstance(subscriptions, dict) else [(s, 0) for s in subscriptions]:
            if self._topic_matches(sub, topic):
                if best_qos is None or qos > best_qos:
                    best_qos = qos
        return best_qos

    @staticmethod
    def _recv_all(sock: socket.socket, count: int) -> Optional[bytes]:
        """接收指定字节数"""
        data = bytearray()
        while len(data) < count:
            try:
                chunk = sock.recv(count - len(data))
                if not chunk:
                    return None
                data.extend(chunk)
            except (socket.timeout, OSError):
                return None
        return bytes(data)


# ═══════════════════════════════════════════════════════
# MQTT Bridge (集成层)
# ═══════════════════════════════════════════════════════

class MQTTBridge:
    """
    MQTT Bridge 模块 - 供 main.py 集成

    职责:
    1. 启动内嵌 MQTT Broker
    2. 自动订阅所有传感器主题
    3. 聚合传感器数据供仪表盘使用
    4. 提供统一的发布接口给其他模块
    5. 与 WebSocket 客户端联动 (将 MQTT 数据通过 WS 推送)
    """

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False

        # MQTT Broker (PC 端)
        self.broker = MQTTBroker(
            host="0.0.0.0",
            port=daemon.config.get("mqtt", {}).get("port", 1883)
        )

        # 手机数据桥接客户端 (通过 ADB forward 订阅手机本地 Broker)
        self._phone_bridge_client = None
        self._phone_bridge_thread = None

        # 数据聚合
        self.sensor_data: Dict[str, Dict[str, Any]] = {}  # device_id -> {sensor_type -> {value, unit, timestamp}}
        self.device_status: Dict[str, Dict[str, Any]] = {}  # device_id -> {online, battery, last_seen}
        self.latest_messages: List[Dict[str, Any]] = []  # 最近消息缓存
        self._max_recent_messages = 500

        # 回调
        self._on_sensor_data_callbacks: List[Callable] = []
        self._on_device_online_callbacks: List[Callable] = []
        self._on_device_offline_callbacks: List[Callable] = []
        self._on_command_response_callbacks: List[Callable] = []  # 命令响应回调
        self._on_device_state_callbacks: List[Callable] = []      # 设备状态变化回调

        # 注册 Broker 回调
        self.broker.on_message(self._on_broker_message)
        self.broker.on_client_connect(self._on_client_connected)
        self.broker.on_client_disconnect(self._on_client_disconnected)

        # 内部订阅: 命令响应和设备状态
        self._internal_subscriptions = [
            "fusion/cmd/#",           # 命令响应
            "fusion/devices/#",       # 设备状态
            "fusion/scene/#",         # 场景事件
            "fusion/camera/#",        # 摄像头控制
            "fusion/audio/#",         # 音频控制
        ]

    def start(self) -> bool:
        """启动 MQTT Bridge"""
        if self.running:
            return True

        if not self.broker.start():
            return False

        self.running = True
        logger.info(f"[MQTT Bridge] 已启动 - Broker 端口: {self.broker.port}")

        # 启动手机数据桥接 (通过 ADB forward 订阅手机本地 Broker)
        self._start_phone_bridge()

        return True

    # ═══════════════════════════════════════════════════════
    # 手机数据桥接 (ADB forward → 手机本地 Broker → PC Dashboard)
    # ═══════════════════════════════════════════════════════

    def _start_phone_bridge(self):
        """启动手机数据桥接客户端

        当手机 MQTTClientService 在 fallback 模式下连本地 Broker (127.0.0.1:1883) 时，
        PC 端通过 ADB forward tcp:1884 → tcp:1883 订阅手机 Broker 的传感器数据，
        然后注入到 Dashboard 和 PC Broker。
        """
        if not HAS_PAHO:
            logger.info("[PhoneBridge] paho-mqtt 未安装，跳过手机数据桥接")
            return

        try:
            # 设置 ADB forward: PC 1884 → 手机 1883
            adb_path = self.daemon.config.get("adb", {}).get("path", "adb")
            device_serial = getattr(self.daemon, "device_serial", None) or self.daemon.config.get("adb", {}).get("device_serial", "")

            # 构建 ADB 命令（如果有多设备需要指定 -s）
            adb_cmd = [adb_path]
            if device_serial:
                adb_cmd.extend(["-s", device_serial])
            adb_cmd.extend(["forward", "tcp:1884", "tcp:1883"])

            import subprocess
            result = subprocess.run(adb_cmd, capture_output=True, text=True, timeout=10)
            if result.returncode != 0:
                logger.warning(f"[PhoneBridge] ADB forward 设置失败: {result.stderr}")
                return

            logger.info("[PhoneBridge] ADB forward 已设置: tcp:1884 → phone:1883")

            # 创建 Paho MQTT 客户端连接手机 Broker
            client = mqtt.Client(client_id="pc-phone-bridge", clean_session=True)

            def on_connect(cli, userdata, flags, rc):
                if rc == 0:
                    logger.info("[PhoneBridge] 已连接手机 Broker")
                    cli.subscribe([
                        ("sensors/#", 0), 
                        ("devices/#", 0), 
                        ("fusion/+/broker", 0),
                        # 反向通道: 订阅手机 Broker 的命令响应
                        ("fusion/+/response", 0),
                    ])
                    logger.info("[PhoneBridge] 已订阅 sensors/#, devices/#, fusion/+/response")
                else:
                    logger.warning(f"[PhoneBridge] 连接手机 Broker 失败: rc={rc}")

            def on_message(cli, userdata, msg):
                """手机 Broker 的消息转发到 PC Broker 和 Dashboard"""
                try:
                    topic = msg.topic
                    payload = msg.payload.decode("utf-8", errors="replace")

                    # 解析传感器数据
                    if topic.startswith("sensors/"):
                        try:
                            data = json.loads(payload)
                            device_id = data.get("device_id", "unknown")
                            sensor_type = data.get("sensor_type", "unknown")
                            value = data.get("value", 0)
                            unit = data.get("unit", "")

                            # 注入到 PC Broker (让 Dashboard 能收到)
                            self.broker.publish(topic, msg.payload, 0)

                            # 触发回调
                            for cb in self._on_sensor_data_callbacks:
                                try:
                                    cb(device_id, sensor_type, value, unit)
                                except Exception:
                                    pass

                        except json.JSONDecodeError:
                            pass

                    # PC Broker 发现消息 — 转发到手机让 MQTTClientService 发现 PC
                    elif topic.startswith("fusion/") and "broker" in topic:
                        logger.info(f"[PhoneBridge] 收到 Broker 发现消息: {payload}")

                    # 设备心跳
                    elif topic.startswith("devices/") and topic.endswith("/heartbeat"):
                        try:
                            data = json.loads(payload)
                            device_id = data.get("device_id", "unknown")
                            for cb in self._on_device_online_callbacks:
                                try:
                                    cb(device_id, data)
                                except Exception:
                                    pass
                        except json.JSONDecodeError:
                            pass

                except Exception as e:
                    logger.error(f"[PhoneBridge] 消息处理错误: {e}")

            client.on_connect = on_connect
            client.on_message = on_message

            # 连接 (通过 ADB forward 的本地端口)
            client.connect_async("127.0.0.1", 1884, 60)
            client.loop_start()

            self._phone_bridge_client = client
            logger.info("[PhoneBridge] 手机数据桥接已启动 (127.0.0.1:1884 → phone:1883)")

        except Exception as e:
            logger.error(f"[PhoneBridge] 启动失败: {e}")

    def _stop_phone_bridge(self):
        """停止手机数据桥接"""
        if self._phone_bridge_client:
            try:
                self._phone_bridge_client.loop_stop()
                self._phone_bridge_client.disconnect()
            except Exception:
                pass
            self._phone_bridge_client = None

    def _forward_command_to_phone(self, topic: str, payload: bytes):
        """反向桥接: 将 PC Broker 收到的命令转发到手机本地 Broker
        
        当手机 MQTTClientService 在 fallback 模式下连本地 Broker (127.0.0.1:1883) 时，
        PC 端 command_bridge 发送的 MQTT 命令 (fusion/cmd/#, fusion/audio/#) 
        只到达 PC Broker，手机收不到。
        
        此方法通过 ADB forward 的 PhoneBridge 客户端将命令转发到手机 Broker。
        """
        if not self._phone_bridge_client or not self._phone_bridge_client.is_connected():
            return

        try:
            self._phone_bridge_client.publish(topic, payload, qos=0)
            logger.debug(f"[PhoneBridge] 命令已转发到手机: {topic}")
        except Exception as e:
            logger.debug(f"[PhoneBridge] 命令转发失败: {e}")

    def stop(self):
        """停止 MQTT Bridge"""
        self.running = False
        self._stop_phone_bridge()
        self.broker.stop()
        logger.info("[MQTT Bridge] 已停止")

    # ═══════════════════════════════════════════════════════
    # 发布接口 (供其他模块调用)
    # ═══════════════════════════════════════════════════════

    def publish(self, topic: str, payload: bytes, qos: int = 0):
        """发布原始字节消息"""
        if self.running:
            self.broker.publish(topic, payload, qos)

    def publish_text(self, topic: str, message: str, qos: int = 0):
        """发布文本消息"""
        self.publish(topic, message.encode("utf-8"), qos)

    def publish_json(self, topic: str, data: Any, qos: int = 0):
        """发布 JSON 消息"""
        self.publish_text(topic, json.dumps(data, ensure_ascii=False), qos)

    def send_command(self, device_id: str, command: str, params: dict = None):
        """向指定设备发送控制命令"""
        topic = f"devices/{device_id}"
        payload = {
            "command": command,
            "timestamp": int(time.time() * 1000),
        }
        if params:
            payload["params"] = params
        self.publish_json(topic, payload, qos=1)

    def broadcast(self, message: str, data: dict = None):
        """广播消息到所有设备"""
        payload = {
            "message": message,
            "timestamp": int(time.time() * 1000),
        }
        if data:
            payload["data"] = data
        self.publish_json("fusion/broadcast", payload)

    def set_mode(self, mode: str):
        """设置模式 (online/offline)"""
        self.publish_json("fusion/mode", {"mode": mode, "timestamp": int(time.time() * 1000)})

    # ═══════════════════════════════════════════════════════
    # 数据查询接口
    # ═══════════════════════════════════════════════════════

    def get_sensor_data(self, device_id: str = None) -> Dict[str, Any]:
        """获取传感器数据"""
        if device_id:
            return self.sensor_data.get(device_id, {})
        return self.sensor_data.copy()

    def get_device_status(self) -> Dict[str, Any]:
        """获取所有设备状态"""
        return self.device_status.copy()

    def get_latest_messages(self, count: int = 50) -> List[Dict[str, Any]]:
        """获取最近消息"""
        return self.latest_messages[-count:]

    def get_connected_clients(self) -> List[str]:
        """获取已连接的 MQTT 客户端列表"""
        return self.broker.get_connected_clients()

    def get_client_count(self) -> int:
        """获取已连接客户端数"""
        return self.broker.get_client_count()

    # ═══════════════════════════════════════════════════════
    # 事件回调注册
    # ═══════════════════════════════════════════════════════

    def on_sensor_data(self, callback: Callable[[str, str, float, str], None]):
        """注册传感器数据回调: callback(device_id, sensor_type, value, unit)"""
        self._on_sensor_data_callbacks.append(callback)

    def on_device_online(self, callback: Callable[[str], None]):
        """注册设备上线回调: callback(device_id)"""
        self._on_device_online_callbacks.append(callback)

    def on_device_offline(self, callback: Callable[[str], None]):
        """注册设备下线回调: callback(device_id)"""
        self._on_device_offline_callbacks.append(callback)

    def on_command_response(self, callback: Callable[[str, dict], None]):
        """注册命令响应回调: callback(cmd_id, response_data)"""
        self._on_command_response_callbacks.append(callback)

    def on_device_state(self, callback: Callable[[str, dict], None]):
        """注册设备状态变化回调: callback(device_id, state_data)"""
        self._on_device_state_callbacks.append(callback)

    # ═══════════════════════════════════════════════════════
    # 内部回调
    # ═══════════════════════════════════════════════════════

    def _on_broker_message(self, topic: str, payload: bytes, client_id: str):
        """Broker 消息回调"""
        now = time.time()

        # 缓存消息
        msg_record = {
            "topic": topic,
            "payload": payload.decode("utf-8", errors="replace"),
            "client_id": client_id,
            "timestamp": now,
        }
        self.latest_messages.append(msg_record)
        if len(self.latest_messages) > self._max_recent_messages:
            self.latest_messages = self.latest_messages[-self._max_recent_messages:]

        # 解析传感器数据: sensors/{deviceId}/{sensorType}
        parts = topic.split("/")
        if len(parts) == 3 and parts[0] == "sensors":
            device_id = parts[1]
            sensor_type = parts[2]
            try:
                data = json.loads(payload)
                value = float(data.get("value", 0))
                unit = data.get("unit", "")
                ts = data.get("timestamp", now * 1000) / 1000.0

                if device_id not in self.sensor_data:
                    self.sensor_data[device_id] = {}
                self.sensor_data[device_id][sensor_type] = {
                    "value": value,
                    "unit": unit,
                    "timestamp": ts,
                }

                # 回调
                for cb in self._on_sensor_data_callbacks:
                    try:
                        cb(device_id, sensor_type, value, unit)
                    except Exception as e:
                        logger.debug(f"[MQTT Bridge] 传感器数据回调异常: {e}")

            except (json.JSONDecodeError, ValueError):
                pass

        # 解析心跳: devices/{deviceId}/heartbeat
        elif len(parts) == 3 and parts[0] == "devices" and parts[2] == "heartbeat":
            device_id = parts[1]
            try:
                data = json.loads(payload)
                battery = data.get("battery_level", -1)
                self.device_status[device_id] = {
                    "online": True,
                    "battery": battery,
                    "last_seen": now,
                    "client_id": client_id,
                }
                logger.debug(f"[MQTT Bridge] 心跳: {device_id} (电量: {battery}%)")
            except json.JSONDecodeError:
                pass

        # 命令响应: fusion/cmd/{targetId}/response
        elif len(parts) >= 3 and parts[0] == "fusion" and parts[1] == "cmd":
            try:
                data = json.loads(payload)
                cmd_id = data.get("id", "")
                response = data.get("response", data)
                for cb in self._on_command_response_callbacks:
                    try:
                        cb(cmd_id, response)
                    except Exception as e:
                        logger.debug(f"[MQTT Bridge] 命令响应回调异常: {e}")
                logger.debug(f"[MQTT Bridge] 命令响应: {topic}")
            except json.JSONDecodeError:
                pass

            # 🔁 反向桥接: PC→手机命令转发
            # 当手机 MQTTClientService 在 fallback 模式连本地 Broker 时，
            # PC Broker 收到的命令需要转发到手机本地 Broker
            self._forward_command_to_phone(topic, payload)

        # 🔁 反向桥接: 音频命令转发 (fusion/audio/*)
        elif len(parts) >= 2 and parts[0] == "fusion" and parts[1] == "audio":
            self._forward_command_to_phone(topic, payload)

        # 设备状态上报: fusion/devices/{deviceId}/state
        elif len(parts) >= 3 and parts[0] == "fusion" and parts[1] == "devices":
            try:
                data = json.loads(payload)
                device_id = parts[2]
                for cb in self._on_device_state_callbacks:
                    try:
                        cb(device_id, data)
                    except Exception as e:
                        logger.debug(f"[MQTT Bridge] 设备状态回调异常: {e}")
                logger.debug(f"[MQTT Bridge] 设备状态: {topic}")
            except json.JSONDecodeError:
                pass

    def _on_client_connected(self, client_id: str):
        """客户端连接回调"""
        self.device_status[client_id] = {
            "online": True,
            "battery": -1,
            "last_seen": time.time(),
            "client_id": client_id,
        }
        logger.info(f"[MQTT Bridge] 设备上线: {client_id}")

        for cb in self._on_device_online_callbacks:
            try:
                cb(client_id)
            except Exception:
                pass

    def _on_client_disconnected(self, client_id: str):
        """客户端断开回调"""
        if client_id in self.device_status:
            self.device_status[client_id]["online"] = False
        logger.info(f"[MQTT Bridge] 设备下线: {client_id}")

        for cb in self._on_device_offline_callbacks:
            try:
                cb(client_id)
            except Exception:
                pass
