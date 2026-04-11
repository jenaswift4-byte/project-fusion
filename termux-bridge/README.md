# Fusion Termux Bridge

Termux 端的 Project Fusion 桥接方案，**无需编译 APK** 即可使用大部分功能。

## 功能对比

| 功能 | Termux Bridge | 伴侣 App |
|------|:---:|:---:|
| 剪贴板双向同步 | ✅ | ✅ |
| URL 自动打开 | ✅ | ✅ |
| 铃声控制 | ✅ | ✅ |
| WebSocket 通信 | ✅ | ✅ |
| 通知推送到 PC | ❌ | ✅ |
| 来电监听 | ❌ | ✅ |
| 开机自启 | ⚠️ 需 Boot 插件 | ✅ |

> **最佳方案**: Termux Bridge + 伴侣 App 混合使用
> - 伴侣 App 负责通知和来电 (系统级权限)
> - Termux Bridge 负责剪贴板和其他功能
> - PC Bridge 自动处理双通道

## 快速安装

```bash
# 1. 将 termux-bridge/ 目录传到手机
#    方式 A: 通过 ADB
adb push . /data/data/com.termux/files/home/fusion-bridge/
#    方式 B: 通过 scp (需同一网络)
scp -r . phone-ip:/data/data/com.termux/files/home/fusion-bridge/

# 2. 在 Termux 中安装
cd ~/fusion-bridge
bash install.sh

# 3. 启动
bash start.sh
```

## 手动安装

```bash
# 安装依赖
pkg install python termux-api
pip install websockets

# 启动
python fusion_termux_bridge.py
```

## PC 端配置

在 PC 上执行 ADB 端口转发后启动 Bridge：

```powershell
# 端口转发
adb forward tcp:17532 tcp:17532

# 启动 Fusion Bridge
python -m bridge.main
```

## Termux:API 注意事项

剪贴板功能需要 **Termux:API App** 配合：

1. 从 **F-Droid** 安装 [Termux:API](https://f-droid.org/packages/com.termux.api/)
2. Termux 和 Termux:API **必须来自同一来源**（都从 F-Droid 或都从 GitHub）
3. 首次使用 `termux-clipboard-get` 会弹出权限请求，允许即可

## 开机自启 (可选)

安装 [Termux:Boot](https://f-droid.org/packages/com.termux.boot/)：

```bash
# 创建启动脚本
mkdir -p ~/.termux/boot/
cat > ~/.termux/boot/fusion-bridge.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd ~/fusion-bridge
bash start.sh &
EOF
chmod +x ~/.termux/boot/fusion-bridge.sh
```

## 故障排除

| 问题 | 解决方案 |
|------|---------|
| `termux-clipboard-get: not found` | 安装 Termux:API App + `pkg install termux-api` |
| WebSocket 连接失败 | 检查 `adb forward tcp:17532 tcp:17532` 是否执行 |
| 剪贴板不变化 | Termux:API App 权限是否允许？检查 `termux-clipboard-get` 是否能手动执行 |
| 中文乱码 | 确保 Termux 字体支持中文，`pkg install fontconfig` |
