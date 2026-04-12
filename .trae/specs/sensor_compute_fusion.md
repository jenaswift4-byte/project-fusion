# Project Fusion - 传感器与算力整合方案

## 核心目标

**让手机成为PC的传感器中枢 + AI协处理器**

---

## 一、传感器整合方案

### 1.1 可用传感器列表（MI 8）

| 传感器 | 用途 | 采样率 |
|--------|------|--------|
| **光线传感器** | 自动调节PC屏幕亮度 | 10Hz |
| **距离传感器** | 检测人是否在PC前 | 5Hz |
| **加速度计** | 震动检测/倾斜输入 | 50Hz |
| **陀螺仪** | 精确姿态检测 | 50Hz |
| **气压计** | 海拔/天气监测 | 1Hz |
| **磁力计** | 指南针/方向检测 | 10Hz |
| **摄像头** | 视频会议/监控 | 30fps |
| **麦克风** | 语音输入/降噪 | 48kHz |

### 1.2 传感器数据流架构

```
手机传感器
    ↓
SensorHub (融合中心)
    ↓ WebSocket (17532端口)
PC端 Sensor Receiver
    ↓
┌─────────────────────────────────────┐
│  应用场景                            │
├─────────────────────────────────────┤
│  • 自动亮度调节 (光线传感器)         │
│  • 自动锁屏/解锁 (距离传感器)        │
│  • 倾斜控制 (陀螺仪+加速度计)        │
│  • 环境监测 (气压计+温湿度)          │
│  • 视频会议 (摄像头+麦克风)          │
└─────────────────────────────────────┘
```

### 1.3 具体实现

#### 场景1：自动亮度调节

```python
# 手机端 - 光线传感器监听
class LightSensorListener:
    def on_sensor_changed(self, event):
        lux = event.values[0]  # 环境光照度
        
        # 智能判断
        if lux < 50:
            brightness = 30  # 暗环境
        elif lux < 200:
            brightness = 60  # 正常室内
        else:
            brightness = 100  # 明亮环境
        
        # 发送到PC
        ws_client.send({
            "type": "auto_brightness",
            "lux": lux,
            "brightness": brightness
        })
```

```python
# PC端 - 亮度调节
class AutoBrightnessController:
    def on_sensor_data(self, data):
        if data["type"] == "auto_brightness":
            # 调用Windows API调节亮度
            self.set_screen_brightness(data["brightness"])
```

#### 场景2：自动锁屏/解锁

```python
# 手机端 - 距离传感器
class ProximityLock:
    def on_sensor_changed(self, event):
        distance = event.values[0]  # 厘米
        
        if distance < 5:  # 人靠近
            ws_client.send({"type": "user_present", "present": True})
        else:  # 人离开
            ws_client.send({"type": "user_present", "present": False})
```

```python
# PC端 - 自动锁屏
class AutoLockController:
    def __init__(self):
        self.user_present = True
        self.away_timeout = 30  # 秒
        
    def on_sensor_data(self, data):
        if data["type"] == "user_present":
            if not data["present"] and self.user_present:
                # 人离开，启动计时器
                threading.Timer(
                    self.away_timeout, 
                    self.lock_screen
                ).start()
            elif data["present"] and not self.user_present:
                # 人回来，取消锁屏
                self.unlock_screen()
            
            self.user_present = data["present"]
```

#### 场景3：倾斜控制

```python
# 手机端 - 陀螺仪+加速度计
class TiltController:
    def on_sensor_changed(self, event):
        # 计算倾斜角度
        pitch = event.values[0]  # 前后倾斜
        roll = event.values[1]   # 左右倾斜
        
        # 发送到PC
        ws_client.send({
            "type": "tilt_input",
            "pitch": pitch,
            "roll": roll
        })
```

```python
# PC端 - 倾斜映射为鼠标/音量控制
class TiltInputMapper:
    def on_sensor_data(self, data):
        if data["type"] == "tilt_input":
            # 倾斜控制音量
            volume = int((data["pitch"] + 90) / 180 * 100)
            self.set_volume(volume)
            
            # 或者映射为鼠标移动
            # mouse.move(data["roll"] * 2, data["pitch"] * 2)
```

---

## 二、算力整合方案

### 2.1 手机算力分析（MI 8）

| 组件 | 规格 | 适用场景 |
|------|------|----------|
| **CPU** | 骁龙845 (8核) | 通用计算 |
| **GPU** | Adreno 630 | 图形/并行计算 |
| **NPU** | Hexagon 685 | AI推理 (1-2B模型) |
| **内存** | 6GB LPDDR4X | 模型加载 |

### 2.2 分布式计算架构

