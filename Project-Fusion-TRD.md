# 🔀 Project Fusion — 跨设备无缝融合方案 (Android + Windows)

> **技术实施需求文档 (TRD) v1.0**
> 目标读者：workbuddy (执行 Agent) / 开发者
> 日期：2026-04-11

---

## 一、项目概述

### 1.1 一句话目标

基于 **KDE Connect + Scrcpy + Input Leap** 三大开源组件，编写 Python 桥接中间件，在 Android 手机与 Windows PC 之间实现**操作系统级**的无缝流转体验——接近但超越 Apple Handoff / Universal Clipboard。

### 1.2 核心策略

> **不造轮子，只做胶水。**

| 层级 | 选型 | 原因 |
|------|------|------|
| 通信/发现层 | KDE Connect | 协议开放 (JSON/TCP + TLS)、全平台、插件化 |
| 视觉/控制层 | Scrcpy | 极低延迟 (<50ms)、命令行友好、支持反向控制 |
| 键鼠流转层 | Input Leap | Barrier 的活跃继任者，支持多显示器模拟 |
| 胶水/逻辑层 | 自定义 Python 守护进程 | 串联各组件，实现"智能流转" |

### 1.3 用户体验目标

| 场景 | 期望行为 |
|------|----------|
| 手机复制文本 | Windows 剪贴板即时同步，Ctrl+V 直接粘贴 |
| 手机收到通知 | Windows 弹出原生 Toast，点击后 Scrcpy 窗口自动置顶并跳转对应 App |
| 手机复制图片 | Windows 自动保存到指定目录并弹窗预览 |
| 手机复制链接 | Windows 后台浏览器自动打开 |
| 鼠标滑到屏幕边缘 | 焦点无缝切入 Scrcpy 窗口，键盘/鼠标直接操控手机 |
| 鼠标离开 Scrcpy 窗口 | 自动暂停触控转发，防止误触 |

---

## 二、架构设计

### 2.1 整体架构图

```
┌──────────────────────────────┐           ┌──────────────────────────────────────┐
│       Android Device         │           │            Windows PC                │
│                              │           │                                      │
│  ┌─────────────────────┐     │           │  ┌─────────────────────────────┐     │
│  │  KDE Connect App    │◄────┼──TLS─────►│  │  KDE Connect Daemon         │     │
│  │  (通知/文件/剪贴板)  │     │  TCP:1716  │  │  (DBus / JSON Events)       │     │
│  └─────────┬───────────┘     │           │  └──────────┬──────────────────┘     │
│            │                 │           │             │                        │
│            ▼                 │           │             ▼                        │
│  ┌─────────────────────┐     │           │  ┌─────────────────────────────┐     │
│  │  Scrcpy Server      │◄────┼──ADB─────►│  │  Scrcpy Client (Video)      │     │
│  │  (由 adb 启动)       │     │ USB/WiFi  │  │  --window-title "Phone"     │     │
│  └─────────────────────┘     │           │  │  --no-border --always-on-top│     │
│                              │           │  └──────────┬──────────────────┘     │
│                              │           │             │                        │
│                              │           │             ▼                        │
│                              │           │  ┌─────────────────────────────────┐ │
│                              │           │  │   🤖 Bridge Daemon (Python)     │ │
│                              │           │  │                                 │ │
│                              │           │  │  ┌─── Event Listener ─────────┐ │ │
│                              │           │  │  │ KDE Connect DBus/Socket   │ │ │
│                              │           │  │  │ → clipboard / notification │ │ │
│                              │           │  │  │ → telephony / battery     │ │ │
│                              │           │  │  └───────────┬───────────────┘ │ │
│                              │           │  │              ▼                 │ │
│                              │           │  │  ┌─── Action Dispatcher ─────┐ │ │
│                              │           │  │  │ Scrcpy Window Manager     │ │ │
│                              │           │  │  │ System Clipboard API      │ │ │
│                              │           │  │  │ Windows Toast Notification│ │ │
│                              │           │  │  │ Input Leap Config Trigger │ │ │
│                              │           │  │  └───────────────────────────┘ │ │
│                              │           │  └─────────────────────────────────┘ │
└──────────────────────────────┘           └──────────────────────────────────────┘
```

