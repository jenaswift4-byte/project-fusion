import paho.mqtt.client as mqtt
import time
import json

# 创建 MQTT 客户端
client = mqtt.Client()

# 连接到本地 Broker
client.connect('127.0.0.1', 1883, 60)

# 发送停止命令到所有设备
for device_id in ["device-mi-8-6560", "7254adb5", "fusion"]:
    topic = f"fusion/audio/{device_id}/command"
    payload = json.dumps({"action": "stop_mic"})
    print(f"发送停止命令到 {topic}")
    client.publish(topic, payload)
    time.sleep(0.3)

client.disconnect()
print("已发送停止录音命令！")