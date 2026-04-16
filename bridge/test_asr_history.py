"""
测试 ASR 转录历史功能

用法:
  python test_asr_history.py sync     # 同步手机日志
  python test_asr_history.py query    # 查询 ASR 历史
  python test_asr_history.py api      # 测试 Dashboard API
"""

import sys
import time
import json
import requests
from pathlib import Path

# 添加 bridge/modules 到路径
sys.path.insert(0, str(Path(__file__).parent / "modules"))

from modules.log_sync_service import LogSyncService


def test_sync():
    """测试日志同步"""
    print("\n=== 测试日志同步 ===")
    
    service = LogSyncService()
    
    # 手动查询本地数据库
    logs = service.query_logs(log_type="asr_result", limit=5)
    print(f"本地 ASR 历史记录: {len(logs)} 条")
    
    for log in logs:
        ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(log["timestamp"] / 1000))
        content = log.get("content", "")[:50]
        print(f"  [{ts}] {content}")


def test_query():
    """查询 ASR 历史"""
    print("\n=== 查询 ASR 历史 ===")
    
    service = LogSyncService()
    logs = service.query_logs(log_type="asr_result", limit=20)
    
    if not logs:
        print("暂无 ASR 记录")
        return
    
    print(f"\n最近的 {len(logs)} 条转录记录:")
    for log in logs:
        ts = time.strftime("%H:%M:%S", time.localtime(log["timestamp"] / 1000))
        speaker = log.get("speaker") or "未知"
        content = log.get("content", "")
        print(f"  [{ts}] [{speaker}] {content}")


def test_api():
    """测试 Dashboard API"""
    print("\n=== 测试 Dashboard API ===")
    
    try:
        response = requests.get("http://localhost:8080/api/asr_history?limit=10", timeout=5)
        
        if response.status_code == 200:
            data = response.json()
            logs = data.get("logs", [])
            print(f"API 返回 {len(logs)} 条记录")
            
            for log in logs[:5]:
                ts = time.strftime("%H:%M:%S", time.localtime(log["timestamp"] / 1000))
                content = log.get("content", "")[:50]
                print(f"  [{ts}] {content}")
        else:
            print(f"API 错误: {response.status_code}")
            print("Dashboard 可能需要重启才能加载新 API")
            
    except Exception as e:
        print(f"API 测试失败: {e}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    
    cmd = sys.argv[1]
    
    if cmd == "sync":
        test_sync()
    elif cmd == "query":
        test_query()
    elif cmd == "api":
        test_api()
    else:
        print(f"未知命令: {cmd}")
        print(__doc__)


if __name__ == "__main__":
    main()