```
PC (主节点)
    ↓ 任务分发
┌─────────────────────────────────┐
│  任务调度器                      │
│  • 分析任务类型                  │
│  • 判断是否适合手机端            │
│  • 负载均衡                      │
└─────────────────────────────────┘
    ↓ WebSocket / USB
手机端 (计算节点)
    ↓
┌─────────────────────────────────┐
│  AI推理引擎                      │
│  • Qwen2-1.5B (文本)            │
│  • MobileNet (图像)             │
│  • Whisper (语音)               │
└─────────────────────────────────┘
    ↓ 结果返回
PC端整合
```

### 2.3 具体实现

#### 场景1：AI文本推理（手机端）

```python
# 手机端 - AI推理引擎
class MobileAIEngine:
    def __init__(self):
        # 加载1-2B参数模型
        self.llm = load_model("qwen2-1.5b-int4")
        
    def infer(self, prompt: str) -> str:
        # 使用NPU加速推理
        result = self.llm.generate(
            prompt,
            max_tokens=512,
            temperature=0.7
        )
        return result
```

```python
# PC端 - 任务分发
class TaskDispatcher:
    def dispatch_ai_task(self, task: str):
        # 判断任务类型
        if self.is_text_generation(task):
            # 发送到手机
            ws_client.send({
                "type": "ai_infer",
                "task": task,
                "model": "qwen2-1.5b"
            })
            
            # 等待结果
            result = ws_client.recv()
            return result["output"]
```

#### 场景2：图像识别（手机端）

```python
# 手机端 - 图像识别
class ImageRecognizer:
    def __init__(self):
        self.model = load_model("mobilenet_v3")
        
    def recognize(self, image_path: str) -> dict:
        image = load_image(image_path)
        result = self.model.predict(image)
        return {
            "label": result["label"],
            "confidence": result["confidence"]
        }
```

#### 场景3：语音转文字（手机端）

```python
# 手机端 - 语音识别
class SpeechRecognizer:
    def __init__(self):
        # Whisper tiny模型 (39M参数)
        self.model = load_model("whisper-tiny-zh")
        
    def transcribe(self, audio_data: bytes) -> str:
        result = self.model.transcribe(audio_data)
        return result["text"]
```

---

## 三、技术架构

### 3.1 通信协议

```python
# WebSocket消息格式
{
    "type": "sensor_data" | "ai_result" | "task_dispatch",
    "timestamp": 1234567890,
    "data": {
        # 传感器数据
        "sensor_type": "light" | "proximity" | "gyro" | ...,
        "values": [value1, value2, ...],
        
        # AI推理结果
        "task_id": "uuid",
        "output": "推理结果",
        "latency_ms": 150,
        
        # 任务分发
        "task_type": "text_gen" | "image_rec" | ...,
        "payload": {...}
    }
}
```

### 3.2 性能优化

| 优化项 | 实现方式 |
|--------|----------|
| **数据压缩** | 传感器数据用protobuf序列化 |
| **批量发送** | 多个传感器数据合并发送 |
| **差分更新** | 只发送变化的数据 |
| **USB优先** | 优先使用USB而非WiFi |
| **模型量化** | INT4量化减少内存占用 |

---

## 四、实现步骤

### 阶段1：传感器整合（1周）

- [ ] 实现SensorHub（手机端）
- [ ] 实现Sensor Receiver（PC端）
- [ ] 自动亮度调节
- [ ] 自动锁屏/解锁
- [ ] 倾斜控制

### 阶段2：算力整合（2周）

- [ ] 实现Mobile AI Engine
- [ ] 实现Task Dispatcher
- [ ] 文本生成任务
- [ ] 图像识别任务
- [ ] 语音识别任务

### 阶段3：性能优化（1周）

- [ ] USB通信优化
- [ ] 模型量化
- [ ] 延迟测试
- [ ] 功耗测试

---

## 五、预期效果

| 功能 | 延迟 | 功耗 |
|------|------|------|
| 传感器同步 | <50ms | <1%/小时 |
| AI文本推理 | <500ms | <5%/次 |
| 图像识别 | <200ms | <2%/次 |
| 语音转写 | <100ms | <1%/分钟 |

---

## 六、需要修改的文件

### 手机端（Companion App）

1. `SensorHub.java` - 传感器数据融合中心
2. `MobileAIEngine.java` - AI推理引擎
3. `WebSocketService.java` - 通信服务

### PC端（Bridge）

1. `bridge/modules/sensor_receiver.py` - 传感器接收器
2. `bridge/modules/auto_brightness.py` - 自动亮度
3. `bridge/modules/auto_lock.py` - 自动锁屏
4. `bridge/modules/task_dispatcher.py` - 任务分发器
5. `bridge/modules/ai_client.py` - AI客户端

---

**这个方案如何？需要我调整哪个部分？**
