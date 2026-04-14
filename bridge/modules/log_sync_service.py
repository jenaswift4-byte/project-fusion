"""
PC 端日志同步服务

工作流程:
  1. 发布 MQTT 同步命令到手机端
  2. 接收手机端返回的增量日志 JSON
  3. 存储到本地 SQLite 数据库

协议:
  PC → 手机: fusion/log/{deviceId}/sync  {"action": "sync", "since_id": 123}
  手机 → PC: fusion/log/{deviceId}/data   {"logs": [...], "max_id": 456}

@author Fusion
@version 1.0
"""

import json
import os
import sqlite3
import time
import threading
from datetime import datetime

# 本地 SQLite 路径
DB_PATH = os.path.join(os.path.dirname(__file__), "fusion_logs.db")


class LogSyncService:
    """PC 端日志同步服务"""

    def __init__(self, mqtt_bridge=None):
        self.mqtt_bridge = mqtt_bridge
        self.db_path = DB_PATH
        self._init_db()
        self._sync_thread = None
        self._running = False

    def _init_db(self):
        """初始化本地 SQLite 数据库"""
        conn = sqlite3.connect(self.db_path)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                remote_id INTEGER,
                device_id TEXT,
                type TEXT NOT NULL,
                speaker TEXT,
                content TEXT,
                timestamp INTEGER NOT NULL,
                synced_at INTEGER,
                UNIQUE(remote_id, device_id)
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_logs_type ON logs(type)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs(timestamp)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_logs_device ON logs(device_id)")
        conn.commit()
        conn.close()
        print(f"[LogSync] 数据库初始化完成: {self.db_path}")

    def get_max_remote_id(self, device_id: str) -> int:
        """获取指定设备的最大远程 ID"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.execute(
            "SELECT MAX(remote_id) FROM logs WHERE device_id = ?",
            (device_id,)
        )
        result = cursor.fetchone()
        conn.close()
        return result[0] if result and result[0] else 0

    def request_sync(self, device_id: str):
        """
        向手机端请求日志同步

        Args:
            device_id: 目标设备 ID
        """
        if not self.mqtt_bridge:
            print("[LogSync] MQTT Bridge 未设置, 无法同步")
            return

        since_id = self.get_max_remote_id(device_id)
        message = json.dumps({
            "action": "sync",
            "since_id": since_id,
            "limit": 200,
            "timestamp": int(time.time() * 1000),
        })

        topic = f"fusion/log/{device_id}/sync"
        self.mqtt_bridge.publish(topic, message, qos=1)
        print(f"[LogSync] 同步请求已发送: {topic}, since_id={since_id}")

    def handle_sync_response(self, device_id: str, payload: str):
        """
        处理手机端返回的同步数据

        Args:
            device_id: 设备 ID
            payload: JSON 字符串
        """
        try:
            data = json.loads(payload)
            logs = data.get("logs", [])
            max_id = data.get("max_id", 0)

            if not logs:
                print(f"[LogSync] 无新日志: {device_id}")
                return

            conn = sqlite3.connect(self.db_path)
            inserted = 0

            for entry in logs:
                try:
                    conn.execute("""
                        INSERT OR IGNORE INTO logs
                        (remote_id, device_id, type, speaker, content, timestamp, synced_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, (
                        entry.get("id"),
                        device_id,
                        entry.get("type"),
                        entry.get("speaker"),
                        entry.get("content"),
                        entry.get("timestamp"),
                        int(time.time() * 1000),
                    ))
                    inserted += 1
                except sqlite3.IntegrityError:
                    pass  # 已存在, 跳过

            conn.commit()
            conn.close()

            print(f"[LogSync] 同步完成: {device_id}, 收到 {len(logs)} 条, 新增 {inserted} 条")

        except Exception as e:
            print(f"[LogSync] 处理同步数据失败: {e}")

    def query_logs(self, log_type: str = None, limit: int = 50,
                   device_id: str = None, since_ts: int = 0) -> list:
        """
        查询本地日志

        Args:
            log_type: 日志类型 (None=全部)
            limit: 最大条数
            device_id: 设备 ID (None=全部)
            since_ts: 起始时间戳

        Returns:
            日志列表
        """
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row

        conditions = []
        params = []

        if log_type:
            conditions.append("type = ?")
            params.append(log_type)
        if device_id:
            conditions.append("device_id = ?")
            params.append(device_id)
        if since_ts:
            conditions.append("timestamp >= ?")
            params.append(since_ts)

        where = " AND ".join(conditions) if conditions else "1=1"
        sql = f"SELECT * FROM logs WHERE {where} ORDER BY timestamp DESC LIMIT ?"
        params.append(limit)

        rows = conn.execute(sql, params).fetchall()
        conn.close()

        return [dict(r) for r in rows]

    def get_daily_summary_data(self, date_str: str = None) -> list:
        """
        获取指定日期的 transcript 记录 (供 Dashboard 展示)

        Args:
            date_str: 日期字符串 "2026-04-14" (None=今天)

        Returns:
            日志列表
        """
        if not date_str:
            date_str = datetime.now().strftime("%Y-%m-%d")

        # 计算时间范围
        start_dt = datetime.strptime(date_str, "%Y-%m-%d")
        end_dt = start_dt.replace(hour=23, minute=59, second=59)
        start_ms = int(start_dt.timestamp() * 1000)
        end_ms = int(end_dt.timestamp() * 1000)

        return self.query_logs(
            log_type="transcript",
            limit=100,
            since_ts=start_ms,
        )

    # ==================== 定时同步 ====================

    def start_periodic_sync(self, device_ids: list, interval_sec: int = 300):
        """
        启动定时同步 (每 5 分钟)

        Args:
            device_ids: 需要同步的设备 ID 列表
            interval_sec: 同步间隔 (秒)
        """
        self._running = True
        self._sync_thread = threading.Thread(
            target=self._sync_loop,
            args=(device_ids, interval_sec),
            daemon=True,
        )
        self._sync_thread.start()
        print(f"[LogSync] 定时同步已启动: 间隔 {interval_sec}s, 设备: {device_ids}")

    def stop_periodic_sync(self):
        """停止定时同步"""
        self._running = False
        if self._sync_thread:
            self._sync_thread.join(timeout=5)
        print("[LogSync] 定时同步已停止")

    def _sync_loop(self, device_ids: list, interval_sec: int):
        """同步循环"""
        while self._running:
            for device_id in device_ids:
                try:
                    self.request_sync(device_id)
                except Exception as e:
                    print(f"[LogSync] 同步失败: {device_id}, {e}")

            # 等待下次同步
            for _ in range(interval_sec):
                if not self._running:
                    break
                time.sleep(1)


