#!/data/data/com.termux/files/usr/bin/bash
# Fusion Termux Bridge — 停止脚本

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$SCRIPT_DIR/.fusion.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "Fusion Termux Bridge 未在运行"
    # 尝试通过进程名查找
    PIDS=$(pgrep -f "fusion_termux_bridge" 2>/dev/null)
    if [ -n "$PIDS" ]; then
        echo "找到运行中的进程: $PIDS"
        echo "正在停止..."
        kill $PIDS 2>/dev/null
        sleep 1
        echo "✅ 已停止"
    else
        echo "没有找到运行中的进程"
    fi
    exit 0
fi

PID=$(cat "$PID_FILE")
if kill -0 "$PID" 2>/dev/null; then
    echo "正在停止 Fusion Termux Bridge (PID: $PID)..."
    kill "$PID" 2>/dev/null
    sleep 1
    # 如果还没停，强制杀
    if kill -0 "$PID" 2>/dev/null; then
        kill -9 "$PID" 2>/dev/null
    fi
    echo "✅ 已停止"
else
    echo "进程 $PID 已不存在"
fi

rm -f "$PID_FILE"
