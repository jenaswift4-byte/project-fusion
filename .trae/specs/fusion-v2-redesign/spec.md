# Project Fusion v2.0 跨设备分布式计算平台 设计规范

## 1. 概述

### 1.1 项目愿景

Project Fusion v2.0 致力于构建一个**真正的跨设备无缝计算平台**，实现：

- **设备能力池化**：将手机、PC、云端的计算资源统一调度
- **应用原生级迁移**：应用在哪运行由AI智能决定，用户无感知
- **端侧AI推理**：手机本地运行1-2B参数模型，实现低延迟隐私AI
- **环境感知联动**：整合手机传感器与智能家居，构建智能生活场景

### 1.2 核心架构

```
┌────────────────────────────────────────────────────────────────────┐
│                    🌐 万物互联控制平面 (Orchestrator)                  │
│  • AI推理调度引擎                                                    │
│  • 分布式计算资源池                                                  │
│  • 应用生命周期管理器                                                │
│  • 环境感知与自动化                                                  │
└────────────────────────────────────────────────────────────────────┘
              ↓                           ↓                       ↓
┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│       📱 手机        │    │       💻 PC          │    │       ☁️ 云/边缘      │
│                      │    │                      │    │                      │
│  • 端侧AI推理 (Q4)  │    │  • Sunshine串流     │    │  • OpenAI API       │
│  • 触摸映射引擎     │    │  • 高性能编码       │    │  • 本地GPU服务       │
│  • 传感器数据采集   │    │  • 虚拟显示器      │    │  • 边缘计算节点       │
│  • Scrcpy投屏      │    │  • Ollama 70B      │    │                      │
└──────────────────────┘    └──────────────────────┘    └──────────────────────┘
```

---

## 2. 功能模块规范

### 2.1 手机原生运行PC应用（Phone as Display）

#### 2.1.1 技术选型

| 组件 | 技术方案 | 理由 |
|------|---------|------|
| **串流核心** | Sunshine + Moonlight | 开源成熟，延迟<40ms，支持GPU硬件编码 |
| **触摸映射** | 自研触摸映射引擎 | 手势识别+边缘手势+应用特定映射 |
| **虚拟显示** | Sunshine Virtual Display | 单应用独立窗口 |
| **传输优化** | USB优先 + 自适应码率 | 最低延迟体验 |

#### 2.1.2 Sunshine集成模块

```python
# 模块路径: bridge/modules/sunshine_manager.py
class SunshineManager:
    """
    Sunshine 应用串流管理器
    - 创建虚拟显示器
    - 单应用启动
    - Sunshine API集成
    """
```

#### 2.1.3 触摸映射引擎

```python
# 模块路径: bridge/modules/touch_mapper.py
class TouchMapper:
    """
    深度优化的触摸映射引擎
    - 手势识别 (点击/滑动/捏合/边缘)
    - 应用特定快捷键映射
    - 动态DPI缩放
    - 省电模式自适应
    """
```

#### 2.1.4 低延迟传输优化

```python
# 模块路径: bridge/modules/stream_optimizer.py
class StreamOptimizer:
    """
    自适应传输优化器
    - 连接类型检测 (USB/WiFi6/WiFi5)
    - 动态码率调整
    - Sunshine编码参数优化
    """
```

### 2.2 PC原生运行手机应用（PC Running Phone Apps）

#### 2.2.1 技术选型

| 组件 | 技术方案 | 理由 |
|------|---------|------|
| **单应用窗口** | VirtualDisplay + Scrcpy | Android 10+原生API，性能最佳 |
| **多应用管理** | Scrcpy多实例 | 每个应用独立窗口 |
| **应用启动器** | 托盘菜单 + 快捷方式 | 快速访问常用应用 |

#### 2.2.2 应用窗口管理器

```python
# 模块路径: bridge/modules/app_window_manager.py
class AppWindowManager:
    """
    手机应用窗口化管理器
    - VirtualDisplay创建
    - Scrcpy单应用捕获
    - 窗口生命周期管理
    - 应用图标快捷方式生成
    """
```

### 2.3 端侧AI推理引擎

#### 2.3.1 技术选型

| 模型 | 参数量 | 手机兼容性 | 推荐量化 |
|------|--------|-----------|---------|
| **Qwen2-1.5B** | 1.5B | ⭐⭐⭐⭐⭐ | Q4_K_M |
| **Phi-2** | 2.7B | ⭐⭐⭐ | Q4_K_M |
| **Gemma-2B** | 2B | ⭐⭐⭐⭐ | Q4_K_M |

