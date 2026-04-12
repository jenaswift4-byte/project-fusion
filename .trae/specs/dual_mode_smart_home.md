# Project Fusion - 双模式智能家居方案

## 硬件配置更新

### 设备布局

| 设备 | 位置 | 用途 | 模式 |
|------|------|------|------|
| **手机A** | 厨房 | 环境监控 + 菜谱 + 本地AI | 固定 + 常充电 |
| **手机B** | 厕所 | 环境监控 + 媒体播放 | 固定 + 常充电 |
| **手机C** | 卧室 | 环境监控 + 闹钟 + **次级中枢** | 固定 + 常充电 |
| **手机D** | 随身 | 主力交互 + 传感器 | 移动 + 常充电 |
| **PC** | 书房 | 主算力 (可带走) | 移动 |

### 次级中枢选择：卧室手机C

**为什么选卧室手机C？**
- ✅ 位置居中，WiFi信号好
- ✅ 24小时插电，永不断电
- ✅ 固定位置，稳定性高
- ✅ MI 8 性能足够 (骁龙845 + 6GB内存)

### 智能设备（手搓）

| 设备 | 位置 | 控制方式 | 接口 |
|------|------|----------|------|
| **步进电机** | 窗帘 | ESP32 + 驱动板 | WiFi/MQTT |
| **舵机** | 柜门 | ESP32 + 舵机驱动 | WiFi/MQTT |
| **继电器** | 灯光/插座 | ESP32 + 继电器模块 | WiFi/MQTT |
| **温湿度传感器** | 各房间 | DHT22 + ESP32 | WiFi/MQTT |
| **烟雾传感器** | 厨房 | MQ-2 + ESP32 | WiFi/MQTT |
| **水浸传感器** | 厕所/厨房 | 水浸模块 + ESP32 | WiFi/MQTT |

---

## 双模式架构

### 模式1：离线模式（你不在家）

```
┌─────────────────────────────────────┐
│  卧室手机C (次级中枢)                │
│  • 本地AI: Qwen2.5-3B               │
│  • 语音识别：Whisper-tiny           │
│  • 设备控制：MQTT Broker            │
│  • 数据存储：SQLite                 │
└─────────────────────────────────────┘
         ↓ MQTT (局域网)
┌─────────────────────────────────────┐
│  ESP32 设备                          │
│  • 窗帘电机                         │
│  • 灯光继电器                       │
│  • 传感器                           │
└─────────────────────────────────────┘
```

**特点**：
- ✅ 完全离线，无需互联网
- ✅ 本地AI推理，隐私安全
- ✅ 响应快 (<500ms)
- ⚠️ 功能受限（只有本地模型能力）

### 模式2：在线模式（你在家）

```
┌─────────────────────────────────────┐
│  PC (主中枢)                         │
│  • 本地AI: Qwen3.5-7B               │
│  • 云端AI: Qwen-Max API             │
│  • 设备控制：Home Assistant         │
│  • 媒体库：本地音乐/视频            │
└─────────────────────────────────────┘
         ↓ WiFi
┌─────────────────────────────────────┐
│  卧室手机C (次级中枢 - 中继)         │
│  • 转发请求到PC                     │
│  • PC不在时降级为离线模式           │
└─────────────────────────────────────┘
         ↓
┌─────────────────────────────────────┐
│  各房间手机 + ESP32设备              │
└─────────────────────────────────────┘
```

**特点**：
- ✅ 强大AI能力（云端 + 本地）
- ✅ 完整功能
- ✅ 可以语音控制（小爱/Google助手）
- ⚠️ 依赖网络

---

## 模式切换逻辑

```python
class ModeManager:
    def __init__(self):
        self.current_mode = "offline"
        self.pc_present = False
        self.internet_available = False
        
    def check_conditions(self):
        # 检测PC是否在家（ping）
        self.pc_present = self.ping_pc()
        
        # 检测网络是否可用
        self.internet_available = self.check_internet()
        
        # 决定模式
        if self.pc_present and self.internet_available:
            new_mode = "online"
        else:
            new_mode = "offline"
        
        # 模式切换
        if new_mode != self.current_mode:
            self.switch_mode(new_mode)
    
    def switch_mode(self, mode: str):
        old_mode = self.current_mode
        self.current_mode = mode
        
        print(f"模式切换：{old_mode} -> {mode}")
        
        if mode == "online":
            # 在线模式
            self.enable_cloud_ai()
            self.enable_voice_assistant()
            self.sync_to_cloud()
        else:
            # 离线模式
            self.disable_cloud_ai()
            self.enable_local_ai()
            self.work_offline()
```

