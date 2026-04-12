"""
低延迟传输优化器
实现USB优先连接和自适应码率调整，确保最低延迟体验

核心功能：
  - 连接类型检测 (USB/WiFi6/WiFi5/Ethernet)
  - 延迟测量与质量评估
  - 动态编码参数调整
  - 自适应传输优化
"""

import time
import logging
import subprocess
from enum import Enum
from typing import Dict, Optional, Tuple
from dataclasses import dataclass

logger = logging.getLogger(__name__)


class ConnectionType(Enum):
    """连接类型"""
    USB = "usb"
    WIFI_6 = "wifi_6"
    WIFI_5G = "wifi_5g"
    WIFI_2G = "wifi_2g"
    ETHERNET = "ethernet"
    UNKNOWN = "unknown"


@dataclass
class EncodingParams:
    """编码参数"""
    bitrate: str
    fps: int
    gop: int
    bframes: int
    hw_accel: str
    low_latency: bool = True


class StreamOptimizer:
    """低延迟传输优化器"""

    # 延迟阈值（毫秒）
    LATENCY_THRESHOLDS = {
        ConnectionType.USB: 20,
        ConnectionType.WIFI_6: 40,
        ConnectionType.WIFI_5G: 60,
        ConnectionType.ETHERNET: 30,
    }

    # 推荐编码参数
    ENCODING_PRESETS = {
        ConnectionType.USB: EncodingParams(
            bitrate="50M",
            fps=120,
            gop=12,
            bframes=0,
            hw_accel="nvenc",
            low_latency=True,
        ),
        ConnectionType.WIFI_6: EncodingParams(
            bitrate="30M",
            fps=60,
            gop=30,
            bframes=0,
            hw_accel="nvenc",
            low_latency=True,
        ),
        ConnectionType.WIFI_5G: EncodingParams(
            bitrate="20M",
            fps=60,
            gop=60,
            bframes=1,
            hw_accel="nvenc",
            low_latency=True,
        ),
        ConnectionType.ETHERNET: EncodingParams(
            bitrate="40M",
            fps=60,
            gop=30,
            bframes=0,
            hw_accel="nvenc",
            low_latency=True,
        ),
    }

    # PC IP 地址（需要动态获取）
    DEFAULT_PC_IP = "192.168.1.100"

    def __init__(self, daemon):
        self.daemon = daemon
        self.connection_type = ConnectionType.UNKNOWN
        self.current_latency = 0
        self.last_network_check = 0
        self.adaptive_enabled = True
        self.monitoring = False
        self.monitor_thread = None

        # 性能历史
        self.latency_history = []
        self.max_history = 60

        # USB 网络共享检测缓存
        self._usb_rndis_checked = False
        self._is_usb_rndis = False

    def start_monitoring(self):
        """启动自适应监控循环"""
        if self.monitoring:
            return

        self.monitoring = True
        self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.monitor_thread.start()
        logger.info("传输优化监控已启动")

    def stop_monitoring(self):
        """停止监控"""
        self.monitoring = False
        if self.monitor_thread:
            self.monitor_thread.join(timeout=2)

    def _monitor_loop(self):
        """自适应调整监控循环"""
        while self.monitoring:
            try:
                # 检测当前连接类型
                new_connection = self.detect_connection()

                # 如果连接类型变化，重新配置
                if new_connection != self.connection_type:
                    logger.info(f"连接类型变化: {self.connection_type.value} -> {new_connection.value}")
                    self.connection_type = new_connection

                    # 动态调整编码参数
                    params = self.get_encoding_params()
                    self._apply_encoding_params(params)

                # 延迟监控
                latency = self.current_latency
                threshold = self.LATENCY_THRESHOLDS.get(self.connection_type, 60)

                if latency > threshold:
                    logger.warning(f"延迟过高: {latency}ms (阈值: {threshold}ms)")
                    self._handle_high_latency()

                # 记录历史
                self.latency_history.append({
                    "timestamp": time.time(),
                    "latency": latency,
                    "connection": self.connection_type.value,
                })

                if len(self.latency_history) > self.max_history:
                    self.latency_history.pop(0)

            except Exception as e:
                logger.error(f"自适应调整错误: {e}")

            time.sleep(5)

    def detect_connection(self) -> ConnectionType:
        """检测当前连接类型"""
        try:
            # 1. 先检查 USB tethering
            if not self._usb_rndis_checked:
                self._is_usb_rndis = self._check_usb_rndis()
                self._usb_rndis_checked = True

            if self._is_usb_rndis:
                return ConnectionType.USB

            # 2. 测量网络延迟
            latency = self._measure_network_latency()
            self.current_latency = latency

            # 3. 根据延迟判断连接类型
            if latency < 25:
                # 可能是 USB 虚拟网络或极好的 WiFi 6
                if self._is_usb_rndis:
                    return ConnectionType.USB
                return ConnectionType.WIFI_6
            elif latency < 50:
                return ConnectionType.WIFI_6
            elif latency < 80:
                return ConnectionType.WIFI_5G
            elif latency < 150:
                return ConnectionType.WIFI_2G
            else:
                return ConnectionType.ETHERNET

        except Exception as e:
            logger.debug(f"连接检测失败: {e}")
            return ConnectionType.UNKNOWN

    def _check_usb_rndis(self) -> bool:
        """检查是否启用 USB 网络共享 (RNDIS)"""
        try:
            # 检查 Android USB 配置
            output, _, _ = self.daemon.adb_shell("getprop sys.usb.config")
            if output:
                return "rndis" in output.lower() or "ethernet" in output.lower()

            # 检查网络接口
            output, _, _ = self.daemon.adb_shell("ip route | grep usb")
            return bool(output and "usb" in output.lower())

        except:
            return False

    def _measure_network_latency(self) -> float:
        """测量到 PC 的网络延迟"""
        try:
            # 获取 PC IP（通过 ADB devices 或已知 IP）
            pc_ip = self._get_pc_ip()
            if not pc_ip:
                pc_ip = self.DEFAULT_PC_IP

            # Ping 测试
            result = subprocess.run(
                ["ping", "-n", "3", "-w", "100", pc_ip],
                capture_output=True,
                text=True,
                timeout=2
            )

            if result.returncode == 0:
                # 解析平均延迟
                import re
                match = re.search(r"Average = (\d+)ms", result.stdout)
                if match:
                    return float(match.group(1))

                # 尝试解析其他格式
                match = re.search(r"time[=<](\d+)ms", result.stdout)
                if match:
                    return float(match.group(1))

        except Exception as e:
            logger.debug(f"延迟测量失败: {e}")

        return 999.0

    def _get_pc_ip(self) -> Optional[str]:
        """获取 PC 的 IP 地址"""
        try:
            # 通过 adb shell 获取手机 IP，然后推断 PC IP
            output, _, _ = self.daemon.adb_shell(
                "ip addr show wlan0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1"
            )

            if output and output.strip():
                phone_ip = output.strip()
                # 假设 PC 和手机在同一网段
                # 例如手机是 192.168.1.100，PC 可能是 192.168.1.99
                parts = phone_ip.split('.')
                if len(parts) == 4:
                    # 尝试常见的 PC IP
                    for i in [1, 2, 254]:
                        if int(parts[3]) != i:
                            test_ip = f"{parts[0]}.{parts[1]}.{parts[2]}.{i}"
                            if self._ping_host(test_ip):
                                return test_ip

        except:
            pass

        return None

    def _ping_host(self, ip: str) -> bool:
        """Ping 主机检测是否存活"""
        try:
            result = subprocess.run(
                ["ping", "-n", "1", "-w", "100", ip],
                capture_output=True,
                timeout=1
            )
            return result.returncode == 0
        except:
            return False

    def get_encoding_params(self, connection_type: ConnectionType = None) -> EncodingParams:
        """获取推荐编码参数"""
        if connection_type is None:
            connection_type = self.connection_type

        if self.adaptive_enabled:
            # 动态检测连接类型
            detected = self.detect_connection()
            connection_type = detected

        return self.ENCODING_PRESETS.get(
            connection_type,
            self.ENCODING_PRESETS[ConnectionType.WIFI_5G]
        )

    def _apply_encoding_params(self, params: EncodingParams):
        """应用编码参数到 Sunshine"""
        try:
            # 通过 Sunshine API 动态调整
            self._configure_sunshine(params)
            logger.info(f"编码参数已更新: {params.bitrate}, {params.fps}fps, {params.hw_accel}")

        except Exception as e:
            logger.error(f"应用编码参数失败: {e}")

    def _configure_sunshine(self, params: EncodingParams):
        """配置 Sunshine 编码参数"""
        try:
            import requests

            # Sunshine API 配置端点
            api_url = "http://localhost:47990/api/config"

            config_data = {
                "video": params.bitrate,
                "fps": params.fps,
                "gop": params.gop,
                "bframes": params.bframes,
                "hwaccel": params.hw_accel,
                "low_latency": params.low_latency,
            }

            resp = requests.post(api_url, json=config_data, timeout=5)

            if resp.status_code == 200:
                logger.info(f"Sunshine 配置成功: {config_data}")
            else:
                logger.warning(f"Sunshine 配置失败: {resp.status_code}")

        except Exception as e:
            logger.debug(f"Sunshine API 调用失败: {e}")

    def _handle_high_latency(self):
        """处理高延迟情况"""
        logger.info("尝试改善连接质量...")

        # 1. 尝试重启 ADB 隧道
        try:
            if hasattr(self.daemon, 'ws_client') and self.daemon.ws_client:
                self.daemon.ws_client.reconnect()
        except:
            pass

        # 2. 降低码率作为应急方案
        current_params = self.get_encoding_params()
        if current_params.fps > 30:
            emergency_params = EncodingParams(
                bitrate="10M",
                fps=30,
                gop=30,
                bframes=0,
                hw_accel=current_params.hw_accel,
                low_latency=True,
            )
            self._apply_encoding_params(emergency_params)
            logger.warning("已切换到应急低码率模式")

    def set_usb_priority(self, enabled: bool):
        """设置 USB 连接优先级"""
        if enabled:
            logger.info("USB 优先级已启用")
        else:
            logger.info("WiFi 优先级已启用")

    def enable_adaptive(self):
        """启用自适应调整"""
        self.adaptive_enabled = True
        logger.info("自适应传输优化已启用")

    def disable_adaptive(self):
        """禁用自适应调整"""
        self.adaptive_enabled = False
        logger.info("自适应传输优化已禁用")

    def get_status(self) -> Dict:
        """获取传输优化状态"""
        return {
            "connection_type": self.connection_type.value,
            "latency_ms": self.current_latency,
            "adaptive_enabled": self.adaptive_enabled,
            "monitoring": self.monitoring,
            "encoding": self.get_encoding_params().__dict__ if self.connection_type != ConnectionType.UNKNOWN else None,
            "latency_history_avg": (
                sum(h["latency"] for h in self.latency_history) / len(self.latency_history)
                if self.latency_history else 0
            ),
        }

    def force_reconnect(self):
        """强制重连以改善质量"""
        logger.info("执行强制重连...")

        try:
            # 重启 ADB
            self.daemon.adb_shell("killall adb 2>/dev/null || true")
            time.sleep(1)
            self.daemon.adb_shell("start adbd")
            time.sleep(2)

            # 清除 USB 检测缓存
            self._usb_rndis_checked = False

            # 重新检测连接
            new_connection = self.detect_connection()
            logger.info(f"重连后连接类型: {new_connection.value}")

        except Exception as e:
            logger.error(f"强制重连失败: {e}")


import threading
