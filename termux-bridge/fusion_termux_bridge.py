#!/usr/bin/env python3
"""
Fusion Termux Bridge — Termux 端 Python WebSocket Server
完全兼容 PC Bridge 的 WebSocket 协议，实现：
  - 剪贴板双向同步 (Termux:API)
  - 文件管理 (ADB/scp)
  - 通知转发 (需要伴侣 App)
  - 来电监听 (需要伴侣 App)

使用方式:
  1. Termux 安装依赖:
     pkg install python termux-api
     pip install websockets
  2. USB 连接手机，PC 端执行:
     adb forward tcp:17532 tcp:17532
  3. 运行:
     python fusion_termux_bridge.py
  4. PC Bridge 自动连接

协议 (与伴侣 App 完全兼容):
  Phone → PC:
    {"type": "clipboard", "source": "phone", "content": "...", "contentType": "text|url"}
    {"type": "pong", "timestamp": ...}
    {"type": "connected", "device": "...", "androidVersion": "..."}

  PC → Phone:
    {"type": "clipboard_set", "content": "..."}
    {"type": "open_url", "url": "..."}
    {"type": "ping"}
    {"type": "ring"}
"""

import asyncio
import json
import subprocess
import time
import os
import signal
import logging
import re
from pathlib import Path

# === 配置 ===
WS_PORT = 17532
CLIPBOARD_POLL_INTERVAL = 1.0  # 剪贴板轮询间隔（秒）
LOG_LEVEL = logging.INFO