---

## 场景实现（双模式）

### 场景1：起床闹钟

#### 离线模式

```python
# 卧室手机C (次级中枢)
class OfflineAlarm:
    def __init__(self):
        self.ai = LocalAI("qwen2.5-3b-int4")
        
    def on_alarm(self, time: str):
        # 逐渐调亮屏幕
        self.fade_in_brightness()
        
        # 播放本地音乐
        self.play_music("轻柔音乐")
        
        # 检测到人起床
        if self.proximity_sensor.detect():
            # 语音问候
            response = self.ai.generate("早上问候语")
            self.tts.speak(response)
            
            # 打开窗帘（ESP32控制）
            self.mqtt.publish("curtain/open")
```

#### 在线模式

```python
# PC (主中枢)
class OnlineAlarm:
    def __init__(self):
        self.ai = CloudAI("qwen-max")
        
    def on_alarm(self, time: str):
        # 逐渐调亮屏幕
        self.fade_in_brightness()
        
        # 播放在线音乐（网易云/QQ音乐）
        self.play_online_music("今日推荐")
        
        # 检测到人起床
        if self.proximity_sensor.detect():
            # 语音问候（更智能）
            response = self.ai.generate(
                "早上问候，包含天气和日程"
            )
            self.tts.speak(response)
            
            # 打开窗帘
            self.mqtt.publish("curtain/open")
            
            # 播报天气
            weather = self.get_weather()
            self.tts.speak(f"今天天气{weather}")
```

---

### 场景2：做饭场景

#### 离线模式

```python
# 厨房手机A
class OfflineCooking:
    def __init__(self):
        self.ai = LocalAI("qwen2.5-3b")
        self.recipes = self.load_local_recipes()
        
    def on_voice_command(self, cmd: str):
        # 本地语音识别
        text = self.whisper.recognize(cmd)
        
        if "番茄炒蛋" in text:
            # 本地菜谱
            recipe = self.recipes.get("番茄炒蛋")
            self.display(recipe)
            
            # 设置计时器
            self.timer.start(3 * 60)  # 炒蛋3分钟
            
            # 打开排气扇（ESP32继电器）
            self.mqtt.publish("fan/on")
            
    def on_smoke_detected(self):
        # 烟雾传感器触发
        if self.smoke_sensor.value > threshold:
            # 本地警报
            self.alarm.play()
            
            # 打开窗户（ESP32电机）
            self.mqtt.publish("window/open")
```

#### 在线模式

```python
# PC + 厨房手机A
class OnlineCooking:
    def on_voice_command(self, cmd: str):
        # 云端语音识别（更准确）
        text = self.cloud_asr.recognize(cmd)
        
        if "番茄炒蛋" in text:
            # 云端菜谱（更丰富）
            recipe = self.cloud_ai.search("番茄炒蛋 菜谱")
            self.display(recipe)
            
            # 播放教学视频
            self.play_video("番茄炒蛋教学")
            
            # 设置计时器
            self.timer.start(3 * 60)
            
            # 打开排气扇
            self.mqtt.publish("fan/on")
```

---

### 场景3：安防监控

#### 离线模式

```python
# 卧室手机C (次级中枢)
class OfflineSecurity:
    def __init__(self):
        self.alerts = []
        
    def monitor(self):
        while True:
            # 检测厨房烟雾
            if self.kitchen_smoke > threshold:
                self.alert("厨房烟雾报警！")
                self.mqtt.publish("window/open")
                
            # 检测厕所漏水
            if self.bathroom_water:
                self.alert("厕所漏水！")
                self.mqtt.publish("water_valve/close")
                
            # 检测入侵（距离传感器）
            if self.bedroom_proximity < 50:  # 有人
                if self.is_night():
                    self.alert("卧室检测到入侵！")
                    self.camera.record()
                    
            time.sleep(1)
    
    def alert(self, message: str):
        # 本地警报
        self.speaker.play("警报声")
        
        # 推送到随身手机D（局域网）
        self.mqtt.publish("mobile/alert", {
            "message": message,
            "timestamp": time.time()
        })
```

#### 在线模式

```python
# PC (主中枢)
class OnlineSecurity:
    def monitor(self):
        while True:
            # 烟雾/漏水检测
            if self.kitchen_smoke > threshold:
                self.alert("厨房烟雾报警！")
                self.mqtt.publish("window/open")
                
                # 推送手机通知
                self.push_notification("厨房烟雾报警！")
                
                # 发送短信
                self.send_sms("厨房烟雾报警！")
                
            # 入侵检测 + 人脸识别
            if self.bedroom_camera.detect_person():
                face = self.face_recognition.recognize()
                
                if face == "unknown":
                    self.alert("未知人员入侵！")
                    self.camera.record()
                    
                    # 报警
                    self.call_police()
```

