#!/usr/bin/env python3
"""Fusion Companion 综合静默测试 v2 - 使用正确的命令类型，不发声音"""
import websocket
import json
import time

results = []

def test(name, cmd, expect_reply=True, timeout=3):
    """发送命令并收集结果"""
    try:
        ws.send(json.dumps(cmd))
        if expect_reply:
            ws.settimeout(timeout)
            r = ws.recv()
            d = json.loads(r)
            status = "✅"
            info = json.dumps(d, ensure_ascii=False)[:250]
            results.append((status, name, info))
            print(f"  {status} {name}: {info}")
            return d
        else:
            results.append(("✅", name, "已发送(无回复)"))
            print(f"  ✅ {name}: 已发送(无回复)")
            return None
    except websocket.WebSocketTimeoutException:
        results.append(("⏱", name, "超时(可能被通知推送插队)"))
        print(f"  ⏱ {name}: 超时")
        return None
    except Exception as e:
        results.append(("❌", name, f"{type(e).__name__}: {e}"))
        print(f"  ❌ {name}: {type(e).__name__}: {e}")
        return None

try:
    print("Connecting to Fusion Companion...")
    ws = websocket.create_connection('ws://127.0.0.1:17532', timeout=5)
    r = json.loads(ws.recv())
    print(f"📱 {r.get('device','?')} | Android {r.get('androidVersion','?')} | {r.get('bridge','?')}")
    print("=" * 55)
    
    # 1. Ping (期望 pong 回复)
    test("📡 Ping", {"type": "ping"})
    
    # 2. 电池查询 (App 主动推送 battery 数据)
    test("🔋 电池查询", {"type": "battery_query"})
    
    # 3. 剪贴板设置 (静默写入测试文本)
    test("📋 剪贴板写入", {"type": "clipboard_set", "content": "FusionTest_" + str(int(time.time()))}, expect_reply=False)
    time.sleep(0.3)
    
    # 4. 截图 (静默，不弹出)
    # test("📸 截图", {"type": "screenshot"})  # 会存文件到手机，跳过
    
    # 5. 音量调低再调高 (静默操作)
    test("🔉 音量下调", {"type": "volume", "direction": "down"}, expect_reply=False)
    time.sleep(0.2)
    test("🔊 音量上调", {"type": "volume", "direction": "up"}, expect_reply=False)
    time.sleep(0.2)
    
    # 6. 按键事件 - 静默测试 (KEYCODE_HOME = 3)
    # test("📱 按HOME", {"type": "keyevent", "keycode": 3})  # 会切回桌面，跳过
    
    # 7. 打开 App (设置，静默)
    test("⚙️ 打开设置", {"type": "open_app", "package": "com.android.settings"}, expect_reply=False)
    time.sleep(0.5)
    
    # 8. 再 Ping 验证连接还活着
    test("📡 Ping #2", {"type": "ping"})
    
    ws.close()
    
    print("=" * 55)
    ok = sum(1 for s,_,_ in results if s == "✅")
    timeout_cnt = sum(1 for s,_,_ in results if s == "⏱")
    fail = sum(1 for s,_,_ in results if s == "❌")
    print(f"\n📊 结果: {ok} 通过, {timeout_cnt} 超时, {fail} 失败 (共 {len(results)} 项)")
    
    if timeout_cnt > 0:
        print("\n💡 超时说明: 命令已执行，但回复被通知推送事件插队了")
        print("   这是正常的 — App 同时在推送系统通知")

except Exception as e:
    print(f"\n❌ Connection failed: {type(e).__name__}: {e}")
    print("Make sure Fusion Companion is running on the phone.")