# ==================== CLI 入口 ====================

def main():
    import argparse

    parser = argparse.ArgumentParser(description="PC 端日志同步服务")
    parser.add_argument("--sync", metavar="DEVICE_ID", help="同步指定设备的日志")
    parser.add_argument("--query", choices=["speech_detected", "transcript", "daily_summary"],
                        help="查询指定类型的日志")
    parser.add_argument("--today", action="store_true", help="查询今天的 transcript")
    parser.add_argument("--limit", type=int, default=20, help="查询条数")
    args = parser.parse_args()

    service = LogSyncService()

    if args.sync:
        service.request_sync(args.sync)

    elif args.query:
        logs = service.query_logs(log_type=args.query, limit=args.limit)
        for log in logs:
            ts = datetime.fromtimestamp(log["timestamp"] / 1000).strftime("%H:%M:%S")
            speaker = log.get("speaker") or ""
            content = log.get("content", "")[:80]
            print(f"  [{ts}] {log['type']:20s} {speaker:10s} {content}")

    elif args.today:
        logs = service.get_daily_summary_data()
        print(f"\n📋 今天的语音记录 ({len(logs)} 条):")
        for log in logs:
            ts = datetime.fromtimestamp(log["timestamp"] / 1000).strftime("%H:%M:%S")
            speaker = log.get("speaker") or "未知"
            content = log.get("content", "")
            print(f"  [{ts}] [{speaker}] {content}")

    else:
        parser.print_help()


if __name__ == "__main__":
    main()