---

### 场景4：媒体播放

#### 离线模式

```python
# 分布式媒体播放（本地音乐库）
class OfflineMedia:
    def __init__(self):
        self.music_lib = self.load_local_music()
        self.current_device = "kitchen"
        
    def on_voice_command(self, cmd: str):
        text = self.whisper.recognize(cmd)
        
        if "播放" in text:
            # 提取歌名
            song = self.extract_song_name(text)
            
            # 本地搜索
            track = self.music_lib.search(song)
            
            if track:
                # 在当前设备播放
                self.play_on_device(self.current_device, track)
    
    def follow_user(self):
        # 检测人位置
        for device_id in ["kitchen", "bathroom", "bedroom"]:
            if self.proximity[device_id] < 100:  # 检测到人在
                if device_id != self.current_device:
                    # 切换播放
                    self.transfer_media(
                        self.current_device, 
                        device_id
                    )
                    self.current_device = device_id
```

#### 在线模式

```python
# PC (主中枢)
class OnlineMedia:
    def __init__(self):
        self.music_services = ["网易云", "QQ音乐", "Spotify"]
        
    def on_voice_command(self, cmd: str):
        # 云端语音识别
        text = self.cloud_asr.recognize(cmd)
        
        if "播放" in text:
            song = self.extract_song_name(text)
            
            # 在线搜索（曲库更全）
            track = self.cloud_music.search(song)
            
            if track:
                # 多房间同步播放
                self.multi_room_play(track)
    
    def multi_room_play(self, track):
        # 所有设备同时播放
        for device in ["kitchen", "bathroom", "bedroom"]:
            self.mqtt.publish(f"{device}/play", {
                "track": track.url,
                "position": 0,
                "sync_time": time.time()
            })
```

---

## 本地AI模型选择

### 文本生成

| 设备 | 模型 | 量化 | 内存 | 速度 |
|------|------|------|------|------|
| 厨房手机A | Qwen2.5-3B | INT4 | 2GB | ~50 tokens/s |
| 厕所手机B | Gemma-2B | INT4 | 1.5GB | ~80 tokens/s |
| 卧室手机C | Qwen2.5-3B | INT4 | 2GB | ~50 tokens/s |
| PC | Qwen3.5-7B | FP16 | 14GB | ~30 tokens/s |

### 语音识别

| 设备 | 模型 | 语言 | 准确率 |
|------|------|------|--------|
| 所有手机 | Whisper-tiny-zh | 中文 | ~90% |
| PC | Whisper-large-v3 | 多语言 | ~98% |

### 语音合成

| 设备 | 模型 | 音质 | 速度 |
|------|------|------|------|
| 所有手机 | VITS-zh | 良好 | 实时 |
| PC | CosyVoice | 优秀 | 实时 |

---

## ESP32手搓设备接口

### 1. 窗帘电机控制

```python
# ESP32固件 (MicroPython)
from machine import Pin, PWM
import network
import umqtt.simple

class CurtainController:
    def __init__(self):
        # 步进电机控制
        self.step_pin = Pin(4, Pin.OUT)
        self.dir_pin = Pin(5, Pin.OUT)
        self.enable_pin = Pin(6, Pin.OUT)
        
        # MQTT连接
        self.mqtt = umqtt.simple.MQTTClient(
            "curtain_01",
            "bedroom_phone_c",  # 卧室手机C做broker
            port=1883
        )
        self.mqtt.connect()
        
        # 订阅主题
        self.mqtt.set_callback(self.on_message)
        self.mqtt.subscribe("curtain/command")
    
    def on_message(self, topic, msg):
        if msg == b"open":
            self.open_curtain()
        elif msg == b"close":
            self.close_curtain()
        elif msg == b"stop":
            self.stop()
    
    def open_curtain(self):
        # 打开窗帘
        self.dir_pin.value(1)
        self.enable_pin.value(1)
        for _ in range(1000):  # 步数
            self.step_pin.value(1)
            self.step_pin.value(0)
        self.enable_pin.value(0)
```

### 2. 灯光继电器控制

```python
# ESP32固件
class LightController:
    def __init__(self):
        self.relay = Pin(2, Pin.OUT)
        
        self.mqtt = umqtt.simple.MQTTClient(
            "light_01",
            "bedroom_phone_c",
            port=1883
        )
        self.mqtt.connect()
        self.mqtt.subscribe("light/command")
    
    def on_message(self, topic, msg):
        if msg == b"on":
            self.relay.value(1)  # 开灯
        elif msg == b"off":
            self.relay.value(0)  # 关灯
```

