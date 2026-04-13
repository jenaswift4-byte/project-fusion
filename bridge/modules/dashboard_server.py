"""
Fusion Dashboard Web Server
为 PC 端传感器仪表盘提供 HTTP + WebSocket 服务

功能:
  - 静态文件服务 (HTML/CSS/JS)
  - WebSocket 推送实时传感器数据
  - REST API 查询历史数据
  - 自动从 MQTT Bridge 获取数据
"""

import json
import time
import logging
import threading
from http.server import HTTPServer, SimpleHTTPRequestHandler
from socketserver import ThreadingMixIn
from typing import Dict, List, Optional, Any
from pathlib import Path
import urllib.parse

logger = logging.getLogger(__name__)

# 尝试导入 WebSocket 支持
try:
    import websockets
    import asyncio
    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False
    logger.warning("websockets 未安装，Dashboard 将仅支持 HTTP 轮询模式")


class DashboardDataStore:
    """仪表盘数据存储"""

    def __init__(self):
        self.sensor_data: Dict[str, Dict[str, Any]] = {}  # device_id -> {sensor_type -> {value, unit, timestamp}}
        self.device_status: Dict[str, Dict[str, Any]] = {}  # device_id -> {online, battery, last_seen}
        self.message_log: List[Dict[str, Any]] = []
        self._max_log = 1000
        self._ws_clients: set = set()
        self._lock = threading.Lock()

    def update_sensor(self, device_id: str, sensor_type: str, value: float, unit: str, timestamp: float):
        """更新传感器数据"""
        with self._lock:
            if device_id not in self.sensor_data:
                self.sensor_data[device_id] = {}
            self.sensor_data[device_id][sensor_type] = {
                "value": value,
                "unit": unit,
                "timestamp": timestamp,
            }

            self.message_log.append({
                "type": "sensor",
                "device_id": device_id,
                "sensor_type": sensor_type,
                "value": value,
                "unit": unit,
                "timestamp": timestamp,
            })
            if len(self.message_log) > self._max_log:
                self.message_log = self.message_log[-self._max_log:]

        # 推送到 WebSocket 客户端
        self._ws_broadcast({
            "type": "sensor",
            "device_id": device_id,
            "sensor": sensor_type,
            "value": value,
            "unit": unit,
            "ts": timestamp,
        })

    def update_device_status(self, device_id: str, online: bool, battery: int = -1):
        """更新设备状态"""
        with self._lock:
            self.device_status[device_id] = {
                "online": online,
                "battery": battery,
                "last_seen": time.time(),
            }

            self.message_log.append({
                "type": "device_status",
                "device_id": device_id,
                "online": online,
                "battery": battery,
                "timestamp": time.time(),
            })
            if len(self.message_log) > self._max_log:
                self.message_log = self.message_log[-self._max_log:]

        self._ws_broadcast({
            "type": "device_status",
            "device_id": device_id,
            "online": online,
            "battery": battery,
            "ts": time.time(),
        })

    def push_alert(self, alert_source: str, alert_type: str, value: float, message: str = ""):
        """推送告警事件到 Dashboard"""
        alert_data = {
            "type": "alert",
            "source": alert_source,
            "alert_type": alert_type,
            "value": value,
            "message": message,
            "ts": time.time(),
        }
        with self._lock:
            self.message_log.append(alert_data)
            if len(self.message_log) > self._max_log:
                self.message_log = self.message_log[-self._max_log:]
        self._ws_broadcast(alert_data)

    def get_all_sensor_data(self) -> Dict[str, Any]:
        """获取所有传感器数据"""
        with self._lock:
            return {
                "sensors": self.sensor_data,
                "devices": self.device_status,
                "timestamp": time.time(),
            }

    def get_messages(self, count: int = 100) -> List[Dict[str, Any]]:
        """获取最近消息"""
        with self._lock:
            return self.message_log[-count:]

    def add_ws_client(self, ws):
        """添加 WebSocket 客户端"""
        with self._lock:
            self._ws_clients.add(ws)

    def remove_ws_client(self, ws):
        """移除 WebSocket 客户端"""
        with self._lock:
            self._ws_clients.discard(ws)

    def _ws_broadcast(self, data: dict):
        """广播到所有 WebSocket 客户端"""
        if not self._ws_clients:
            return
        message = json.dumps(data, ensure_ascii=False)
        dead = set()
        for ws in list(self._ws_clients):
            try:
                if HAS_WEBSOCKETS:
                    asyncio.create_task(ws.send(message))
                else:
                    ws.send(message)
            except Exception:
                dead.add(ws)
        self._ws_clients -= dead


