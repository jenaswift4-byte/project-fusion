import paho.mqtt.client as mqtt
import json, sys

try:
    client = mqtt.Client(callback_api_version=mqtt.CallbackAPIVersion.VERSION2)
    client.connect('192.168.42.244', 1883, keepalive=60)
    result = client.publish('fusion/audio/command', json.dumps({'action': 'start_mic'}), qos=1)
    result.wait_for_publish(timeout=2)
    print(f'Published to fusion/audio/command: {result.rc}')
    client.disconnect()
    print('Done')
except Exception as e:
    print(f'Error: {type(e).__name__}: {e}', file=sys.stderr)
    sys.exit(1)