| 推理框架 | 平台 | 理由 |
|---------|------|------|
| **llama.cpp** | Android/PC | 量化支持最佳，K/Q/V优化 |
| **Ollama** | PC | 70B模型支持，易用性好 |
| **MLC-LLM** | WebGPU | 移动端优化 |

#### 2.3.2 AI推理调度器

```python
# 模块路径: bridge/modules/ai_inference_engine.py
class AIInferenceEngine:
    """
    统一AI推理引擎
    - 多模型管理
    - 多后端路由 (llama.cpp/Ollama/OpenAI)
    - 智能模型选择
    - 推理结果缓存
    """
```

### 2.4 分布式计算与负载均衡

#### 2.4.1 技术选型

| 组件 | 技术方案 | 理由 |
|------|---------|------|
| **服务发现** | mDNS/DNS-SD (zeroconf) | 零配置局域网发现 |
| **设备监控** | psutil + ADB | 跨平台资源监控 |
| **负载调度** | 资源评分算法 | 综合CPU/内存/GPU/温度 |

#### 2.4.2 分布式调度器

```python
# 模块路径: bridge/modules/distributed_scheduler.py
class DistributedScheduler:
    """
    分布式调度器
    - 设备性能采集
    - 应用注册与发现
    - 负载均衡决策
    - 应用迁移执行
    """
```

### 2.5 传感器健康检测与环境感知

#### 2.5.1 技术选型

| 功能 | 技术方案 | 理由 |
|------|---------|------|
| **传感器检测** | dumpsys sensorservice | Android原生接口 |
| **健康诊断** | 噪声分析 + 漂移检测 | 统计学方法 |
| **数据上报** | MQTT | 实时性+低开销 |
| **智能家居** | Home Assistant | 生态成熟 |

#### 2.5.2 传感器健康监控

```python
# 模块路径: bridge/modules/sensor_health_monitor.py
class SensorHealthMonitor:
    """
    传感器健康监控系统
    - 传感器可用性检测
    - 噪声等级分析
    - 漂移检测
    - 健康报告生成
    """
```

#### 2.5.3 环境监控与智能联动

```python
# 模块路径: bridge/modules/environment_monitor.py
class EnvironmentMonitor:
    """
    环境感知监控器
    - 多传感器数据采集
    - 智能场景触发
    - Home Assistant集成
    - 异常告警
    """
```

### 2.6 混合网络架构

#### 2.6.1 技术选型

| 功能 | 技术方案 | 理由 |
|------|---------|------|
| **局域网发现** | zeroconf (mDNS) | 开源零配置 |
| **VPN隧道** | WireGuard | 高性能+安全 |
| **内网穿透** | Tailscale | 简易配置+稳定 |
| **通信协议** | WebSocket + gRPC | 控制信令+高性能RPC |

#### 2.6.2 混合网络管理器

```python
# 模块路径: bridge/network/mesh_network.py
class HybridNetworkManager:
    """
    混合网络管理器
    - 局域网优先连接
    - WireGuard VPN隧道
    - Tailscale内网穿透
    - 设备发现与服务注册
    """
```

### 2.7 手机省电管理

```python
# 模块路径: bridge/modules/phone_power_manager.py
class PhonePowerManager:
    """
    手机省电管理器
    - 动态刷新率调整
    - 自适应码率控制
    - 传感器功耗管理
    - 电池温度监控
    """
```

---

## 3. 数据流设计

### 3.1 PC应用串流到手机

```
用户点击"VSCode"
       ↓
SunshineManager.launch_app("vscode")
       ↓
创建虚拟显示器 (Display 2)
       ↓
在虚拟显示器上启动VSCode
       ↓
Sunshine GPU编码 (H.264/H.265)
       ↓
网络传输 (USB优先, 50Mbps)
       ↓
Moonlight接收并解码
       ↓
触摸映射引擎激活
       ↓
手机屏幕显示 + 触摸控制 → PC
```

### 3.2 手机应用显示到PC

```
用户点击托盘"抖音"
       ↓
AppWindowManager.launch_app("com.ss.android.ugc.aweme")
       ↓
创建VirtualDisplay (displayId=1)
       ↓
在虚拟显示器启动抖音
       ↓
Scrcpy --display=1 捕获特定显示器
       ↓
ADB 传输视频流到PC
       ↓
PC显示独立抖音窗口
       ↓
边缘检测触发键鼠流转
```