### 2.2 数据流

```
手机事件 (通知/剪贴板/来电)
    │
    ▼
KDE Connect App ──TLS/TCP──► KDE Connect Daemon (Windows)
    │
    ▼
Bridge Daemon 监听 DBus 信号
    │
    ├──► 通知 → Windows Toast + Scrcpy 窗口置顶
    ├──► 剪贴板(文本) → Win32 Clipboard API
    ├──► 剪贴板(图片) → 保存文件 + 预览弹窗
    ├──► 剪贴板(URL) → 默认浏览器打开
    └──► 来电 → 全屏弹窗提醒
```

---

## 三、开源组件详细说明

### 3.1 KDE Connect

- **仓库**: https://invent.kde.org/network/kdeconnect-kde
- **Android**: https://f-droid.org/en/packages/org.kde.kdeconnect_tp/ 或 Google Play
- **Windows**: https://kdeconnect.kde.org/ (Microsoft Store 或 Winget)
- **协议端口**: TCP 1716-1764 (TLS 加密)
- **本地 API**: Windows 上通过 DBus (或 KDE Connect CLI `kdeconnect-cli`) 暴露事件

**关键 CLI 命令**:
```bash
# 列出已配对设备
kdeconnect-cli --list-devices

# 发送剪贴板
kdeconnect-cli -d <device_id> --ping

# 获取通知
kdeconnect-cli -d <device_id> --list-notifications
```

**DBus 接口** (Windows 上需启用 DBus over TCP 或使用 `kdeconnect-cli` 包装):
```
/org/kde/kdeconnect/devices/<device_id>/clipboard
/org/kde/kdeconnect/devices/<device_id>/notifications
/org/kde/kdeconnect/devices/<device_id>/battery
/org/kde/kdeconnect/devices/<device_id>/telephony
```

### 3.2 Scrcpy

- **仓库**: https://github.com/Genymobile/scrcpy
- **Windows 安装**: `winget install Genymobile.scrcpy` 或 `scoop install scrcpy`
- **前提**: ADB 可用，手机开启 USB 调试

**推荐启动参数**:
```bash
scrcpy ^
  --window-title="Phone" ^
  --window-x=1920 --window-y=0 ^
  --window-width=480 --window-height=1020 ^
  --no-border ^
  --always-on-top ^
  --max-fps=60 ^
  --video-bit-rate=8M ^
  --video-codec=h264 ^
  --turn-screen-off ^
  --stay-awake ^
  --no-audio ^
  --clipboard-autocopy
```

**窗口管理关键**:
- `--no-border` 使窗口无边框，方便视觉融合
- `--always-on-top` 保证手机窗口不被遮挡
- `--turn-screen-off` 投屏时关闭手机屏幕省电
- Scrcpy 支持 `--window-x/y/width/height` 精确定位

### 3.3 Input Leap

- **仓库**: https://github.com/input-leap/input-leap
- **Windows 安装**: 从 GitHub Releases 下载 MSI 安装包
- **角色**: Windows 作为 Server，将 Scrcpy 窗口区域定义为虚拟屏幕

**配置思路**:
```
[Windows Server]
Screen: Main (1920x1080, 0,0)
Screen: Phone (480x1020, 1920,0)  ← 映射到 Scrcpy 窗口位置

[Android Client]
(通过 Scrcpy 窗口接收输入，不需要在 Android 端装 Input Leap)
```

> **注意**: Input Leap 的传统用法是跨物理设备，这里我们创新性地将 Scrcpy 窗口所在区域定义为"第二屏幕"，实现鼠标从主屏幕边缘滑入 Scrcpy 窗口的无缝体验。

---

## 四、Bridge Daemon 设计

### 4.1 目录结构