# === 日志 ===
logging.basicConfig(
    level=LOG_LEVEL,
    format="[%(asctime)s] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("FusionTermux")

# === URL 检测 ===
URL_PATTERN = re.compile(r'https?://[^\s<>"\']+', re.IGNORECASE)


class TermuxAPI:
    """Termux:API 命令封装"""

    @staticmethod
    def clipboard_get() -> str:
        """获取剪贴板内容"""
        try:
            result = subprocess.run(
                ["termux-clipboard-get"],
                capture_output=True, text=True, timeout=5
            )
            if result.returncode == 0:
                return result.stdout
        except FileNotFoundError:
            logger.warning("termux-clipboard-get 未找到，请安装 termux-api")
        except subprocess.TimeoutExpired:
            logger.warning("读取剪贴板超时")
        except Exception as e:
            logger.error(f"读取剪贴板失败: {e}")
        return ""

    @staticmethod
    def clipboard_set(text: str) -> bool:
        """设置剪贴板内容"""
        try:
            result = subprocess.run(
                ["termux-clipboard-set"],
                input=text, text=True, timeout=5
            )
            return result.returncode == 0
        except FileNotFoundError:
            logger.warning("termux-clipboard-set 未找到，请安装 termux-api")
            return False
        except Exception as e:
            logger.error(f"设置剪贴板失败: {e}")
            return False

    @staticmethod
    def open_url(url: str) -> bool:
        """在浏览器中打开 URL"""
        try:
            result = subprocess.run(
                ["termux-open-url", url],
                capture_output=True, timeout=10
            )
            return result.returncode == 0
        except Exception as e:
            logger.error(f"打开 URL 失败: {e}")
            return False

    @staticmethod
    def ring() -> bool:
        """播放铃声"""
        try:
            subprocess.run(
                ["termux-media-player", "play",
                 "/system/media/audio/ringtones/default.ogg"],
                capture_output=True, timeout=5
            )
            # 5 秒后停止
            import threading
            def stop_later():
                time.sleep(5)
                subprocess.run(
                    ["termux-media-player", "stop"],
                    capture_output=True, timeout=3
                )
            threading.Thread(target=stop_later, daemon=True).start()
            return True
        except Exception as e:
            logger.error(f"播放铃声失败: {e}")
            return False

    @staticmethod
    def get_device_info() -> dict:
        """获取设备信息"""
        info = {
            "device": "Android (Termux)",
            "androidVersion": "",
            "sdkVersion": 0,
        }
        try:
            result = subprocess.run(
                ["getprop", "ro.product.model"],
                capture_output=True, text=True, timeout=3
            )
            if result.returncode == 0:
                info["device"] = result.stdout.strip() or "Android (Termux)"

            result = subprocess.run(
                ["getprop", "ro.build.version.release"],
                capture_output=True, text=True, timeout=3
            )
            if result.returncode == 0:
                info["androidVersion"] = result.stdout.strip()

            result = subprocess.run(
                ["getprop", "ro.build.version.sdk"],
                capture_output=True, text=True, timeout=3
            )
            if result.returncode == 0:
                info["sdkVersion"] = int(result.stdout.strip() or "0")
        except Exception:
            pass
        return info

    @staticmethod
    def has_termux_api() -> bool:
        """检查 termux-api 是否可用"""
        try:
            result = subprocess.run(
                ["which", "termux-clipboard-get"],
                capture_output=True, timeout=3
            )
            return result.returncode == 0
        except Exception:
            return False


class FusionTermuxBridge:
    """Fusion Termux WebSocket Server — 完全兼容 PC Bridge 协议"""

    def __init__(self, port: int = WS_PORT):
        self.port = port
        self.clients: set = set()
        self.api = TermuxAPI()
        self.last_clipboard = ""
        self._clipboard_task = None
        self._server = None
        self._running = False

    async def start(self):
        """启动 WebSocket Server"""
        # 检查 Termux:API
        if not self.api.has_termux_api():
            logger.warning("⚠ Termux:API 未安装! 剪贴板功能不可用")
            logger.warning("  安装: pkg install termux-api")
            logger.warning("  并在 Termux 外安装 Termux:API App (F-Droid)")

        # 获取初始剪贴板
        self.last_clipboard = self.api.clipboard_get()
        logger.info(f"初始剪贴板: {self.last_clipboard[:50]}..." if self.last_clipboard else "初始剪贴板: (空)")

        # 启动剪贴板监控
        self._clipboard_task = asyncio.create_task(self._clipboard_monitor())

        # 启动 WS Server
        self._running = True
        import websockets
        self._server = await websockets.serve(
            self._handle_client,
            "0.0.0.0",
            self.port,
            ping_interval=15,
            ping_timeout=10,
        )
        logger.info(f"🚀 Fusion Termux Bridge 已启动 — ws://0.0.0.0:{self.port}")
        logger.info(f"   PC 端: adb forward tcp:{self.port} tcp:{self.port}")

    async def stop(self):
        """停止服务"""
        self._running = False
        if self._clipboard_task:
            self._clipboard_task.cancel()
        if self._server:
            self._server.close()
            await self._server.wait_closed()
        logger.info("Fusion Termux Bridge 已停止")

    async def _handle_client(self, websocket, path=None):
        """处理客户端连接"""
        self.clients.add(websocket)
        remote = websocket.remote_address if websocket.remote_address else "unknown"
        logger.info(f"新连接: {remote}")

        # 等待欢迎消息判断客户端类型
        client_type = "unknown"  # "pc" or "companion"
        try:
            # 给客户端 3 秒发送欢迎/标识消息
            try:
                first_msg = await asyncio.wait_for(websocket.recv(), timeout=3.0)
                data = json.loads(first_msg)
                if data.get("type") == "connected":
                    client_type = "companion"
                    device = data.get("device", "未知")
                    logger.info(f"🔗 伴侣 App 已连接: {device}")
                elif data.get("type") == "companion_hello":
                    # 伴侣 App 主动标识
                    client_type = "companion"
                    logger.info("🔗 伴侣 App 已连接 (混合模式)")
                else:
                    # PC 客户端第一条消息不是 connected，正常
                    client_type = "pc"
                    # 处理这条消息
                    await self._process_message(websocket, first_msg, client_type)
            except asyncio.TimeoutError:
                # 没有收到欢迎消息，是 PC 客户端
                client_type = "pc"

            # PC 客户端 → 发送欢迎消息
            if client_type == "pc":
                device_info = self.api.get_device_info()
                welcome = {
                    "type": "connected",
                    "device": device_info["device"],
                    "androidVersion": device_info["androidVersion"],
                    "sdkVersion": device_info["sdkVersion"],
                    "bridge": "termux",
                }
                await websocket.send(json.dumps(welcome))
                logger.info(f"💻 PC 已连接: {remote}")

            # 标记客户端类型
            websocket.client_type = client_type

            # 继续处理后续消息
            async for message in websocket:
                await self._process_message(websocket, message, client_type)

        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            logger.error(f"客户端处理错误: {e}")
        finally:
            ctype = getattr(websocket, 'client_type', 'unknown')
            logger.info(f"连接断开: {remote} ({ctype})")
            self.clients.discard(websocket)

    async def _process_message(self, websocket, message: str, client_type: str = "pc"):
        """处理消息 — 区分 PC 客户端和伴侣 App"""
        try:
            data = json.loads(message)
            msg_type = data.get("type", "")

            if client_type == "companion":
                # 伴侣 App 发来的事件 (通知/来电/剪贴板) → 转发给所有 PC 客户端
                await self._broadcast_to_pc(message)
                logger.info(f"[伴侣→PC] {msg_type}: {str(data)[:60]}")
                return

            # PC 客户端发来的命令
            if msg_type == "clipboard_set":
                # PC → Phone 设置剪贴板
                content = data.get("content", "")
                if content:
                    # 在线程池中执行同步的 Termux API 调用
                    loop = asyncio.get_event_loop()
                    success = await loop.run_in_executor(None, self.api.clipboard_set, content)
                    if success:
                        self.last_clipboard = content  # 防止回弹
                        logger.info(f"[剪贴板] PC→Phone: {content[:40]}")
                    else:
                        logger.warning(f"[剪贴板] PC→Phone 设置失败")

            elif msg_type == "open_url":
                url = data.get("url", "")
                if url:
                    loop = asyncio.get_event_loop()
                    await loop.run_in_executor(None, self.api.open_url, url)
                    logger.info(f"[URL] 打开: {url[:60]}")

            elif msg_type == "ping":
                pong = {
                    "type": "pong",
                    "timestamp": int(time.time() * 1000),
                }
                await websocket.send(json.dumps(pong))

            elif msg_type == "ring":
                loop = asyncio.get_event_loop()
                await loop.run_in_executor(None, self.api.ring)
                logger.info("[铃声] 播放铃声 5 秒")

            else:
                logger.debug(f"未知消息类型: {msg_type}")

        except json.JSONDecodeError:
            logger.warning(f"收到非 JSON 消息: {message[:100]}")
        except Exception as e:
            logger.error(f"处理消息失败: {e}")

    async def _clipboard_monitor(self):
        """剪贴板监控循环 — 检测手机剪贴板变化并推送到 PC"""
        logger.info("📋 剪贴板监控已启动")
        while self._running:
            try:
                # 在线程池中执行同步的 Termux API 调用
                loop = asyncio.get_event_loop()
                current = await loop.run_in_executor(None, self.api.clipboard_get)

                if current and current != self.last_clipboard:
                    self.last_clipboard = current

                    # 检测内容类型
                    content_type = "text"
                    if URL_PATTERN.match(current.strip()):
                        content_type = "url"

                    # 广播到所有 PC 客户端
                    msg = {
                        "type": "clipboard",
                        "source": "phone",
                        "content": current,
                        "contentType": content_type,
                    }
                    msg_json = json.dumps(msg)
                    await self._broadcast(msg_json)
                    logger.info(f"[剪贴板] Phone→PC: {current[:40]}")

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"剪贴板监控错误: {e}")

            await asyncio.sleep(CLIPBOARD_POLL_INTERVAL)

    async def _broadcast(self, message: str):
        """广播消息到所有客户端"""
        if not self.clients:
            return
        # 复制集合避免迭代中修改
        dead_clients = set()
        for client in list(self.clients):
            try:
                await client.send(message)
            except Exception:
                dead_clients.add(client)
        # 清理断开的客户端
        self.clients -= dead_clients

    async def _broadcast_to_pc(self, message: str):
        """只广播给 PC 客户端 (跳过伴侣 App)"""
        if not self.clients:
            return
        dead_clients = set()
        for client in list(self.clients):
            ctype = getattr(client, 'client_type', 'pc')
            if ctype == "companion":
                continue  # 跳过伴侣 App
            try:
                await client.send(message)
            except Exception:
                dead_clients.add(client)
        self.clients -= dead_clients


async def main():
    bridge = FusionTermuxBridge(port=WS_PORT)

    # 优雅关闭
    loop = asyncio.get_event_loop()

    def signal_handler():
        logger.info("收到关闭信号...")
        asyncio.create_task(bridge.stop())

    # 注册信号处理 (Termux 支持 SIGINT/SIGTERM)
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, signal_handler)
        except NotImplementedError:
            pass  # Windows 不支持，忽略

    await bridge.start()

    # 保持运行
    try:
        while bridge._running:
            await asyncio.sleep(1)
    except KeyboardInterrupt:
        logger.info("用户中断")
    finally:
        await bridge.stop()


if __name__ == "__main__":
    print("""
╔══════════════════════════════════════╗
║   Fusion Termux Bridge v1.0         ║
║   Project Fusion - 万物互联          ║
╠══════════════════════════════════════╣
║  协议: 与伴侣 App 完全兼容           ║
║  功能: 剪贴板同步 / URL打开 / 铃声   ║
║  需要: Termux:API + websockets       ║
╚══════════════════════════════════════╝
    """)
    asyncio.run(main())