# 全局数据存储
data_store = DashboardDataStore()


class DashboardHTTPRequestHandler(SimpleHTTPRequestHandler):
    """Dashboard HTTP 请求处理器"""

    def __init__(self, *args, dashboard_dir: str = None, daemon=None, **kwargs):
        self.dashboard_dir = dashboard_dir
        self.daemon = daemon
        super().__init__(*args, directory=dashboard_dir or ".", **kwargs)

    def log_message(self, format, *args):
        logger.debug(f"[Dashboard HTTP] {args[0]}")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        # API 路由
        if path == "/api/sensors":
            self._send_json(data_store.get_all_sensor_data())
            return
        elif path == "/api/messages":
            count = int(urllib.parse.parse_qs(parsed.query).get("count", [100])[0])
            self._send_json(data_store.get_messages(count))
            return
        elif path == "/api/devices":
            with data_store._lock:
                self._send_json(data_store.device_status)
            return
        elif path == "/api/status":
            status_data = {
                "status": "ok",
                "uptime": time.time(),
                "ws_clients": len(data_store._ws_clients),
                "mqtt_connected": True,
            }
            # 声音监测数据
            if self.daemon:
                try:
                    sound_status = self.daemon.sound_monitor.get_status()
                    status_data["sound_db"] = sound_status.get("current_db", -100)
                    status_data["sound_alert"] = sound_status.get("alert_active", False)
                except Exception:
                    pass
                # 算力数据
                try:
                    compute_status = self.daemon.distributed_scheduler.get_status()
                    status_data["compute"] = compute_status
                except Exception:
                    pass
                # 摄像头数据
                try:
                    cam_status = self.daemon.multi_scrcpy.get_status()
                    status_data["cameras"] = cam_status
                except Exception:
                    pass
                # 注册设备
                try:
                    reg_devices = self.daemon.command_bridge.get_all_devices()
                    status_data["registered_devices"] = reg_devices
                except Exception:
                    pass
            self._send_json(status_data)
            return

        # 静态文件
        super().do_GET()

    def _send_json(self, data: Any):
        """发送 JSON 响应"""
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False, default=str).encode("utf-8"))

    def do_POST(self):
        """处理 POST 请求"""
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/api/command":
            try:
                content_length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(content_length)
                data = json.loads(body)
                target = data.get("target", "")
                action = data.get("action", "")
                params = data.get("params", {})

                if self.daemon:
                    if target == "broadcast":
                        self.daemon.command_bridge.broadcast_command(action, params)
                    else:
                        self.daemon.command_bridge.send_command(target, action, params)
                    self._send_json({"ok": True, "status": "sent"})
                else:
                    self._send_json({"ok": False, "error": "daemon not available"})
            except Exception as e:
                self._send_json({"ok": False, "error": str(e)})
            return

        elif path == "/api/scene":
            try:
                content_length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(content_length)
                data = json.loads(body)
                scene_name = data.get("scene", "")
                if self.daemon:
                    ok = self.daemon.command_bridge.activate_scene(scene_name)
                    self._send_json({"ok": ok})
                else:
                    self._send_json({"ok": False, "error": "daemon not available"})
            except Exception as e:
                self._send_json({"ok": False, "error": str(e)})
            return

        elif path == "/api/sound/threshold":
            try:
                content_length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(content_length)
                data = json.loads(body)
                threshold = data.get("threshold", 80)
                if self.daemon:
                    self.daemon.sound_monitor.set_alert_threshold(float(threshold))
                    self._send_json({"ok": True, "threshold": threshold})
                else:
                    self._send_json({"ok": False, "error": "daemon not available"})
            except Exception as e:
                self._send_json({"ok": False, "error": str(e)})
            return

        self.send_response(404)
        self.end_headers()


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    """多线程 HTTP 服务器"""
    daemon_threads = True
    allow_reuse_address = True


