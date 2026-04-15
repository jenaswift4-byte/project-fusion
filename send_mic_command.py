import paho.mqtt.client as mqtt
import time
import json

# 创建 MQTT 客户端
client = mqtt.Client()

# 连接到本地 Broker
client.connect('127.0.0.1', 1883, 60)

# 发送录音命令
device_id = "7254adb5"
topic = f"fusion/audio/{device_id}/command"
payload = json.dumps({"action": "start_mic"})

print(f"发送命令到 {topic}: {payload}")
client.publish(topic, payload)

# 等待一下
time.sleep(1)

# 发送停止命令（5秒后）
payload_stop = json.dumps({"action": "stop_mic"})
print(f"发送停止命令: {payload_stop}")
client.publish(topic, payload_stop)

client.disconnect()
print("完成！")