### 3.3 AI推理调度

```
用户发送"帮我写代码"
       ↓
AIInferenceEngine.infer(prompt)
       ↓
检测任务类型: code
       ↓
检查设备能力:
  - Android: Q4量化, 12GB RAM, 负载30%
  - PC: RTX 4070, 负载80%
       ↓
决策: Android优先 (负载低+网络OK)
       ↓
调用手机llama.cpp推理
       ↓
返回结果 (~15 tokens/s)
```

---

## 4. 配置规范

### 4.1 完整配置结构

```yaml
# Project Fusion v2.0 完整配置

# ═══ AI推理配置 ═══
ai:
  enabled: true
  default_model: "qwen2-1.5b-chat-q4"
  auto_select: true
  models:
    qwen2-1.5b-chat-q4:
      path_android: "/data/local/llm/qwen2-1.5b-q4_k_m.gguf"
      path_pc: "C:\\Models\\qwen2-1.5b-q4_k_m.gguf"
      backend: "llama.cpp"
      max_context: 8192
      capabilities: ["chat", "translate", "summarize"]
    phi-2-q4:
      path_android: "/data/local/llm/phi-2-q4.gguf"
      backend: "llama.cpp"
      max_context: 2048
      capabilities: ["chat", "code"]
    qwen2.5-7b:
      path_pc: "C:\\Models\\qwen2.5-7b-q4_k_m.gguf"
      backend: "ollama"
      max_context: 16384
      capabilities: ["chat", "code", "reasoning"]

# ═══ 手机运行PC应用配置 ═══
phone_as_display:
  enabled: true
  
  # Sunshine配置
  sunshine:
    auto_start: true
    port: 47984
    api_port: 47990
    apps:
      - id: "chrome"
        name: "Google Chrome"
        exe: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
        args: ["--new-window"]
      - id: "vscode"
        name: "Visual Studio Code"
        exe: "C:\\Users\\wang\\AppData\\Local\\Programs\\Microsoft VS Code\\Code.exe"
      - id: "file_explorer"
        name: "File Explorer"
        exe: "C:\\Windows\\explorer.exe"
        args: ["::{20D04FE0-3AEA-1069-A2D8-08002B30309D}"]
  
  # 传输配置
  transport:
    preferred: "sunshine"
    usb_priority: true
    adaptive_bitrate: true
  
  # 编码配置
  encoding:
    hardware_accel: "nvenc"  # nvenc, amf, quicksync, auto
    bitrate:
      usb: "50M"
      wifi6: "30M"
      wifi5: "20M"
      battery_saving: "5M"
    fps:
      usb: 120
      wifi6: 60
      wifi5: 60
      battery_saving: 30
  
  # 触摸配置
  touch:
    enabled: true
    sensitivity: 1.0
    tap: "left_click"
    double_tap: "left_double_click"
    long_press: "right_click"
    swipe_threshold: 50
    
    # 边缘手势
    edge_gestures:
      left_edge: "windows_key"
      right_edge: "alt_tab"
      top_edge: "alt_f4"
      bottom_edge: "show_taskbar"
    
    # 应用特定映射
    app_overrides:
      chrome:
        swipe_left: "alt_left"
        swipe_right: "alt_right"
      vscode:
        swipe_left: "ctrl_pageup"
        swipe_right: "ctrl_pagedn"

# ═══ PC运行手机应用配置 ═══
pc_running_phone_apps:
  enabled: true
  virtual_display:
    width: 1080
    height: 1920
    dpi: 420
  scrcpy:
    show_in_taskbar: true
    window_decorations: false
  apps:
    - id: "douyin"
      name: "抖音"
      package: "com.ss.android.ugc.aweme"
    - id: "bilibili"
      name: "哔哩哔哩"
      package: "tv.danmaku.bili"
    - id: "wechat"
      name: "微信"
      package: "com.tencent.mm"

# ═══ 分布式计算配置 ═══
distributed:
  enabled: true
  scheduler:
    strategy: "compute_score"  # compute_score, memory, round_robin
    check_interval_seconds: 5
    migration_threshold: 30
  offload:
    enabled: true
    ai_tasks: true
    encoding_tasks: true

# ═══ 传感器健康配置 ═══
sensors:
  health_check:
    enabled: true
    interval_hours: 24
    auto_calibrate: false
  thresholds:
    noise:
      light: 50.0
      accelerometer: 0.5
    drift:
      light: 100.0
      accelerometer: 1.0

# ═══ 环境感知配置 ═══
environment:
  enabled: true
  sensors:
    - light
    - proximity
    - noise
    - wifi_scan
    - bluetooth
  home_assistant:
    enabled: true
    url: "http://homeassistant:8123"
    token: ""
  mqtt:
    broker: "mqtt://homeassistant:1883"
    topic_prefix: "fusion"

# ═══ 网络配置 ═══
network:
  lan:
    enabled: true
    auto_discover: true
  tunnel:
    enabled: false
    provider: "wireguard"  # wireguard, tailscale
    wireguard_config: ""
    tailscale_auth_key: ""

# ═══ 省电配置 ═══
power_saving:
  auto_enabled: true
  battery_threshold: 30
  reduce_fps_when_low: true
  reduce_bitrate_when_low: true
  auto_adjust_refresh_rate: true
```