```
Project-Fusion/
├── bridge/
│   ├── __init__.py
│   ├── main.py              # 入口 & 事件循环
│   ├── config.py             # 配置管理
│   ├── listeners/
│   │   ├── kde_connect.py    # KDE Connect 事件监听
│   │   └── window_focus.py   # Scrcpy 窗口焦点监听
│   ├── dispatchers/
│   │   ├── clipboard.py      # 剪贴板同步 & 增强
│   │   ├── notification.py   # 通知中继 & Toast
│   │   ├── window_manager.py # Scrcpy 窗口管理
│   │   └── input_leap.py     # Input Leap 控制
│   └── utils/
│       ├── win32_clipboard.py # Win32 剪贴板操作
│       ├── win32_toast.py     # Windows 原生通知
│       └── scrcpy_ctrl.py     # Scrcpy 进程管理
├── config.yaml               # 用户配置
├── start_fusion.bat          # 一键启动脚本
├── requirements.txt
└── README.md
```

### 4.2 核心模块伪代码

#### 4.2.1 主事件循环

```python
# bridge/main.py
import asyncio
from listeners.kde_connect import KDEConnectListener
from dispatchers.clipboard import ClipboardDispatcher
from dispatchers.notification import NotificationDispatcher
from dispatchers.window_manager import ScrcpyWindowManager

class BridgeDaemon:
    def __init__(self, config):
        self.config = config
        self.scrcpy = ScrcpyWindowManager(config)
        self.clipboard = ClipboardDispatcher(config)
        self.notification = NotificationDispatcher(config)
        self.kde_listener = KDEConnectListener(config)

    async def run(self):
        # 1. 启动 Scrcpy 进程
        await self.scrcpy.start()

        # 2. 注册事件处理器
        self.kde_listener.on("clipboard", self.clipboard.handle)
        self.kde_listener.on("notification", self.notification.handle)
        self.kde_listener.on("telephony", self.notification.handle_call)

        # 3. 启动监听
        await self.kde_listener.start()

        # 4. 保持运行
        await asyncio.Event().wait()

if __name__ == "__main__":
    config = load_config("config.yaml")
    daemon = BridgeDaemon(config)
    asyncio.run(daemon.run())
```

#### 4.2.2 KDE Connect 事件监听

```python
# bridge/listeners/kde_connect.py
# 方案 A: 通过 kdeconnect-cli 轮询 (简单可靠)
# 方案 B: 通过 DBus 信号订阅 (低延迟，需 DBus on Windows)

import subprocess
import json
from typing import Callable

class KDEConnectListener:
    def __init__(self, config):
        self.device_id = config["kde_connect"]["device_id"]
        self.handlers = {}
        self._last_clipboard = ""

    def on(self, event_type: str, handler: Callable):
        self.handlers.setdefault(event_type, []).append(handler)

    async def start(self):
        """轮询 KDE Connect 状态变化"""
        while True:
            # 方案 A: 使用 kdeconnect-cli 获取通知
            result = subprocess.run(
                ["kdeconnect-cli", "-d", self.device_id,
                 "--list-notifications", "--json"],
                capture_output=True, text=True
            )
            if result.stdout:
                notifications = json.loads(result.stdout)
                for handler in self.handlers.get("notification", []):
                    await handler(notifications)

            await asyncio.sleep(1)  # 轮询间隔
```

#### 4.2.3 剪贴板增强

```python
# bridge/dispatchers/clipboard.py
import os
import requests
from utils.win32_clipboard import set_clipboard_text, set_clipboard_image

class ClipboardDispatcher:
    def __init__(self, config):
        self.image_dir = config["paths"]["clipboard_images"]

    async def handle(self, data: dict):
        content = data.get("content", "")
        mime_type = data.get("mimeType", "text/plain")

        if mime_type.startswith("image/"):
            # 图片 → 保存 + 预览
            path = os.path.join(self.image_dir, f"clip_{int(time.time())}.png")
            with open(path, "wb") as f:
                f.write(data["binary"])
            set_clipboard_image(path)
            self._show_preview(path)

        elif content.startswith(("http://", "https://")):
            # 链接 → 浏览器打开 + 剪贴板
            set_clipboard_text(content)
            os.startfile(content)  # Windows 默认浏览器

        else:
            # 普通文本 → 系统剪贴板
            set_clipboard_text(content)
```

#### 4.2.4 Scrcpy 窗口管理

