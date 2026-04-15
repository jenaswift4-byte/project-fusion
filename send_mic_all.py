import paho.mqtt.client as mqtt
import time
import json

# 创建 MQTT 客户端
client = mqtt.Client()

# 连接到本地 Broker
client.connect('127.0.0.1', 1883, 60)

# 发送录音命令到所有可能的设备 ID
for device_id in ["device-mi-8-6560", "7254adb5", "fusion"]:
    topic = f"fusion/audio/{device_id}/command"
    payload = json.dumps({"action": "start_mic"})
    print(f"发送命令到 {topic}: {payload}")
    client.publish(topic, payload)
    time.sleep(0.5)

# 等待录音
time.sleep(5)

# 发送停止命令
for device_id in ["device-mi-8-6560", "7254adb5", "fusion"]:
    topic = f"fusion/audio/{device_id}/command"
    payload = json.dumps({"action": "stop_mic"})
    print(f"发送停止命令: {payload}")
    client.publish(topic, payload)
    time.sleep(0.5)

client.disconnect()
print("完成！现在对着手机说话5秒钟")