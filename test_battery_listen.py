#!/usr/bin/env python3
"""抓取电池推送 - 发命令后持续监听"""
import websocket
import json
import time

ws = websocket.create_connection('ws://127.0.0.1:17532', timeout=8)
ws.settimeout(0.5)

# 读取 welcome
try:
    r = ws.recv()
except:
    pass

# 发电池查询
ws.send(json.dumps({"type": "battery_query"}))
print("Sent battery_query, listening 5s...")

start = time.time()
found = False
while time.time() - start < 5:
    try:
        r = ws.recv()
        d = json.loads(r)
        t = d.get("type", "?")
        if "battery" in t.lower():
            print(f"  [{t}] {json.dumps(d, ensure_ascii=False)}")
            found = True
            break
        else:
            print(f"  [noise:{t}] skipped")
    except websocket.WebSocketTimeoutException:
        continue
    except Exception as e:
        print(f"  err: {e}")
        break

ws.close()
if not found:
    print("  No battery event received in 5s")