```python
# bridge/dispatchers/window_manager.py
import ctypes
import subprocess

class ScrcpyWindowManager:
    SCRCPY_WINDOW_TITLE = "Phone"

    def __init__(self, config):
        self.config = config
        self.process = None

    async def start(self):
        cmd = [
            "scrcpy",
            f"--window-title={self.SCRCPY_WINDOW_TITLE}",
            f"--window-x={self.config['scrcpy']['x']}",
            f"--window-y={self.config['scrcpy']['y']}",
            f"--window-width={self.config['scrcpy']['width']}",
            f"--window-height={self.config['scrcpy']['height']}",
            "--no-border",
            "--always-on-top",
            "--max-fps=60",
            "--video-bit-rate=8M",
            "--turn-screen-off",
            "--stay-awake",
        ]
        self.process = subprocess.Popen(cmd)

    def bring_to_front(self):
        """将 Scrcpy 窗口置顶"""
        hwnd = ctypes.windll.user32.FindWindowW(
            None, self.SCRCPY_WINDOW_TITLE
        )
        if hwnd:
            ctypes.windll.user32.SetForegroundWindow(hwnd)
            ctypes.windll.user32.BringWindowToTop(hwnd)

    def minimize(self):
        """最小化 Scrcpy 窗口"""
        hwnd = ctypes.windll.user32.FindWindowW(
            None, self.SCRCPY_WINDOW_TITLE
        )
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 6)  # SW_MINIMIZE
```

### 4.3 配置文件

```yaml
# config.yaml
kde_connect:
  device_id: "auto"  # "auto" 自动发现，或填入具体 device_id
  poll_interval_ms: 1000

scrcpy:
  enabled: true
  x: 1440          # 窗口位置 (右侧)
  y: 0
  width: 480
  height: 1080
  max_fps: 60
  bit_rate: "8M"
  always_on_top: true
  no_border: true
  turn_screen_off: true

input_leap:
  enabled: false    # Phase 3 再启用
  config_path: "%APPDATA%/InputLeap/InputLeap.conf"

clipboard:
  sync_to_windows: true
  image_save_dir: "D:\\Fusion\\Clipboard"
  auto_open_urls: true

notification:
  show_toast: true
  auto_focus_scrcpy: true  # 收到通知自动置顶 Scrcpy 窗口
  ringtone_path: ""        # 来电铃声

paths:
  clipboard_images: "D:\\Fusion\\Clipboard"
  logs: "D:\\Fusion\\Logs"
```

---

## 五、执行计划 (workbuddy Task List)

### Phase 1: 基础连通性验证 ✅

**目标**: 确保 KDE Connect 和 Scrcpy 能独立正常工作。

| # | 任务 | 具体操作 | 验证标准 |
|---|------|----------|----------|
| 1.1 | 安装 KDE Connect (Windows) | `winget install KDE.KDEConnect` 或从 Microsoft Store 安装 | `kdeconnect-cli --version` 有输出 |
| 1.2 | 安装 KDE Connect (Android) | 从 Google Play / F-Droid 安装 `KDE Connect` | 手机和电脑能互相发现 |
| 1.3 | 设备配对 | 在两端点击"请求配对"，确认 RSA 指纹 | `kdeconnect-cli --list-devices` 显示 `已配对` |
| 1.4 | 验证剪贴板同步 | 手机复制文本 → 检查 Windows 剪贴板 | Ctrl+V 能粘贴手机复制的文本 |
| 1.5 | 安装 Scrcpy | `winget install Genymobile.scrcpy` | `scrcpy --version` 有输出 |
| 1.6 | ADB 连接 | USB 连接手机，开启 USB 调试 | `adb devices` 显示设备 |
| 1.7 | 验证 Scrcpy 投屏 | `scrcpy --window-title="Phone"` | 手机画面正常显示在 Windows |
| 1.8 | WiFi ADB (可选) | `adb tcpip 5555` → `adb connect <手机IP>:5555` | 拔掉 USB 后 Scrcpy 仍可投屏 |

### Phase 2: Bridge Daemon MVP (核心胶水)

**目标**: 编写 Python 中间件，实现剪贴板增强 + 通知中继 + Scrcpy 窗口管理。

