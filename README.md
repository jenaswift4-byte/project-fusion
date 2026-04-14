# 🔀 Project Fusion

> 废旧 Android 手机 → 分布式传感器 + 摄像头 + 算力节点，PC 端统一控制。

不造轮子，只做胶水。串联现有开源组件（ADB / MQTT / WebSocket / Camera2 API），让废旧手机成为 PC 的外设集群。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## 🎯 核心场景

| 场景 | 说明 |
|------|------|
| 📹 **分布式摄像头** | Camera2 API 帧流，PC 实时查看画面 |
| 🌡️ **环境传感器网络** | 气压/光线/加速度/陀螺仪/磁场实时采集 |
| 📋 **跨设备剪贴板** | 手机 ↔ PC 双向同步，链接自动打开 |
| 📱 **通知转发** | 手机通知推送到 PC Toast，支持免打扰 |
| 📩 **短信读取** | PC 端实时查看手机短信 |
| 📺 **视频投射** | PC → 手机 URL 投射，播放控制 |
| 🔊 **音频桥接** | 手机麦克风 → PC，PC 播放 → 手机 |
| 🤖 **算力卸载** | 手机分担轻量计算任务 |
| 🔋 **电池监控** | 电量/温度实时告警 |

---

## 🏗️ 架构

```
┌─────────────────────────────────────────────────────────┐
│                      PC (Windows)                        │
│                                                         │
│  ┌─────────────┐    ┌──────────────┐   ┌────────────┐  │
│  │  Dashboard  │    │ BridgeDaemon │   │ InputLeap  │  │
│  │  (HTTP/WS)  │    │  (Python)    │   │  键鼠跨屏  │  │
│  └─────────────┘    └──────┬───────┘   └────────────┘  │
│                            │                             │
│              ┌─────────────┼─────────────┐              │
│              │             │             │              │
│         ADB WiFi       WebSocket     MQTT Broker        │
│              │             │             │              │
└──────────────┼─────────────┼─────────────┼──────────────┘
               │             │             │
        ┌──────┴──────┐ ┌────┴────┐ ┌──────┴──────┐
        │   小米8     │ │ 伴侣App │ │  多设备扩展  │
        │  Android 15 │ │ (WS)   │ │   (MQTT)   │
        │             │ │        │ │            │
        │ Camera2 API │ │ 通知   │ │  传感器    │
        │ Sensor APIs │ │ 剪贴板 │ │  音频      │
        │ AudioRecord │ │ 通话   │ │  算力      │
        └─────────────┘ └────────┘ └────────────┘
```

**通信通道（三选一，自动降级）：**

| 优先级 | 通道 | 延迟 | 说明 |
|--------|------|------|------|
| 1 | 伴侣 App (WebSocket) | **0ms** | 实时事件推送，无需轮询 |
| 2 | ADB Shell | ~100ms | USB/WiFi 连接，备用 |
| 3 | MQTT Broker | ~200ms | 多设备消息路由 |

---

## 📁 项目结构

```
project-fusion/
├── bridge/                          # PC 端 Bridge Daemon (Python)
│   ├── main.py                      # 主入口 (9 阶段启动 + 双通道架构)
│   ├── config.py                    # YAML 配置管理
│   ├── dashboard_server.py          # HTTP + WebSocket Dashboard
│   ├── modules/                     # 功能模块
│   │   ├── clipboard_bridge.py      # 剪贴板双向同步
│   │   ├── notification_bridge.py   # 通知转发 + Toast
│   │   ├── file_bridge.py           # ADB 文件传输
│   │   ├── phone_bridge.py          # 通话控制
│   │   ├── sms_bridge.py            # 短信读取/发送
│   │   ├── screenshot_bridge.py     # 截图
│   │   ├── battery_bridge.py        # 电池监控
│   │   ├── audio_bridge.py          # 音频桥接
│   │   ├── video_bridge.py          # 视频投射
│   │   ├── handoff_bridge.py        # URL 接力
│   │   ├── hotkey_manager.py        # 全局热键
│   │   ├── dnd_manager.py          # 免打扰模式
│   │   ├── proximity_detector.py    # 蓝牙近场检测
│   │   ├── mqtt_bridge.py           # MQTT Broker
│   │   ├── sound_monitor.py         # 声音监测
│   │   ├── camera_ws_bridge.py      # 摄像头帧流接收
│   │   ├── command_bridge.py        # 命令通道 + 场景引擎
│   │   ├── distributed_scheduler.py # 分布式任务调度
│   │   ├── pc_online_broadcaster.py # PC 在线广播
│   │   ├── smart_night_light.py     # 智能夜灯
│   │   └── whole_home_audio.py      # 全屋音响
│   └── listeners/                   # 实时监听器
│       ├── ws_client.py             # WebSocket 客户端 (伴侣 App)
│       ├── clipboard_hook.py        # Win32 剪贴板 Hook (零延迟)
│       ├── mouse_hook.py            # Win32 全局鼠标 Hook
│       ├── window_focus.py          # Scrcpy 焦点监听
│       └── kde_connect.py          # KDE Connect 监听
│
├── android-companion/               # Android 伴侣 App
│   ├── app/src/main/java/com/fusion/companion/
│   │   ├── MainActivity.java        # 主界面
│   │   ├── FusionWebSocketServer.java
│   │   ├── CameraStreamer.java      # Camera2 API 帧流
│   │   ├── AudioStreamer.java       # AudioRecord 麦克风
│   │   ├── SensorCollector.java     # 传感器采集
│   │   ├── MQTTClientService.java   # MQTT 客户端
│   │   ├── MQTTBrokerService.java   # MQTT Broker (手机端)
│   │   └── service/
│   │       ├── FusionBridgeService.java        # 前台服务
│   │       └── FusionNotificationListener.java  # 通知监听
│   └── build.gradle
│
├── dashboard/                       # Dashboard 前端 (单 HTML)
│   └── index.html                   # 8 Tab 控制面板
│
├── termux-bridge/                   # Termux 桥接 (可选)
│
├── SCENARIOS.md                     # 落地场景规划
├── Project-Fusion-TRD.md            # 技术实施需求文档
├── config.yaml                      # 用户配置
├── requirements.txt                # Python 依赖
└── LICENSE                         # MIT License
```

