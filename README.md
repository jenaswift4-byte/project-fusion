# 🔀 Project Fusion — 跨设备系统级融合 (Android + Windows)

基于 **KDE Connect + Scrcpy + Input Leap + Android 伴侣 App** + Python 桥接中间件，实现非苹果生态的"原生级"无缝流转。

## ✨ 功能一览

| 模块 | 功能 | 通道 | 延迟 |
|------|------|------|------|
| 🖥️ Scrcpy 投屏 | 手机画面实时投射，无边框窗口 | ADB | <50ms |
| 📋 剪贴板同步 | 双向实时同步 + 链接自动打开 + 图片保存 | **WebSocket / Win32 Hook** | **0ms** |
| 🔔 通知桥接 | 通知实时推送 + Toast + 自动置顶 + 包名映射 | **WebSocket** | **0ms** |
| 📁 文件传输 | ADB push/pull + APK 安装 | ADB | - |
| 📞 通话控制 | 来电实时弹窗 + 拨号/挂断/接听 | **WebSocket** | **0ms** |
| 📡 KDE Connect | 设备发现 + 加密通道 + 通知/剪贴板/文件/响铃 | KDE Connect | <1s |
| ⌨️ 键鼠流转 | Input Leap + **全局鼠标钩子边缘检测** | Win32 Hook | **0ms** |
| 🖱️ 屏幕边缘穿越 | 鼠标滑到右边缘 → 自动聚焦 Scrcpy | **Win32 Hook** | **0ms** |
| 📌 系统托盘 | 右键菜单 + 模块开关 + 快捷操作 | - | - |
| 🔄 开机自启 | 注册/取消 Windows 开机自启动 | - | - |
| 🤖 Android 伴侣 App | NotificationListenerService + ClipboardManager + TelephonyCallback + WebSocket Server | - | - |

### 双通道架构

```
手机事件 ──┬── WebSocket (伴侣 App，零延迟推送) ──► Bridge Daemon
           └── ADB shell  (备用，轮询)        ──► Bridge Daemon

PC 事件  ──┬── Win32 Hook (零延迟回调)  ──► Bridge Daemon
           └── pyperclip (备用，轮询)   ──► Bridge Daemon
```

- **有伴侣 App** → 全部实时，零轮询，零 CPU 开销
- **无伴侣 App** → 自动降级到 ADB 轮询，仍然可用

## 🚀 快速开始

### 前提

1. **Android 手机**：开启 USB 调试，USB 连接电脑
2. **Windows PC**：Python 3.10+，桌面已有 `scrcpy-win64-v3.3.4/`

### 一键启动

```
双击 start_fusion.bat
```

### 命令行

```bash
python -m bridge                    # 全功能启动 (自动检测伴侣 App)
python -m bridge --no-companion     # 强制纯 ADB 模式
python -m bridge --no-scrcpy        # 不启动 Scrcpy 窗口
python -m bridge --no-tray          # 不显示托盘
python -m bridge --list-devices     # 查看设备
python -m bridge --autostart        # 注册开机自启
```

## 📱 Android 伴侣 App (推荐)

伴侣 App 提供**系统级实时事件**，无需轮询：

1. 用 Android Studio 打开 `android-companion/` 目录
2. 编译安装到手机
3. 打开 App → 开启通知访问权限 → 启动服务
4. PC 端运行 `python -m bridge`，自动通过 ADB forward 连接

**伴侣 App 提供：**
- 通知实时推送 (NotificationListenerService)
- 剪贴板实时回调 (OnPrimaryClipChangedListener)
- 通话状态实时推送 (TelephonyCallback)
- 手机响铃 (找手机)
- 远程打开链接

## 📁 项目结构

```
万物互联/
├── bridge/                           # Python Bridge Daemon
│   ├── main.py                       # 主入口 (9 阶段启动)
│   ├── config.py                     # 配置管理
│   ├── modules/                      # 功能模块
│   │   ├── clipboard_bridge.py       # 剪贴板增强
│   │   ├── notification_bridge.py    # 通知桥接
│   │   ├── file_bridge.py            # 文件传输
│   │   └── phone_bridge.py           # 通话控制
│   ├── listeners/                    # 实时监听器
│   │   ├── ws_client.py              # WebSocket 客户端 (伴侣 App)
│   │   ├── clipboard_hook.py         # Win32 剪贴板 Hook (零延迟)
│   │   ├── mouse_hook.py             # Win32 全局鼠标 Hook + 边缘检测
│   │   ├── window_focus.py           # Scrcpy 焦点监听
│   │   └── kde_connect.py            # KDE Connect 监听
│   ├── dispatchers/                  # 调度器
│   │   ├── input_leap.py             # Input Leap 键鼠跨屏
│   │   ├── tray_icon.py              # 系统托盘
│   │   └── autostart.py              # 开机自启
│   └── utils/                        # 工具
│       ├── scrcpy_ctrl.py            # Scrcpy 进程 + 窗口管理
│       ├── win32_clipboard.py        # Win32 原生剪贴板
│       └── win32_toast.py            # Windows Toast
├── android-companion/                # Android 伴侣 App
│   └── app/src/main/java/com/fusion/companion/
│       ├── MainActivity.java         # 主界面
│       ├── FusionWebSocketServer.java # WebSocket Server
│       └── service/
│           ├── FusionBridgeService.java     # 前台服务 + 剪贴板/通话
│           └── FusionNotificationListener.java # 通知监听
├── config.yaml                       # 用户配置
├── start_fusion.bat                  # 一键启动
├── stop_fusion.bat                   # 一键停止
├── requirements.txt                  # Python 依赖
├── Project-Fusion-TRD.md             # 技术实施文档
└── README.md
```

## ⚙️ 配置

编辑 `config.yaml`：

```yaml
kde_connect:
  enabled: true             # 启用 KDE Connect
input_leap:
  enabled: true             # 启用键鼠跨屏
```

## 🔗 依赖

### Python

```
pip install -r requirements.txt
```

### Android (可选)

Android Studio 编译 `android-companion/` 项目

### 外部工具

| 工具 | 用途 | 安装 |
|------|------|------|
| Scrcpy | 投屏 + 反向控制 | 桌面已有 |
| KDE Connect | 通信 + 通知 + 剪贴板 | Microsoft Store (可选) |
| Input Leap | 键鼠跨屏 | GitHub Releases (可选) |

---

*不造轮子，只做胶水。系统级融合，零轮询。*