| # | 任务 | 具体操作 | 验证标准 |
|---|------|----------|----------|
| 2.1 | 初始化项目 | 创建 `Project-Fusion/` 目录结构，`pip install pywin32 pyyaml` | 目录结构完整 |
| 2.2 | 编写配置加载模块 | `config.py` 读取 `config.yaml` | 能正确解析所有配置项 |
| 2.3 | 编写 KDE Connect 监听器 | `kde_connect.py` 通过 `kdeconnect-cli` 轮询事件 | 能捕获通知和剪贴板变化 |
| 2.4 | 编写 Win32 剪贴板工具 | `win32_clipboard.py` 使用 `pywin32` 操作系统剪贴板 | 能读写文本/图片到剪贴板 |
| 2.5 | 编写 Windows Toast 工具 | `win32_toast.py` 使用 `win10toast` 或 XML Toast | 能弹出原生通知 |
| 2.6 | 编写剪贴板 Dispatcher | `clipboard.py` 区分文本/链接/图片 | 三种类型分别正确处理 |
| 2.7 | 编写通知 Dispatcher | `notification.py` 收到通知时弹出 Toast + 置顶 Scrcpy | 点击 Toast 后 Scrcpy 窗口置顶 |
| 2.8 | 编写 Scrcpy 窗口管理器 | `window_manager.py` 启动/置顶/最小化 Scrcpy | 程序化控制 Scrcpy 窗口 |
| 2.9 | 整合主循环 | `main.py` 串联所有模块 | 一键启动，所有功能正常 |

### Phase 3: 键鼠流转融合

**目标**: 实现鼠标在 Windows 和 Scrcpy 窗口间的无缝移动。

| # | 任务 | 具体操作 | 验证标准 |
|---|------|----------|----------|
| 3.1 | 安装 Input Leap | 从 GitHub Releases 下载安装 | `input-leap` 命令可用 |
| 3.2 | 配置虚拟屏幕 | 将 Scrcpy 窗口区域定义为 Input Leap 的第二个 Screen | 配置文件正确 |
| 3.3 | 编写 Input Leap 控制模块 | `input_leap.py` 程序化启停 Input Leap | 可通过 Python 启停服务 |
| 3.4 | 实现焦点感知 | 监听 Scrcpy 窗口获得/失去焦点事件 | 鼠标进入/离开窗口时有日志 |
| 3.5 | 防误触逻辑 | 焦点离开 Scrcpy 时，自动暂停触控转发 | 离开后键盘输入不影响手机 |

### Phase 4: 打包 & 一键启动

**目标**: 封装为开箱即用的启动脚本。

| # | 任务 | 具体操作 | 验证标准 |
|---|------|----------|----------|
| 4.1 | 编写启动脚本 | `start_fusion.bat` 按顺序启动所有组件 | 双击即启动完整方案 |
| 4.2 | 编写停止脚本 | `stop_fusion.bat` 优雅关闭所有进程 | 无残留进程 |
| 4.3 | 托盘图标 (可选) | 使用 `pystray` 实现系统托盘 | 托盘图标可右键退出 |
| 4.4 | 开机自启 (可选) | 注册为 Windows 启动项 | 重启后自动运行 |
| 4.5 | 编写用户文档 | `README.md` 安装和使用说明 | 新用户能按文档操作 |

---

## 六、关键约束 & 避坑指南

### 6.1 绝对不要做的事

| ❌ 禁止 | 原因 |
|---------|------|
| 自己写通信/加密协议 | KDE Connect 已有成熟的 TLS 握手，自己写必有漏洞 |
| 修改 KDE Connect / Scrcpy 源码 | 上游更新会覆盖，应通过外部 API 集成 |
| 全屏锁定 Scrcpy | 必须用无边框窗口模式，否则无法和 Input Leap 配合 |
| 在 Android 端安装额外守护进程 | 尽量只依赖 KDE Connect App + ADB，减少手机端侵入 |

### 6.2 已知坑点

