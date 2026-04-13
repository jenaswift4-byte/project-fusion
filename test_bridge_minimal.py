"""
最小化启动测试 - 验证 MQTT Broker + Dashboard + IP 注入
不启动 Scrcpy 和伴侣 App，只验证核心通信链路

用法: python test_bridge_minimal.py
"""

import sys
import os
import time
import logging
import socket

# 添加项目根目录
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bridge.config import load_config
from bridge.modules.mqtt_bridge import MQTTBridge
from bridge.modules.dashboard_server import DashboardServer
from bridge.modules.command_bridge import CommandBridge
from bridge.modules.sound_monitor import SoundMonitor
from bridge.modules.multi_scrcpy import MultiScrcpyManager
from bridge.modules.pc_online_broadcaster import PCOnlineBroadcaster
from bridge.modules.distributed_scheduler import DistributedScheduler

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("test")


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


class MockDaemon:
    """模拟 BridgeDaemon 的最小接口"""
    def __init__(self, config):
        self.config = config
        self.device_serial = None
        self.adb_path = config.get("adb", {}).get("path", "adb")
        
    def adb_shell(self, command, capture=True):
        import subprocess
        device_arg = ["-s", self.device_serial] if self.device_serial else []
        cmd = [self.adb_path] + device_arg + ["shell", command]
        try:
            if capture:
                result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
                return result.stdout.strip(), result.stderr.strip(), result.returncode
            else:
                subprocess.Popen(cmd)
                return None, None, 0
        except subprocess.TimeoutExpired:
            return None, "Timeout", 1
        except Exception as e:
            return None, str(e), 1


def main():
    print("=" * 60)
    print("    Project Fusion - 最小化启动测试")
    print("=" * 60)
    print()
    
    config = load_config()
    pc_ip = get_local_ip()
    
    # 1. MQTT Broker
    print("[1/5] 启动 MQTT Broker...")
    daemon = MockDaemon(config)
    daemon.mqtt_bridge = MQTTBridge(daemon)
    if daemon.mqtt_bridge.start():
        mqtt_port = config.get("mqtt", {}).get("port", 1883)
        print(f"  ✅ MQTT Broker 已启动 - {pc_ip}:{mqtt_port}")
    else:
        print("  ❌ MQTT Broker 启动失败")
        return
    
    # 2. Dashboard
    print("[2/5] 启动 Dashboard...")
    dashboard_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dashboard")
    dashboard = DashboardServer(port=8080, dashboard_dir=dashboard_dir)
    daemon.dashboard_server = dashboard
    dashboard.set_bridges(daemon)
    if dashboard.start():
        print("  ✅ Dashboard 已启动 - http://localhost:8080")
    else:
        print("  ❌ Dashboard 启动失败")
    
    # 3. Command Bridge
    print("[3/5] 启动命令通道...")
    daemon.command_bridge = CommandBridge(daemon)
    daemon.command_bridge.set_mqtt_bridge(daemon.mqtt_bridge)
    daemon.command_bridge.start()
    print("  ✅ 命令通道已启动")
    
    # 4. PC Online Broadcaster
    print("[4/5] 启动 PC 在线广播...")
    daemon.pc_online_broadcaster = PCOnlineBroadcaster(
        mqtt_bridge=daemon.mqtt_bridge,
        config=config.get("pc_online", {}),
    )
    daemon.pc_online_broadcaster.start()
    print(f"  ✅ PC 在线广播 (心跳: {config.get('pc_online', {}).get('heartbeat_interval', 15)}s)")
    
    # 5. 分布式调度器
    print("[5/5] 启动分布式调度器...")
    daemon.distributed_scheduler = DistributedScheduler(daemon)
    daemon.distributed_scheduler.start_monitoring()
    print("  ✅ 分布式调度器已启动")
    
    # 连接 MQTT → Dashboard 数据桥
    daemon.mqtt_bridge.on_sensor_data(
        lambda dev, stype, val, unit: dashboard.update_sensor(dev, stype, val, unit)
    )
    daemon.mqtt_bridge.on_device_online(
        lambda dev: dashboard.update_device_status(dev, True)
    )
    daemon.mqtt_bridge.on_device_offline(
        lambda dev: dashboard.update_device_status(dev, False)
    )
    
    print()
    print("=" * 60)
    print(f"  PC IP: {pc_ip}")
    print(f"  MQTT Broker: ws://{pc_ip}:{config.get('mqtt', {}).get('port', 1883)}")
    print(f"  Dashboard: http://localhost:8080")
    print(f"  WebSocket: ws://localhost:8081")
    print()
    print("  手机 App 需要连接到 MQTT Broker 才能看到传感器数据")
    print("  在手机 App 中设置 Broker 地址: " + pc_ip)
    print("=" * 60)
    
    # 注入 PC IP 到手机 (如果有设备连接)
    try:
        import subprocess
        result = subprocess.run(
            [config.get("adb", {}).get("path", "adb"), "devices"],
            capture_output=True, text=True, timeout=5
        )
        lines = result.stdout.strip().split("\n")[1:]
        usb_devices = [l.split()[0] for l in lines if "device" in l and not l.startswith("List")]
        if usb_devices:
            daemon.device_serial = usb_devices[0]
            print(f"\n[ADB] 发现设备: {daemon.device_serial}，尝试注入 PC IP...")
            
            # 尝试广播注入
            out, err, rc = daemon.adb_shell(
                f"am broadcast -a com.fusion.companion.action.SET_BROKER "
                f"--es host {pc_ip} --ei port {config.get('mqtt', {}).get('port', 1883)} "
                f"--ez reconnect true -p com.fusion.companion"
            )
            print(f"  广播结果: rc={rc}")
            
            # MQTT 广播
            daemon.mqtt_bridge.publish_json("fusion/pc/broker", {
                "host": pc_ip,
                "port": config.get("mqtt", {}).get("port", 1883),
                "action": "connect",
                "timestamp": int(time.time() * 1000),
            })
            print(f"  ✅ MQTT Broker 发现消息已广播")
        else:
            print("\n[ADB] 无设备连接，跳过 IP 注入")
    except Exception as e:
        print(f"\n[ADB] 检查失败: {e}")
    
    print("\n按 Ctrl+C 停止...\n")
    
    # 保持运行
    try:
        while True:
            time.sleep(5)
            # 定期状态
            clients = daemon.mqtt_bridge.get_connected_clients()
            if clients:
                print(f"[状态] MQTT 客户端: {clients}")
    except KeyboardInterrupt:
        print("\n正在停止...")
    
    daemon.mqtt_bridge.stop()
    daemon.command_bridge.stop()
    daemon.pc_online_broadcaster.stop()
    daemon.distributed_scheduler.stop_monitoring()
    if dashboard:
        dashboard.stop()
    
    print("已停止")


if __name__ == "__main__":
    main()
