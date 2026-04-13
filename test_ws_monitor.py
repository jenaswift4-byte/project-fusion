#!/usr/bin/env python3
"""Monitor Fusion WS for 15 seconds, listen for push events"""
import websocket
import json
import time

try:
    ws = websocket.create_connection('ws://127.0.0.1:17532', timeout=15)
    print('Monitoring for push events (15s)...')
    print('Trigger a notification on your phone now!')
    print('-' * 40)
    
    # Receive welcome
    r = ws.recv()
    print(f'[Welcome] {r}')
    
    start = time.time()
    while time.time() - start < 15:
        try:
            r = ws.recv()
            d = json.loads(r)
            t = d.get('type', '?')
            print(f'[{t}] {json.dumps(d, ensure_ascii=False)[:300]}')
        except websocket.WebSocketTimeoutException:
            print('.')
            break
    
    ws.close()
    print('-' * 40)
    print('Done monitoring.')
except Exception as e:
    print(f'Error: {type(e).__name__}: {e}')