| 坑 | 解决方案 |
|----|----------|
| KDE Connect 在 Windows 上没有原生 DBus | 使用 `kdeconnect-cli` 命令行包装，或安装 DBus for Windows |
| Scrcpy 窗口标题可能变化 | 用 `--window-title` 固定标题，用 `FindWindowW` 查找 |
| ADB WiFi 连接不稳定 | 提供 USB 降级方案，WiFi 断开时自动提示重连 |
| Windows 剪贴板历史可能覆盖 KDE 的数据 | 使用 `SetClipboardData` 而非追加，避免冲突 |
| Input Leap 配置复杂 | 先用 Python 脚本模拟鼠标穿越（检测边缘位置→聚焦窗口），再升级到 Input Leap |
| `pywin32` 的剪贴板 API 需要 COM 初始化 | 在主线程调用 `pythoncom.CoInitialize()` |

### 6.3 性能要求

| 指标 | 目标值 |
|------|--------|
| Scrcpy 端到端延迟 | < 80ms (WiFi), < 40ms (USB) |
| 剪贴板同步延迟 | < 2s |
| 通知推送延迟 | < 3s |
| Bridge Daemon 内存占用 | < 50MB |
| Bridge Daemon CPU 占用 | < 2% (空闲时) |

---

## 七、技术参考

### 7.1 开源项目链接

| 项目 | 地址 | License |
|------|------|---------|
| KDE Connect | https://invent.kde.org/network/kdeconnect-kde | GPL-2.0 |
| KDE Connect (Android) | https://invent.kde.org/network/kdeconnect-android | GPL-2.0 |
| Scrcpy | https://github.com/Genymobile/scrcpy | Apache-2.0 |
| Input Leap | https://github.com/input-leap/input-leap | GPL-2.0 |
| Barrier (Input Leap 前身) | https://github.com/debauchee/barrier | GPL-2.0 |

### 7.2 关键文档

- **KDE Connect 协议**: https://community.kde.org/KDEConnect
- **KDE Connect DBus API**: https://invent.kde.org/network/kdeconnect-kde/-/blob/master/doc/kdeconnect_dbus_api.md
- **Scrcpy 文档**: https://github.com/Genymobile/scrcpy/blob/master/README.md
- **Input Leap 配置**: https://github.com/input-leap/input-leap/wiki
- **Win32 Clipboard API**: https://learn.microsoft.com/en-us/windows/win32/dataxchg/clipboard
- **Windows Toast Notifications**: https://learn.microsoft.com/en-us/windows/apps/design/shell/tiles-and-notifications/toast-notifications-overview

### 7.3 Python 依赖

```
# requirements.txt
pywin32>=306        # Win32 API (剪贴板, 窗口管理)
pyyaml>=6.0         # 配置文件解析
win10toast>=0.9     # Windows Toast 通知 (或换用 winotify)
asyncio             # 异步事件循环 (标准库)
psutil>=5.9         # 进程管理
```

---

## 八、MVP 验收标准

当以下场景全部通过时，Phase 2 (MVP) 即为完成：

- [ ] **场景 1**: 手机复制"Hello Fusion" → Windows 上 Ctrl+V 粘贴出"Hello Fusion"
- [ ] **场景 2**: 手机复制一张图片 → Windows 剪贴板包含该图片，D:\Fusion\Clipboard\ 下有文件
- [ ] **场景 3**: 手机复制 `https://example.com` → Windows 默认浏览器打开该链接
- [ ] **场景 4**: 手机收到微信通知 → Windows 弹出 Toast，且 Scrcpy 窗口自动置顶
- [ ] **场景 5**: 双击 `start_fusion.bat` → KDE Connect + Scrcpy + Bridge Daemon 全部启动
- [ ] **场景 6**: 运行 `stop_fusion.bat` → 所有进程干净退出

---

## 九、未来扩展 (v2.0+)

| 功能 | 依赖 | 优先级 |
|------|------|--------|
| 文件拖拽 (手机↔Windows) | KDE Connect 文件传输 API | 高 |
| 手机来电全屏提醒 | KDE Connect telephony 插件 | 中 |
| 近场自动发现 (蓝牙/WiFi) | KDE Connect + 自定义触发 | 中 |
| 多设备支持 | 动态 Scrcpy 窗口管理 | 低 |
| 系统托盘 UI | pystray + PyQt | 低 |
| Windows → 手机剪贴板反向同步 | KDE Connect 已支持 | 高 (已有) |

---

*文档结束。workbuddy，开始干活吧。*
