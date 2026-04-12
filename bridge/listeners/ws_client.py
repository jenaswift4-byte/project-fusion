"""
WebSocket 客户端
通过 ADB forward 连接 Android 伴侣 App 的 WebSocket Server
实现双向实时通信，替代所有 ADB shell 轮询

连接流程:
  1. adb forward tcp:17532 tcp:17532
  2. websocket-client 连接 ws://127.0.0.1:17532
  3. 接收 JSON 事件 (通知/剪贴板/通话)
  4. 发送 JSON 命令 (设置剪贴板/打开链接/ping/响铃)

协议:
  Phone → PC:
    {"type": "notification", "package": "...", "title": "...", "text": "..."}
    {"type": "clipboard", "source": "phone", "content": "...", "contentType": "text|url"}
    {"type": "telephony", "state": "RINGING|OFFHOOK|IDLE"}
    {"type": "pong", "timestamp": ...}
    {"type": "connected", "device": "...", "androidVersion": "..."}

  PC → Phone:
    {"type": "clipboard_set", "content": "..."}
    {"type": "open_url", "url": "..."}
    {"type": "ping"}
    {"type": "ring"}
"""

import json
import subprocess
import threading
import time
import logging
from typing import Callable, Optional

logger = logging.getLogger(__name__)


class FusionWSClient:
    """Fusion WebSocket 客户端"""

    def __init__(self, config: dict):
        self.config = config
        self.port = 17532
        self.ws = None
        self.running = False
        self._connected = False
        self._reconnect_thread = None
        self._heartbeat_thread = None
        self._handlers = {}
        self._adb_path = config.get("adb", {}).get("path", "adb")
        self._heartbeat_interval = 15  # 15秒心跳

    def on(self, event_type: str, handler: Callable):
        """注册事件处理器"""
        self._handlers.setdefault(event_type, []).append(handler)

    def start(self) -> bool:
        """启动 WebSocket 客户端"""
        try:
            import websocket
        except ImportError:
            logger.warning("websocket-client 未安装，无法使用实时通信")
            logger.warning("安装: pip install websocket-client")
            return False

        # 建立 ADB forward
        if not self._setup_adb_forward():
            logger.warning("ADB forward 设置失败")
            return False

        self.running = True
        self._reconnect_thread = threading.Thread(target=self._connect_loop, daemon=True)
        self._reconnect_thread.start()

        # 启动心跳线程
        self._heartbeat_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self._heartbeat_thread.start()

        return True

    def stop(self):
        """停止客户端"""
        self.running = False
        if self.ws:
            try:
                self.ws.close()
            except Exception:
                pass

    def send(self, data: dict) -> bool:
        """发送数据到手机"""
        if not self._connected or not self.ws:
            return False

        try:
            self.ws.send(json.dumps(data))
            return True
        except Exception as e:
            logger.error(f"发送数据失败: {e}")
            self._connected = False
            return False

    def set_clipboard(self, text: str) -> bool:
        """设置手机剪贴板"""
        return self.send({"type": "clipboard_set", "content": text})

    def open_url(self, url: str) -> bool:
        """在手机上打开链接"""
        return self.send({"type": "open_url", "url": url})

    def ping(self) -> bool:
        """发送 ping"""
        return self.send({"type": "ping"})

    def ring(self) -> bool:
        """让手机响铃"""
        return self.send({"type": "ring"})

    @property
    def connected(self) -> bool:
        return self._connected

    # === 内部方法 ===

    def _setup_adb_forward(self) -> bool:
        """设置 ADB 端口转发"""
        try:
            # 先移除旧的 forward
            subprocess.run(
                [self._adb_path, "forward", "--remove", f"tcp:{self.port}"],
                capture_output=True, timeout=10,
            )

            # 获取设备列表，优先用 USB 设备 (非 IP 地址的)
            result = subprocess.run(
                [self._adb_path, "devices"],
                capture_output=True, text=True, timeout=10,
            )
            serial = None
            for line in result.stdout.strip().split("\n")[1:]:
                parts = line.strip().split("\t")
                if len(parts) == 2 and parts[1] == "device":
                    dev_id = parts[0]
                    # 优先 USB (不含冒号的)，其次 WiFi (含冒号的)
                    if "." not in dev_id:
                        serial = dev_id
                        break
                    elif serial is None:
                        serial = dev_id

            # 构建命令
            cmd = [self._adb_path]
            if serial:
                cmd += ["-s", serial]
            cmd += ["forward", f"tcp:{self.port}", f"tcp:{self.port}"]

            result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            if result.returncode != 0:
                logger.error(f"ADB forward 失败: {result.stderr.strip()}")
                return False

            logger.info(f"ADB forward 已设置: tcp:{self.port} -> tcp:{self.port} (device: {serial or 'default'})")
            return True
        except Exception as e:
            logger.error(f"ADB forward 设置失败: {e}")
            return False

    def _connect_loop(self):
        """自动重连循环"""
        import websocket

        while self.running:
            try:
                if not self._connected:
                    # 重连时重新设置 ADB forward
                    self._setup_adb_forward()
                    self._connect(websocket)
            except Exception as e:
                logger.debug(f"连接错误: {e}")
                self._connected = False

            time.sleep(2)  # 重连间隔

    def _heartbeat_loop(self):
        """心跳循环 — 定时发送 ping 检测连接存活"""
        while self.running:
            time.sleep(self._heartbeat_interval)
            if self._connected:
                try:
                    if not self.ping():
                        logger.debug("心跳失败，标记为断开")
                        self._connected = False
                except Exception:
                    self._connected = False

    def _connect(self, ws_module):
        """建立连接"""
        ws_url = f"ws://127.0.0.1:{self.port}"

        self.ws = ws_module.WebSocketApp(
            ws_url,
            on_open=self._on_open,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close,
        )

        # 在独立线程运行 (非阻塞)
        wst = threading.Thread(target=self.ws.run_forever, daemon=True)
        wst.start()

    def _on_open(self, ws):
        """连接成功"""
        self._connected = True
        logger.info("已连接到 Fusion Companion (WebSocket)")

    def _on_message(self, ws, message):
        """收到消息"""
        try:
            data = json.loads(message)
            event_type = data.get("type", "")

            # 调用对应处理器
            for handler in self._handlers.get(event_type, []):
                try:
                    handler(data)
                except Exception as e:
                    logger.error(f"事件处理器错误 [{event_type}]: {e}")

            # 调用通用处理器
            for handler in self._handlers.get("*", []):
                try:
                    handler(data)
                except Exception:
                    pass

        except json.JSONDecodeError:
            logger.warning(f"收到非 JSON 消息: {message[:100]}")

    def _on_error(self, ws, error):
        """连接错误"""
        logger.debug(f"WebSocket 错误: {error}")
        self._connected = False

    def _on_close(self, ws, close_status_code, close_msg):
        """连接关闭"""
        self._connected = False
        logger.debug("WebSocket 连接已关闭")
