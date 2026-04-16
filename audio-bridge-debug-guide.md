# 音频桥接调试指南

## 一、常见问题诊断

### 1.1 ASR识别失败

**症状**: 日志中出现 `ASR 处理失败` 或识别结果为空

**排查步骤**:
```bash
# 1. 检查模型文件是否存在
adb shell ls -la /sdcard/Android/data/com.fusion.companion/files/models/

# 2. 检查音频数据是否传输
adb logcat -d -s StreamingASR | grep -E "(PCM|audio)"

# 3. 检查VAD状态
adb logcat -d -s VADHelper
```

**解决方案**:
- 确认模型文件完整推送
- 检查音频数据格式是否为16kHz/16bit/mono
- 调整VAD阈值: `vadHelper = new VADHelper(newThreshold)`

### 1.2 延迟过高

**症状**: 从说话到识别结果输出超过500ms

**排查步骤**:
```bash
# 测量各阶段耗时
adb logcat -d -s StreamingASR | grep -E "(开始|结束|耗时)"
```

**优化方法**:
- 减小音频分片大小: `FRAME_SIZE_MS = 100` (默认200ms)
- 优化网络传输: 使用更高效的WebSocket库
- 调整VAD灵敏度: 降低误触发，减少不必要的ASR调用

### 1.3 内存溢出

**症状**: 应用崩溃，日志出现 `OutOfMemoryError`

**原因**: 长时间语音累积导致speechBuffer过大

**解决方案**:
```java
// 在StreamingASRService中调整最大缓存时间
private static final int MAX_SPEECH_DURATION_MS = 15000; // 从30000降低
```

## 二、调试技巧

### 2.1 日志增强

**临时增强日志级别**:
```java
// 在关键方法前后添加
Log.d(TAG, "=== 方法入口: processSpeech ===");
Log.d(TAG, "语音数据长度: " + pcmData.length);
Log.d(TAG, "=== 方法出口: processSpeech ===");
```

**实时日志过滤**:
```bash
# 实时监控关键日志
adb logcat -v time | grep -E "(StreamingASR|VADHelper|sherpa)"

# 监控错误日志
adb logcat -v time *:E | grep -i "sherpa\|asr"
```

### 2.2 音频数据验证

**验证音频格式**:
```bash
# 使用ffprobe检查音频文件
ffprobe -i test_audio.wav

# 预期输出:
# Stream #0:0: Audio: pcm_s16le ([1][0][0][0] / 0x0001), 16000 Hz, mono, s16, 256 kb/s
```

**验证PCM数据**:
```java
// 在AudioStreamer中添加调试代码
byte[] debugBuffer = new byte[3200]; // 一帧数据
int bytesRead = audioRecord.read(debugBuffer, 0, 3200);
Log.d(TAG, "读取音频: " + bytesRead + " bytes, RMS: " + calculateRMS(debugBuffer));
```

### 2.3 网络传输调试

**WebSocket连接状态**:
```bash
# 检查WebSocket连接
adb logcat -d -s WebSocket

# 验证数据发送
adb shell dumpsys activity services | grep WebSocket
```

**网络质量测试**:
```bash
# 测试网络延迟
adb shell ping -c 4 192.168.1.100

# 测试带宽
adb shell iperf3 -c server_ip
```

## 三、性能优化

### 3.1 音频采集优化

**调整AudioRecord参数**:
```java
// 优化缓冲区大小
private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
    SAMPLE_RATE, 
    CHANNEL_CONFIG, 
    AUDIO_FORMAT
);
```

**多线程处理**:
```java
// 分离录音和处理线程
new Thread(() -> {
    while (isRecording) {
        int bytesRead = audioRecord.read(buffer, 0, bufferSize);
        processAudio(buffer, bytesRead);
    }
}).start();
```

### 3.2 ASR处理优化

**批处理优化**:
```java
// 累积多个音频帧再处理
List<Float> accumulatedSamples = new ArrayList<>();

void processAudio(float[] samples) {
    accumulatedSamples.addAll(Arrays.asList(samples));
    
    if (accumulatedSamples.size() >= SAMPLE_THRESHOLD) {
        float[] batch = convertToArray(accumulatedSamples);
        recognizer.acceptWaveform(batch, sampleRate);
        accumulatedSamples.clear();
    }
}
```

### 3.3 内存管理

**对象池技术**:
```java
private static final ObjectPool<float[]> floatArrayPool = 
    new SimpleObjectPool<>(() -> new float[8192], 10);

float[] buffer = floatArrayPool.acquire();
try {
    // 使用buffer
} finally {
    floatArrayPool.release(buffer);
}
```

## 四、监控指标

### 4.1 关键性能指标(KPI)

| 指标 | 目标值 | 测量方法 |
|------|--------|----------|
| 端到端延迟 | < 350ms | 语音开始到识别结果输出 |
| 识别准确率 | >90% | 正确识别字数/总字数 |
| VAD准确率 | >85% | 正确检测语音段数/总语音段 |
| 内存占用 | < 100MB | Android Profiler |
| CPU使用率 | < 30% | 系统监控 |

### 4.2 日志分析脚本

```python
import re
from collections import defaultdict

def analyze_logs(log_file):
    stats = defaultdict(int)
    
    with open(log_file, 'r') as f:
        for line in f:
            # 统计ASR调用次数
            if 'processSpeech' in line:
                stats['asr_calls'] += 1
            
            # 统计错误
            if 'ERROR' in line:
                stats['errors'] += 1
            
            # 统计识别结果
            if 'ASR 结果:' in line:
                stats['recognitions'] += 1
    
    print(f"ASR调用次数: {stats['asr_calls']}")
    print(f"错误次数: {stats['errors']}")
    print(f"识别结果数: {stats['recognitions']}")
    
    if stats['asr_calls'] > 0:
        success_rate = stats['recognitions'] / stats['asr_calls'] * 100
        print(f"识别成功率: {success_rate:.2f}%")

# 使用: python analyze_logs.py test_logs.txt
```

## 五、故障排除流程图

```
开始
  ↓
ASR识别失败?
  ├─是→ 检查模型文件是否存在
  │       ├─否→ 重新推送模型
  │       └─是→ 检查音频格式
  │               ├─否→ 转换音频格式
  │               └─是→ 检查日志错误
  │                       ├─内存溢出→ 降低MAX_SPEECH_DURATION_MS
  │                       ├─网络错误→ 检查网络连接
  │                       └─其他→ 查看详细日志
  └─否→ 检查延迟
          ├─是→ 优化网络和缓冲区
          └─否→ 测试完成
```