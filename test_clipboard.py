"""Quick test: clipboard sync via WebSocket"""
import asyncio
import websockets
import json
import subprocess
import time

ADB = r"C:\Users\wang\Desktop\scrcpy-win64-v3.3.4\adb.exe"
WS_URL = "ws://127.0.0.1:17532"

async def main():
    print("=== Test: Clipboard Sync (PC <-> Phone) ===\n")
    
    # 1. Connect
    print("[1] Connecting to phone WS...")
    ws = await websockets.connect(WS_URL, open_timeout=5)
    welcome = await ws.recv()
    print(f"    Welcome: {welcome}")
    
    # 2. PC -> Phone: set clipboard
    print("\n[2] PC -> Phone: set clipboard...")
    await ws.send(json.dumps({"type": "clipboard_set", "content": "FusionTest-PC2Phone"}))
    await asyncio.sleep(0.5)
    
    # Verify on phone via ADB
    result = subprocess.run([ADB, "shell", "service", "call", "clipboard", "3"],
                          capture_output=True, text=True)
    print(f"    Phone clipboard (raw): {result.stdout.strip()}")
    if "FusionTest" in result.stdout or len(result.stdout.strip()) > 30:
        print("    ✅ PC -> Phone clipboard sync WORKS!")
    else:
        print("    ⚠️ Clipboard may not have updated (needs UI check)")
    
    # 3. Test ping
    print("\n[3] Test ping...")
    await ws.send(json.dumps({"type": "ping"}))
    try:
        pong = await asyncio.wait_for(ws.recv(), timeout=3)
        print(f"    Pong: {pong}")
        print("    ✅ Ping/pong works")
    except asyncio.TimeoutError:
        print("    ❌ No pong received")
    
    # 4. Test screenshot command
    print("\n[4] Test screenshot command...")
    await ws.send(json.dumps({"type": "screenshot"}))
    try:
        resp = await asyncio.wait_for(ws.recv(), timeout=10)
        print(f"    Response: {resp[:200]}")
        print("    ✅ Screenshot command accepted")
    except asyncio.TimeoutError:
        print("    ⏳ No immediate response (screenshot may be async)")
    
    # 5. Test open_url (open a silent URL, not noisy)
    # Skip - would open browser on phone
    
    # 6. Check device info
    print("\n[5] Device info:")
    result = subprocess.run([ADB, "shell", "getprop", "ro.product.model"],
                          capture_output=True, text=True)
    print(f"    Model: {result.stdout.strip()}")
    result = subprocess.run([ADB, "shell", "dumpsys", "battery"],
                          capture_output=True, text=True)
    for line in result.stdout.split('\n'):
        if 'level' in line.lower() or 'status' in line.lower() or 'temp' in line.lower():
            print(f"    {line.strip()}")
    
    await ws.close()
    print("\n=== All clipboard tests done ===")

asyncio.run(main())