---

## 🚀 快速开始

### 前提

- **Android 手机**：USB 调试已开启，或同一局域网内 WiFi ADB
- **Windows PC**：Python 3.10+
- **Scrcpy**（桌面版，已放入 PATH 或同目录）

### 安装

```bash
# PC 端
git clone https://github.com/jenaswift4-byte/project-fusion.git
cd project-fusion/bridge
pip install -r requirements.txt

# Android 端（可选，推荐安装伴侣 App）
# 1. 用 Android Studio 打开 android-companion/
# 2. Build → Run
# 3. 开启通知访问权限
```

### 启动

```bash
cd bridge
python main.py
```

打开 http://localhost:8080 查看 Dashboard。

### 模块选择

```bash
python main.py                                    # 全功能
python main.py --modules clipboard,notification  # 只启动指定模块
python main.py --no-companion                     # 强制纯 ADB 模式
python main.py --autostart                        # 注册开机自启
```

---

## ⚙️ 配置

编辑 `bridge/config.yaml`：

```yaml
devices:
  "7254adb5":
    name: "客厅监控"
    location: "客厅"
    wifi_ip: "192.168.40.84"

clipboard:
  enabled: true
  sync_bidirectional: true
  auto_open_urls: true

notification:
  enabled: true
  dnd_mode: auto  # auto: 全屏时缓存，退出推送

mqtt:
  broker_port: 1883
  pc_broker_enabled: true
```

---

## 🔧 Android 伴侣 App

伴侣 App 通过 WebSocket 提供系统级实时事件，无需轮询：

| 功能 | 实现方式 |
|------|----------|
| 通知实时推送 | NotificationListenerService |
| 剪贴板实时 | OnPrimaryClipChangedListener |
| 通话状态 | TelephonyCallback |
| 摄像头帧流 | Camera2 API → JPEG → Base64 → WS |
| 麦克风采集 | AudioRecord → PCM → WS |
| 传感器数据 | SensorManager 轮询 + flush |
| MQTT 客户端 | 订阅 /cmd 主题，执行动作 |

安装后首次使用需要：
1. 开启**通知访问权限**（设置 → 无障碍 → 找到 App）
2. 开启**后台弹出界面**权限（MIUI 额外需要）

---

## 📦 CI/CD

APK 通过 GitHub Actions 自动构建：

- 每次 push 到 `main` 分支触发构建
- 构建产物 (artifact) 可直接从 Actions 页下载
- Android 端零配置，无需本地 Android SDK

---

## 🤝 贡献

欢迎 Issue 和 Pull Request！如果你有好的场景想法或发现了 Bug，请告诉我们。

---

## 📝 项目背景

Project Fusion 起源于"废旧手机能做什么"这个问题。一台小米8（Android 15）被改造为分布式传感器节点，与 Windows PC 通过 ADB/MQTT/WebSocket 组成协同系统。

**核心思路**：不造新轮子，只做胶水——串联 Camera2 API、MQTT Broker、WebSocket、ADB 这些成熟技术，让废旧设备发挥余热。

---

*不造轮子，只做胶水。*