async def _ws_handler(websocket, path=None):
    """WebSocket 处理器"""
    logger.info(f"[Dashboard WS] 新客户端连接")
    data_store.add_ws_client(websocket)
    try:
        # 发送当前全部数据
        await websocket.send(json.dumps({
            "type": "init",
            **data_store.get_all_sensor_data(),
        }))
        # 保持连接
        async for message in websocket:
            try:
                data = json.loads(message)
                if data.get("type") == "command":
                    logger.info(f"[Dashboard WS] 收到命令: {data.get('command')}")
            except json.JSONDecodeError:
                pass
    except Exception as e:
        logger.debug(f"[Dashboard WS] 客户端断开: {e}")
    finally:
        data_store.remove_ws_client(websocket)


class DashboardServer:
    """
    Dashboard Web 服务器

    启动方式:
      server = DashboardServer(port=8080, dashboard_dir="dashboard")
      server.start()
    """

    def __init__(self, port: int = 8080, dashboard_dir: str = None, host: str = "0.0.0.0"):
        self.port = port
        self.host = host
        self.dashboard_dir = dashboard_dir
        self.running = False
        self.http_server: Optional[ThreadingHTTPServer] = None
        self.ws_server = None
        self._ws_thread: Optional[threading.Thread] = None
        # Bridge daemon reference (set via set_bridges)
        self._daemon = None

    def set_bridges(self, daemon):
        """设置 BridgeDaemon 引用，用于获取算力/摄像头/声音等数据"""
        self._daemon = daemon

    def start(self) -> bool:
        """启动 Dashboard 服务器"""
        if self.running:
            return True

        try:
            # 启动 HTTP 服务器
            handler = lambda *args, **kwargs: DashboardHTTPRequestHandler(
                *args, dashboard_dir=self.dashboard_dir, daemon=self._daemon, **kwargs
            )
            self.http_server = ThreadingHTTPServer((self.host, self.port), handler)
            http_thread = threading.Thread(target=self.http_server.serve_forever, daemon=True)
            http_thread.start()

            # 启动 WebSocket 服务器
            if HAS_WEBSOCKETS:
                self._ws_thread = threading.Thread(target=self._run_ws_server, daemon=True)
                self._ws_thread.start()

            self.running = True
            logger.info(f"[Dashboard] 已启动 - HTTP: http://{self.host}:{self.port}"
                       + (f", WS: ws://{self.host}:{self.port + 1}" if HAS_WEBSOCKETS else ""))
            return True

        except Exception as e:
            logger.error(f"[Dashboard] 启动失败: {e}")
            return False

    def _run_ws_server(self):
        """运行 WebSocket 服务器 (独立线程，内部使用 asyncio)"""
        ws_port = self.port + 1
        try:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            start_server = websockets.serve(_ws_handler, self.host, ws_port)
            loop.run_until_complete(start_server)
            loop.run_forever()
        except Exception as e:
            logger.error(f"[Dashboard WS] 启动失败: {e}")

    def stop(self):
        """停止服务器"""
        self.running = False
        if self.http_server:
            self.http_server.shutdown()
        logger.info("[Dashboard] 已停止")

    def update_sensor(self, device_id: str, sensor_type: str, value: float, unit: str, timestamp: float = None):
        """更新传感器数据 (供 MQTT Bridge 回调调用)"""
        data_store.update_sensor(device_id, sensor_type, value, unit, timestamp or time.time())

    def update_device_status(self, device_id: str, online: bool, battery: int = -1):
        """更新设备状态"""
        data_store.update_device_status(device_id, online, battery)

    def push_alert(self, alert_source: str, alert_type: str, value: float, message: str = ""):
        """推送告警事件"""
        data_store.push_alert(alert_source, alert_type, value, message)
