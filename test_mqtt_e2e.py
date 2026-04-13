"""MQTT Broker 端到端测试 - 不发出任何声音"""
import sys, json, time, socket, struct, threading

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(('8.8.8.8', 80))
    ip = s.getsockname()[0]
    s.close()
    return ip

MQTT_PORT = 1883
pc_ip = get_local_ip()
print(f"PC IP: {pc_ip}")
print(f"MQTT Broker 启动在端口 {MQTT_PORT}...")

class MiniMQTTBroker:
    def __init__(self, host, port):
        self.host = host
        self.port = port
        self.clients = {}
        self.messages_log = []
        self.running = True

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((self.host, self.port))
        self.sock.listen(5)
        self.sock.settimeout(1)
        print(f"Broker 监听: {self.host}:{self.port}")

    def accept_loop(self, timeout=15):
        start = time.time()
        while time.time() - start < timeout:
            try:
                conn, addr = self.sock.accept()
                print(f"客户端连接: {addr}")
                t = threading.Thread(target=self.handle_client, args=(conn, addr))
                t.daemon = True
                t.start()
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"Accept 错误: {e}")

    def handle_client(self, conn, addr):
        buf = b''
        try:
            while self.running:
                data = conn.recv(4096)
                if not data:
                    break
                buf += data
                while len(buf) >= 2:
                    pkt_type = (buf[0] & 0xF0) >> 4
                    rem_len = self._decode_remaining_length(buf)
                    pkt_len = 2 + rem_len
                    if len(buf) < pkt_len:
                        break
                    pkt = buf[:pkt_len]
                    buf = buf[pkt_len:]
                    self._process_packet(conn, addr, pkt_type, pkt)
        except Exception as e:
            print(f"客户端 {addr} 断开: {e}")
        finally:
            conn.close()

    def _decode_remaining_length(self, buf):
        multiplier = 1
        value = 0
        for i in range(1, min(5, len(buf))):
            b = buf[i]
            value += (b & 127) * multiplier
            if (b & 128) == 0:
                return value
            multiplier *= 128
        return 0

    def _process_packet(self, conn, addr, pkt_type, pkt):
        if pkt_type == 1:  # CONNECT
            print(f"  [MQTT] CONNECT from {addr}")
            # Send CONNACK (Session Present=0, Return Code=0)
            connack = bytes([0x20, 0x02, 0x00, 0x00])
            conn.sendall(connack)
            print(f"  [MQTT] -> CONNACK (accepted)")
        elif pkt_type == 3:  # PUBLISH
            topic_len = struct.unpack('>H', pkt[2:4])[0]
            topic = pkt[4:4+topic_len].decode('utf-8', errors='replace')
            payload = pkt[4+topic_len:]
            try:
                msg = json.loads(payload.decode('utf-8'))
                self.messages_log.append({
                    "topic": topic,
                    "data": msg,
                    "time": time.time()
                })
                short = json.dumps(msg, ensure_ascii=False)[:120]
                print(f"  [MQTT] PUBLISH {topic}: {short}")
            except Exception:
                print(f"  [MQTT] PUBLISH {topic}: {payload[:80]}")
        elif pkt_type == 8:  # SUBSCRIBE
            print(f"  [MQTT] SUBSCRIBE from {addr}")
            # SUBACK with 2 topic QoS grants
            msg_id = pkt[2:4]
            suback = bytes([0x90, 0x03]) + msg_id + bytes([0x00, 0x00])
            conn.sendall(suback)
            print(f"  [MQTT] -> SUBACK")
        elif pkt_type == 12:  # PINGREQ
            conn.sendall(bytes([0xD0, 0x00]))
            print(f"  [MQTT] PINGREQ -> PINGRESP")
        elif pkt_type == 14:  # DISCONNECT
            print(f"  [MQTT] DISCONNECT from {addr}")

broker = MiniMQTTBroker('0.0.0.0', MQTT_PORT)
broker.start()

print()
print(f"等待手机 SensorCollector 连接 (15秒超时)...")
print(f"手机端 Broker 地址应为: {pc_ip}:{MQTT_PORT}")
print()

broker.accept_loop(timeout=15)

print()
print("=" * 50)
print(f"=== MQTT 端到端测试结果 ===")
print(f"收到消息总数: {len(broker.messages_log)}")
print()
# 统计各 topic 的消息数
topic_counts = {}
for m in broker.messages_log:
    t = m["topic"]
    topic_counts[t] = topic_counts.get(t, 0) + 1
print("各 topic 消息数:")
for t, c in topic_counts.items():
    print(f"  {t}: {c} 条")

print()
print("最近 5 条消息:")
for m in broker.messages_log[-5:]:
    topic = m["topic"]
    data = json.dumps(m["data"], ensure_ascii=False)[:100]
    print(f"  [{topic}] {data}")
print("=" * 50)