---

## 5. 新增文件清单

| 模块路径 | 功能描述 |
|---------|---------|
| `bridge/modules/sunshine_manager.py` | Sunshine应用串流管理器 |
| `bridge/modules/touch_mapper.py` | 触摸映射引擎 |
| `bridge/modules/stream_optimizer.py` | 低延迟传输优化器 |
| `bridge/modules/app_window_manager.py` | 手机应用窗口管理器 |
| `bridge/modules/ai_inference_engine.py` | AI推理统一引擎 |
| `bridge/modules/distributed_scheduler.py` | 分布式计算调度器 |
| `bridge/modules/sensor_health_monitor.py` | 传感器健康监控系统 |
| `bridge/modules/environment_monitor.py` | 环境感知监控器 |
| `bridge/modules/phone_power_manager.py` | 手机省电管理器 |
| `bridge/network/mesh_network.py` | 混合网络管理器 |
| `bridge/network/__init__.py` | 网络模块初始化 |

---

## 6. 依赖更新

```txt
# requirements.txt 新增

# AI推理
llama-cpp-python>=0.2.0
aiohttp>=3.9.0
openai>=1.0.0

# 分布式计算
zeroconf>=0.100.0
psutil>=5.9.0

# 网络
paho-mqtt>=1.6.1

# 其他
numpy>=1.24.0  # 触摸映射手势分析
```

---

## 7. 优先级规划

### Phase 1: 核心串流 (1-2周)
1. Sunshine Manager基础集成
2. 触摸映射引擎（基础手势）
3. 低延迟传输优化

### Phase 2: 应用窗口化 (1-2周)
4. AppWindowManager单应用窗口
5. 托盘应用启动器增强
6. 边缘手势系统

### Phase 3: AI推理 (1-2周)
7. AI Inference Engine基础
8. llama.cpp Android移植
9. 智能模型选择

### Phase 4: 分布式调度 (2-3周)
10. 设备性能监控
11. 负载均衡算法
12. 应用迁移框架

### Phase 5: 环境感知 (2-3周)
13. 传感器健康检测
14. 环境监控 + Home Assistant
15. 智能场景联动

### Phase 6: 网络优化 (1-2周)
16. mDNS设备发现
17. WireGuard/Tailscale集成
18. 混合网络路由

---

## 8. 兼容性考虑

### 8.1 Android版本要求
- **基础功能**: Android 8.0+
- **VirtualDisplay单应用**: Android 10+
- **传感器API**: 因设备而异

### 8.2 Windows版本要求
- **基础功能**: Windows 10
- **Sunshine**: Windows 10 1903+
- **GPU编码**: 需要NVIDIA/AMD/Intel GPU

### 8.3 网络要求
- **USB串流**: USB 3.0+
- **WiFi串流**: WiFi 5 (AC) 或 WiFi 6 (AX)
- **局域网发现**: 同一网段

---

## 9. 安全考虑

### 9.1 数据安全
- 串流数据端到端加密（Sunshine内置TLS）
- API认证Token安全存储
- MQTT通信使用用户名密码

### 9.2 隐私保护
- AI推理优先本地化（敏感数据不离设备）
- 传感器数据仅在授权网络传输
- 设备发现使用零信任模型

---

## 10. 文档输出

实现完成后应产出：
1. `docs/architecture.md` - 架构设计文档
2. `docs/api.md` - API接口文档
3. `docs/user-guide.md` - 用户使用指南
4. `docs/developer-guide.md` - 开发者指南
