"""静默测试 WebSocket 连接 - 不触发任何手机操作"""
import asyncio
import json

async def test():
    try:
        import websockets
        async with websockets.connect('ws://127.0.0.1:17532', ping_interval=None) as ws:
            print('Connected to WS 17532')
            for i in range(3):
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=5)
                    data = json.loads(msg)
                    t = data.get("type", "?")
                    print(f"[{i+1}] type={t}: {str(data)[:200]}")
                except asyncio.TimeoutError:
                    print(f"[{i+1}] No message (timeout 5s)")
            print("Done - no messages triggered")
    except ImportError:
        print("websockets not installed, using raw socket")
        import socket
        s = socket.socket()
        s.connect(('127.0.0.1', 17532))
        # Send WS upgrade
        s.sendall(b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
        s.settimeout(5)
        try:
            resp = s.recv(4096)
            print(f"Raw response: {resp[:200]}")
        except socket.timeout:
            print("No response (timeout)")
        s.close()
    except Exception as e:
        print(f"Error: {e}")

asyncio.run(test())
