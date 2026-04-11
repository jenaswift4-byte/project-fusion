#!/data/data/com.termux/files/usr/bin/bash
# Fusion Termux Bridge — 启动脚本

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_SCRIPT="$SCRIPT_DIR/fusion_termux_bridge.py"

# 检查 Python
if ! command -v python > /dev/null 2>&1; then
    echo "❌ Python 未安装，请先运行: bash install.sh"
    exit 1
fi

# 检查 websockets
if ! python -c "import websockets" 2>/dev/null; then
    echo "❌ websockets 未安装，请先运行: bash install.sh"
    exit 1
fi

# 检查是否已有实例运行
PID_FILE="$SCRIPT_DIR/.fusion.pid"
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "⚠ Fusion Termux Bridge 已在运行 (PID: $OLD_PID)"
        echo "  如需重启: bash stop.sh && bash start.sh"
        exit 1
    fi
    rm -f "$PID_FILE"
fi

# 前台运行
echo "启动 Fusion Termux Bridge..."
python "$BRIDGE_SCRIPT" &
BRIDGE_PID=$!
echo "$BRIDGE_PID" > "$PID_FILE"

# 等待一下看是否启动成功
sleep 2
if kill -0 "$BRIDGE_PID" 2>/dev/null; then
    echo "✅ 已启动 (PID: $BRIDGE_PID)"
    echo "   端口: 17532"
    echo "   日志: tail -f $SCRIPT_DIR/fusion.log"
    echo ""
    echo "   PC 端执行: adb forward tcp:17532 tcp:17532"
    echo "   停止: bash stop.sh"
    # 保持前台 (wait)
    wait "$BRIDGE_PID"
else
    echo "❌ 启动失败，请检查日志"
    rm -f "$PID_FILE"
    exit 1
fi