### 3. 传感器上报

```python
# ESP32固件
import dht
import time

class SensorReporter:
    def __init__(self):
        self.sensor = dht.DHT22(Pin(4))
        
        self.mqtt = umqtt.simple.MQTTClient(
            "sensor_01",
            "bedroom_phone_c",
            port=1883
        )
        self.mqtt.connect()
    
    def report(self):
        while True:
            self.sensor.measure()
            temp = self.sensor.temperature()
            humidity = self.sensor.humidity()
            
            self.mqtt.publish("sensor/data", {
                "temperature": temp,
                "humidity": humidity,
                "timestamp": time.time()
            })
            
            time.sleep(5)  # 5秒上报一次
```

---

## MQTT Broker (卧室手机C)

```python
# 卧室手机C运行MQTT Broker
import asyncio
from gmqtt import Client as MQTTClient

class MQTTBroker:
    def __init__(self):
        self.clients = []
        self.topics = {}
        
    async def start(self):
        # 启动MQTT服务器
        self.server = await asyncio.start_server(
            self.handle_client,
            '0.0.0.0',
            1883
        )
        print("MQTT Broker 已启动 (卧室手机C)")
        
    async def handle_client(self, reader, writer):
        # 处理客户端连接
        client = MQTTClient(reader, writer)
        self.clients.append(client)
        
        while True:
            data = await reader.read(1024)
            if not data:
                break
            
            # 解析MQTT消息
            topic, payload = self.parse_mqtt(data)
            
            # 转发给订阅者
            await self.publish(topic, payload)
    
    async def publish(self, topic: str, payload: dict):
        # 转发给所有订阅该topic的客户端
        for client in self.clients:
            if client.subscribed(topic):
                await client.send(topic, payload)
```

---

## 模式切换示例

### 你出门了（PC带走）

```
1. PC关闭 → 卧室手机C检测到ping失败
2. 自动切换到离线模式
3. 卧室手机C启动本地MQTT Broker
4. 加载本地AI模型 (Qwen2.5-3B)
5. 所有设备连接到卧室手机C
6. 继续运行基本功能
```

### 你回家了（PC带回）

```
1. PC启动 → 卧室手机C检测到ping成功
2. 自动切换到在线模式
3. 卧室手机C停止MQTT Broker → PC接管
4. 本地AI模型卸载 → PC加载Qwen3.5-7B
5. 启用云端AI API
6. 启用语音助手（小爱/Google）
7. 完整功能恢复
```

---

## 功耗管理

### 固定设备（厨房/厕所/卧室）

| 状态 | 屏幕 | WiFi | 传感器 | 功耗 |
|------|------|------|--------|------|
| 活跃 | 亮 | 开 | 10Hz | ~3W |
| 待机 | 关 | 开 | 1Hz | ~0.5W |
| 睡眠 | 关 | 关 | 关闭 | ~0.1W |

### 策略

```python
class PowerManager:
    def __init__(self):
        self.idle_timer = 300  # 5分钟
        
    def on_idle(self):
        # 5分钟无操作
        self.screen.off()
        self.sensor_rate = 1  # 1Hz
        self.wifi.sleep()
        
    def on_night(self):
        # 夜间模式
        self.screen.off()
        self.sensor_rate = 0.1  # 10秒一次
        self.mqtt.sleep()
```

---

## 总结

### 双模式对比

| 功能 | 离线模式 | 在线模式 |
|------|----------|----------|
| AI能力 | Qwen2.5-3B (本地) | Qwen3.5-7B + 云端 |
| 语音识别 | Whisper-tiny (90%) | 云端ASR (98%) |
| 媒体库 | 本地音乐 | 在线音乐 |
| 响应速度 | <500ms | <200ms |
| 隐私 | 完全本地 | 部分云端 |
| 功耗 | 低 | 高 |

### 核心优势

1. ✅ **离线也能用** - 你不在家时，本地AI + ESP32继续工作
2. ✅ **在线更强大** - 你在家时，云端AI + 语音助手
3. ✅ **手搓设备** - ESP32 + 电机，成本低，可控
4. ✅ **次级中枢** - 卧室手机C永不断电，PC不在也能工作

---

**开始实现？先做哪个？**
1. ESP32固件（电机/继电器控制）
2. 卧室手机C的MQTT Broker
3. 本地AI推理引擎
4. 双模式切换逻辑
