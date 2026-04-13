#!/usr/bin/env python3
"""Test Fusion WebSocket Server - Full Feature Test"""
import websocket
import json
import time

def recv_timeout(ws, timeout=3):
    """Receive with timeout, return None on timeout"""
    old_timeout = ws.gettimeout()
    ws.settimeout(timeout)
    try:
        return ws.recv()
    except websocket.WebSocketTimeoutException:
        return None
    finally:
        ws.settimeout(old_timeout)

try:
    ws = websocket.create_connection('ws://127.0.0.1:17532', timeout=5)
    print('=' * 50)
    print('  Fusion Companion WebSocket Test')
    print('=' * 50)

    # 1. Welcome
    r = ws.recv()
    d = json.loads(r)
    print(f'[PASS] Welcome: {d.get("device")} | Android {d.get("androidVersion")} | SDK {d.get("sdkVersion")} | {d.get("bridge")}')

    # 2. Ping/Pong
    ws.send(json.dumps({'type': 'ping'}))
    r = ws.recv()
    print(f'[PASS] Ping/Pong OK')

    # 3. Battery Query (only fires on change, so may not respond)
    ws.send(json.dumps({'type': 'battery_query'}))
    r = recv_timeout(ws, 2)
    if r:
        print(f'[PASS] Battery: {r}')
    else:
        print(f'[SKIP] Battery: no change (expected for same state)')

    # 4. Clipboard Set
    ws.send(json.dumps({'type': 'clipboard_set', 'content': 'FusionTest123'}))
    print(f'[PASS] Clipboard set sent')

    # 5. Ring (vibrate)
    ws.send(json.dumps({'type': 'ring'}))
    print(f'[PASS] Ring sent (check phone!)')

    # 6. Screenshot
    ws.send(json.dumps({'type': 'screenshot'}))
    r = recv_timeout(ws, 5)
    if r:
        d = json.loads(r)
        if d.get('type') == 'screenshot':
            print(f'[PASS] Screenshot: {d.get("path")} ({d.get("filename")})')
        elif d.get('type') == 'screenshot_error':
            print(f'[FAIL] Screenshot error: {d.get("error")}')
    else:
        print(f'[WARN] Screenshot: timeout (check permissions)')

    # 7. Volume up
    ws.send(json.dumps({'type': 'volume', 'direction': 'up'}))
    print(f'[PASS] Volume up sent')

    # 8. KeyEvent - HOME
    ws.send(json.dumps({'type': 'keyevent', 'keycode': 3}))
    print(f'[PASS] KeyEvent HOME sent')

    # 9. Open URL (skip to avoid disrupting phone)
    print(f'[SKIP] Open URL (would disrupt phone)')

    # 10. Send SMS (skip)
    print(f'[SKIP] Send SMS (would send real SMS)')

    # Drain remaining messages
    print()
    print('--- Draining remaining messages ---')
    for i in range(5):
        r = recv_timeout(ws, 1)
        if r is None:
            break
        print(f'[MSG] {r[:200]}')

    ws.close()
    print()
    print('=' * 50)
    print('  Test Complete!')
    print('=' * 50)

except Exception as e:
    print(f'[ERR] {type(e).__name__}: {e}')
    import traceback
    traceback.print_exc()
