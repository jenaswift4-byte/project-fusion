#!/usr/bin/env python3
"""逐项检查 Fusion WS 功能"""
import websocket
import json
import time

RESULTS = {}

def ws_connect():
    ws = websocket.create_connection('ws://127.0.0.1:17532', timeout=5)
    ws.settimeout(1.0)
    try:
        r = ws.recv()
        d = json.loads(r)
        print(f"  [connected] device={d.get('device')} android={d.get('androidVersion')}")
    except:
        pass
    return ws

def recv_until(ws, expected_type, timeout=3):
    start = time.time()
    while time.time() - start < timeout:
        try:
            r = ws.recv()
            d = json.loads(r)
            t = d.get("type", "")
            if expected_type in t:
                return d
        except websocket.WebSocketTimeoutException:
            continue
        except Exception as e:
            return None
    return None

def test(name, ws, cmd, expect_type, timeout=3):
    try:
        ws.send(json.dumps(cmd))
        r = recv_until(ws, expect_type, timeout)
        if r:
            print(f"  ✅ {name}: {json.dumps(r, ensure_ascii=False)[:120]}")
            RESULTS[name] = "✅"
        else:
            print(f"  ⚠️  {name}: no response (sent {cmd.get('type')})")
            RESULTS[name] = "⚠️ no response"
    except Exception as e:
        print(f"  ❌ {name}: {e}")
        RESULTS[name] = f"❌ {e}"

print("=" * 50)
print("Fusion Companion 功能验收测试")
print("=" * 50)

# ── 1. 连接 ──
print("\n[1] WebSocket 连接")
try:
    ws = ws_connect()
    RESULTS["WS连接"] = "✅"
except Exception as e:
    print(f"  ❌ 连接失败: {e}")
    exit(1)

# ── 2. Ping ──
print("\n[2] Ping/Pong")
test("Ping", ws, {"type": "ping"}, "pong")

# ── 3. 剪贴板读取 ──
print("\n[3] 剪贴板读取 (手机→PC)")
test("剪贴板读取", ws, {"type": "clipboard_get"}, "clipboard", timeout=4)

# ── 4. 剪贴板写入 ──
print("\n[4] 剪贴板写入 (PC→手机)")
test("剪贴板写入", ws, {"type": "clipboard_set", "text": "FusionTest_" + str(int(time.time()))}, "clipboard_ack", timeout=4)

# ── 5. 短信列表 ──
print("\n[5] 短信读取")
test("短信读取", ws, {"type": "sms_query", "limit": 3}, "sms", timeout=5)

# ── 6. 应用列表 ──
print("\n[6] 打开设置 App")
test("打开设置", ws, {"type": "open_app", "package": "com.android.settings"}, "ack", timeout=3)

# ── 7. 音量查询 ──
print("\n[7] 音量控制 (静默调节)")
test("音量控制", ws, {"type": "volume", "action": "get"}, "volume", timeout=3)

# ── 8. 通话状态 ──
print("\n[8] 通话状态查询")
test("通话状态", ws, {"type": "call_state_query"}, "call_state", timeout=3)

# ── 9. 设备信息 ──
print("\n[9] 设备信息")
test("设备信息", ws, {"type": "device_info"}, "device_info", timeout=3)

ws.close()

print("\n" + "=" * 50)
print("测试结果汇总:")
for k, v in RESULTS.items():
    print(f"  {v} {k}")
print("=" * 50)
