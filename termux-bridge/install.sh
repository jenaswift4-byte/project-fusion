#!/data/data/com.termux/files/usr/bin/bash
# Fusion Termux Bridge — 一键安装脚本
# 在 Termux 中运行: bash install.sh

set -e

echo "╔══════════════════════════════════════╗"
echo "║   Fusion Termux Bridge 安装向导      ║"
echo "╚══════════════════════════════════════╝"
echo ""

# 1. 更新包管理器
echo "[1/4] 更新 Termux 包..."
pkg update -y 2>/dev/null || true

# 2. 安装基础依赖
echo "[2/4] 安装依赖..."
pkg install -y python termux-api 2>/dev/null || {
    echo "⚠ pkg install 失败，尝试 apt..."
    apt update -y 2>/dev/null || true
    apt install -y python termux-api 2>/dev/null || true
}

# 3. 安装 Python 库
echo "[3/4] 安装 Python 依赖..."
pip install websockets 2>/dev/null || pip3 install websockets

# 4. 检查 Termux:API App
echo "[4/4] 检查 Termux:API..."
if ! which termux-clipboard-get > /dev/null 2>&1; then
    echo ""
    echo "⚠⚠⚠ 重要 ⚠⚠⚠"
    echo "termux-clipboard-get 命令不可用。"
    echo "你需要安装 Termux:API App (从 F-Droid):"
    echo "  https://f-droid.org/packages/com.termux.api/"
    echo ""
    echo "注意: Termux:API App 必须与 Termux 来自同一来源"
    echo "  (都从 F-Droid 或都从 GitHub Release 安装)"
    echo ""
fi

# 验证
echo ""
echo "════════ 安装结果 ════════"
echo -n "Python:     "; python --version 2>&1 || echo "❌ 未安装"
echo -n "WebSockets: "; python -c "import websockets; print('✅ v'+websockets.__version__)" 2>/dev/null || echo "❌ 未安装"
echo -n "Termux API: "; which termux-clipboard-get > /dev/null 2>&1 && echo "✅ 已安装" || echo "❌ 未安装"
echo ""
echo "安装完成! 运行以下命令启动:"
echo "  python fusion_termux_bridge.py"
echo ""
echo "或者用快捷脚本:"
echo "  bash start.sh"
