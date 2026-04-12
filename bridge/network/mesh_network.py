"""
混合网络管理器
支持 USB 网络共享、WiFi6/WiFi5/Ethernet 检测、局域网设备发现
WireGuard/Tailscale VPN、mDNS 服务注册等功能
"""

import asyncio
import json
import logging
import os
import platform
import socket
import subprocess
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Optional

logger = logging.getLogger(__name__)


class ConnectionType(Enum):
    """连接类型枚举"""
    USB = "usb"
    WIFI6 = "wifi6"
    WIFI5 = "wifi5"
    ETHERNET = "ethernet"
    UNKNOWN = "unknown"


@dataclass
class NetworkDevice:
    """网络设备信息"""
    ip: str
    hostname: Optional[str] = None
    mac: Optional[str] = None
    latency_ms: Optional[float] = None
    is_reachable: bool = False
    connection_type: ConnectionType = ConnectionType.UNKNOWN
    metadata: dict = field(default_factory=dict)


@dataclass
class RouteInfo:
    """路由信息"""
    gateway: str
    interface: str
    metric: int
    is_direct: bool = False


class HybridNetworkManager:
    """
    混合网络管理器

    功能：
    - 连接检测（USB 网络共享、WiFi6/WiFi5/Ethernet）
    - 设备发现（局域网 ping 扫描、隧道设备发现）
    - WireGuard VPN 配置与管理
    - Tailscale VPN 配置与管理
    - mDNS/DNS-SD 服务注册与发现
    - 智能路由选择（局域网优先，公网备份）
    """

    def __init__(self, config: Optional[dict] = None):
        self.config = config or {}
        self._running = False
        self._discovered_devices: dict[str, NetworkDevice] = {}
        self._lock = threading.Lock()
        self._zeroconf_instance = None
        self._registered_services: dict[str, Any] = {}
        self._ping_timeout = self.config.get("network", {}).get("ping_timeout", 1.0)
        self._scan_range = self.config.get("network", {}).get("scan_range", "192.168.1.0/24")
        self._wg_config_path = self.config.get("wireguard", {}).get("config_path", "/etc/wireguard/fusion.conf")

    def start(self) -> bool:
        """启动网络管理器"""
        try:
            self._running = True
            logger.info("混合网络管理器已启动")
            return True
        except Exception as e:
            logger.error(f"启动网络管理器失败: {e}")
            return False

    def stop(self):
        """停止网络管理器"""
        self._running = False
        self._unregister_all_services()
        logger.info("混合网络管理器已停止")

    # ==================== 连接检测 ====================

    def _check_usb_tethering(self) -> bool:
        """
        检查 USB 网络共享是否可用

        Returns:
            bool: USB 网络共享是否启用
        """
        system = platform.system()
        try:
            if system == "Windows":
                result = subprocess.run(
                    ["netsh", "interface", "show", "interface"],
                    capture_output=True, text=True, timeout=10,
                )
                output = result.stdout.lower()
                return "usb" in output or "ethernet" in output
            elif system == "Linux":
                result = subprocess.run(
                    ["ip", "link", "show"],
                    capture_output=True, text=True, timeout=10,
                )
                output = result.stdout.lower()
                return "usb" in output or "rndis" in output
            elif system == "Darwin":
                result = subprocess.run(
                    ["networksetup", "-listallhardwareports"],
                    capture_output=True, text=True, timeout=10,
                )
                output = result.stdout.lower()
                return "usb" in output
            return False
        except Exception as e:
            logger.debug(f"检查 USB 网络共享失败: {e}")
            return False

    def _detect_connection_type(self) -> ConnectionType:
        """
        检测当前连接类型

        Returns:
            ConnectionType: 检测到的连接类型
        """
        system = platform.system()
        try:
            if system == "Windows":
                result = subprocess.run(
                    ["netsh", "wlan", "show", "interfaces"],
                    capture_output=True, text=True, timeout=10,
                )
                if result.returncode == 0:
                    output = result.stdout.lower()
                    if "wi-fi" in output or "wlan" in output:
                        if "802.11ax" in output or "wifi 6" in output:
                            return ConnectionType.WIFI6
                        elif "802.11ac" in output:
                            return ConnectionType.WIFI5
                        elif "802.11" in output:
                            return ConnectionType.WIFI5
                result = subprocess.run(
                    ["getmac", "-v"],
                    capture_output=True, text=True, timeout=10,
                )
                if "ethernet" in result.stdout.lower():
                    return ConnectionType.ETHERNET
            elif system == "Linux":
                result = subprocess.run(
                    ["cat", "/sys/class/net/*/operstate"],
                    capture_output=True, text=True, timeout=10,
                )
                if "up" in result.stdout:
                    result = subprocess.run(
                        ["iw", "phy"],
                        capture_output=True, text=True, timeout=10,
                    )
                    if "802.11ax" in result.stdout or "wifi 6" in result.stdout.lower():
                        return ConnectionType.WIFI6
                    elif "802.11ac" in result.stdout:
                        return ConnectionType.WIFI5
            elif system == "Darwin":
                result = subprocess.run(
                    ["networksetup", "-getairportnetwork", "en0"],
                    capture_output=True, text=True, timeout=10,
                )
                if result.returncode == 0 and result.stdout.strip():
                    result = subprocess.run(
                        ["system_profiler", "SPAirPortDataType"],
                        capture_output=True, text=True, timeout=10,
                    )
                    if "802.11ax" in result.stdout:
                        return ConnectionType.WIFI6
                    elif "802.11ac" in result.stdout:
                        return ConnectionType.WIFI5
        except Exception as e:
            logger.debug(f"检测连接类型失败: {e}")

        if self._check_usb_tethering():
            return ConnectionType.USB
        return ConnectionType.UNKNOWN

    def get_connection_type(self) -> ConnectionType:
        """获取当前连接类型"""
        return self._detect_connection_type()

    # ==================== 设备发现 ====================

    def _discover_lan_devices(self, subnet: Optional[str] = None) -> list[NetworkDevice]:
        """
        使用 ping 扫描局域网设备

        Args:
            subnet: 子网范围，格式如 "192.168.1.0/24"

        Returns:
            list[NetworkDevice]: 发现的设备列表
        """
        if subnet is None:
            subnet = self._scan_range

        devices = []
        base_ip = self._get_local_ip()
        if not base_ip:
            return devices

        parts = base_ip.split(".")
        if len(parts) != 4:
            return devices

        base = ".".join(parts[:3])
        threads = []
        results = []

        def scan_range(start: int, end: int):
            local_results = []
            for i in range(start, end):
                ip = f"{base}.{i}"
                device = self._ping_device(ip)
                if device and device.is_reachable:
                    local_results.append(device)
            results.extend(local_results)

        step = 25
        ranges = [(i, min(i + step, 256)) for i in range(1, 255, step)]
        for start, end in ranges:
            t = threading.Thread(target=scan_range, args=(start, end))
            threads.append(t)
            t.start()

        for t in threads:
            t.join(timeout=30)

        return results

    def _ping_device(self, ip: str) -> Optional[NetworkDevice]:
        """
        单个设备存活检测

        Args:
            ip: 目标 IP 地址

        Returns:
            Optional[NetworkDevice]: 设备信息，如果不可达则返回 None
        """
        try:
            system = platform.system()
            if system == "Windows":
                cmd = ["ping", "-n", "1", "-w", str(int(self._ping_timeout * 1000)), ip]
            else:
                cmd = ["ping", "-c", "1", "-W", str(int(self._ping_timeout)), ip]

            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=self._ping_timeout + 1,
            )

            device = NetworkDevice(ip=ip, is_reachable=(result.returncode == 0))

            if device.is_reachable:
                device.latency_ms = self._measure_latency(ip)
                device.hostname = self._resolve_hostname(ip)

            return device

        except subprocess.TimeoutExpired:
            return NetworkDevice(ip=ip, is_reachable=False)
        except Exception as e:
            logger.debug(f"Ping {ip} 失败: {e}")
            return NetworkDevice(ip=ip, is_reachable=False)

    def _measure_latency(self, ip: str) -> Optional[float]:
        """
        测量到设备的延迟

        Args:
            ip: 目标 IP 地址

        Returns:
            Optional[float]: 延迟时间（毫秒），失败返回 None
        """
        try:
            system = platform.system()
            start = time.time()

            if system == "Windows":
                cmd = ["ping", "-n", "1", "-w", "1000", ip]
            else:
                cmd = ["ping", "-c", "1", "-W", "1", ip]

            result = subprocess.run(cmd, capture_output=True, text=True, timeout=2)

            if result.returncode == 0:
                elapsed = (time.time() - start) * 1000
                output = result.stdout.lower()
                if "time" in output:
                    import re
                    match = re.search(r"time[=<](\d+(?:\.\d+)?)\s*ms", output)
                    if match:
                        return float(match.group(1))
                return elapsed
            return None

        except Exception:
            return None

    def discover_devices(self, include_tunnel: bool = True) -> dict[str, NetworkDevice]:
        """
        发现所有可用设备（局域网 + 隧道）

        Args:
            include_tunnel: 是否包含隧道设备（VPN）

        Returns:
            dict[str, NetworkDevice]: IP -> 设备信息的字典
        """
        devices = {}

        lan_devices = self._discover_lan_devices()
        for device in lan_devices:
            devices[device.ip] = device

        if include_tunnel:
            tunnel_devices = self._discover_tunnel_devices()
            devices.update(tunnel_devices)

        with self._lock:
            self._discovered_devices = devices

        logger.info(f"设备发现完成，共 {len(devices)} 台设备")
        return devices

    def _discover_tunnel_devices(self) -> dict[str, NetworkDevice]:
        """
        发现隧道设备（WireGuard/Tailscale）

        Returns:
            dict[str, NetworkDevice]: 隧道设备字典
        """
        devices = {}

        wg_ips = self._get_wireguard_peers()
        for ip in wg_ips:
            devices[ip] = NetworkDevice(
                ip=ip,
                is_reachable=True,
                latency_ms=self._measure_latency(ip),
            )

        tail_ips = self._get_tailscale_ips()
        for ip in tail_ips:
            if ip not in devices:
                devices[ip] = NetworkDevice(
                    ip=ip,
                    is_reachable=True,
                    latency_ms=self._measure_latency(ip),
                )

        return devices

    def _get_local_ip(self) -> Optional[str]:
        """获取本机局域网 IP"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return None

    def _resolve_hostname(self, ip: str) -> Optional[str]:
        """通过 IP 反向解析主机名"""
        try:
            hostname, _, _ = socket.gethostbyaddr(ip)
            return hostname
        except Exception:
            return None

    # ==================== WireGuard VPN ====================

    def setup_wireguard(self, config_path: Optional[str] = None) -> bool:
        """
        配置 WireGuard VPN

        Args:
            config_path: 配置文件路径

        Returns:
            bool: 配置是否成功
        """
        if config_path:
            self._wg_config_path = config_path

        try:
            if not os.path.exists(self._wg_config_path):
                logger.error(f"WireGuard 配置文件不存在: {self._wg_config_path}")
                return False

            return self._apply_wireguard_config()

        except Exception as e:
            logger.error(f"WireGuard 配置失败: {e}")
            return False

    def _is_wireguard_running(self) -> bool:
        """
        检查 WireGuard 状态

        Returns:
            bool: WireGuard 是否正在运行
        """
        system = platform.system()
        try:
            if system == "Windows":
                result = subprocess.run(
                    ["sc", "query", "WireGuard"],
                    capture_output=True, text=True, timeout=10,
                )
                return "RUNNING" in result.stdout
            elif system == "Linux":
                result = subprocess.run(
                    ["wg", "show"],
                    capture_output=True, text=True, timeout=10,
                )
                return result.returncode == 0 and len(result.stdout.strip()) > 0
            elif system == "Darwin":
                result = subprocess.run(
                    ["wg", "show"],
                    capture_output=True, text=True, timeout=10,
                )
                return result.returncode == 0
            return False
        except Exception:
            return False

    def _apply_wireguard_config(self) -> bool:
        """
        应用 WireGuard 配置

        Returns:
            bool: 是否应用成功
        """
        system = platform.system()
        try:
            if system == "Windows":
                result = subprocess.run(
                    ["wireguard", "/installtunnelservice", self._wg_config_path],
                    capture_output=True, text=True, timeout=30,
                )
                return result.returncode == 0
            elif system == "Linux":
                result = subprocess.run(
                    ["wg-quick", "up", self._wg_config_path],
                    capture_output=True, text=True, timeout=30,
                )
                return result.returncode == 0
            elif system == "Darwin":
                result = subprocess.run(
                    ["wg-quick", "up", self._wg_config_path],
                    capture_output=True, text=True, timeout=30,
                )
                return result.returncode == 0
            return False
        except Exception as e:
            logger.error(f"应用 WireGuard 配置失败: {e}")
            return False

    def _get_wireguard_peers(self) -> list[str]:
        """
        获取 WireGuard 对等节点 IP

        Returns:
            list[str]: 对等节点 IP 列表
        """
        try:
            result = subprocess.run(
                ["wg", "show"],
                capture_output=True, text=True, timeout=10,
            )
            if result.returncode != 0:
                return []

            peers = []
            for line in result.stdout.split("\n"):
                if "endpoint:" in line.lower():
                    parts = line.split("=")
                    if len(parts) == 2:
                        endpoint = parts[1].strip()
                        if ":" in endpoint:
                            ip = endpoint.rsplit(":", 1)[0]
                            if self._is_valid_ip(ip):
                                peers.append(ip)
            return peers
        except Exception:
            return []

    # ==================== Tailscale ====================

    def setup_tailscale(self, auth_key: Optional[str] = None) -> bool:
        """
        配置 Tailscale 连接

        Args:
            auth_key: Tailscale 认证密钥

        Returns:
            bool: 配置是否成功
        """
        try:
            cmd = ["tailscale", "up"]
            if auth_key:
                cmd.extend(["--authkey", auth_key])

            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=60,
            )
            success = result.returncode == 0
            if success:
                logger.info("Tailscale 连接成功")
            else:
                logger.error(f"Tailscale 连接失败: {result.stderr}")
            return success

        except FileNotFoundError:
            logger.error("Tailscale 未安装")
            return False
        except Exception as e:
            logger.error(f"Tailscale 配置失败: {e}")
            return False

    def _is_tailscale_connected(self) -> bool:
        """
        检查 Tailscale 状态

        Returns:
            bool: Tailscale 是否已连接
        """
        try:
            result = subprocess.run(
                ["tailscale", "status"],
                capture_output=True,
                text=True,
                timeout=10,
            )
            return result.returncode == 0 and "logged in" in result.stdout.lower()
        except Exception:
            return False

    def _get_tailscale_ips(self) -> list[str]:
        """
        获取 Tailscale IP 地址列表

        Returns:
            list[str]: Tailscale IP 列表
        """
        try:
            result = subprocess.run(
                ["tailscale", "ip", "-4"],
                capture_output=True,
                text=True,
                timeout=10,
            )
            if result.returncode == 0:
                return [ip.strip() for ip in result.stdout.strip().split("\n") if self._is_valid_ip(ip.strip())]
            return []
        except Exception:
            return []

    # ==================== 路由选择 ====================

    def get_best_route(self, target_device: str) -> Optional[RouteInfo]:
        """
        获取到目标设备的最佳路由

        Args:
            target_device: 目标设备 IP 或主机名

        Returns:
            Optional[RouteInfo]: 路由信息
        """
        try:
            target_ip = self._resolve_target_ip(target_device)
            if not target_ip:
                return None

            if self._is_local_ip(target_ip):
                return RouteInfo(
                    gateway="",
                    interface=self._get_local_interface(),
                    metric=0,
                    is_direct=True,
                )

            default_route = self._get_default_route()
            return default_route

        except Exception as e:
            logger.error(f"获取最佳路由失败: {e}")
            return None

    def _get_default_route(self) -> Optional[RouteInfo]:
        """
        获取默认路由

        Returns:
            Optional[RouteInfo]: 默认路由信息
        """
        system = platform.system()
        try:
            if system == "Windows":
                result = subprocess.run(
                    ["route", "print", "0.0.0.0"],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                for line in result.stdout.split("\n"):
                    if "0.0.0.0" in line:
                        parts = line.split()
                        if len(parts) >= 3:
                            return RouteInfo(
                                gateway=parts[2],
                                interface="",
                                metric=int(parts[1]) if parts[1].isdigit() else 0,
                            )
            elif system == "Linux":
                result = subprocess.run(
                    ["ip", "route", "show", "default"],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                parts = result.stdout.split()
                if "via" in parts and len(parts) >= 3:
                    gateway_idx = parts.index("via") + 1
                    metric = int(parts[gateway_idx + 2]) if "metric" in parts else 0
                    return RouteInfo(
                        gateway=parts[gateway_idx],
                        interface=parts[parts.index("dev") + 1] if "dev" in parts else "",
                        metric=metric,
                    )
            elif system == "Darwin":
                result = subprocess.run(
                    ["netstat", "-nr"],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                for line in result.stdout.split("\n"):
                    if line.startswith("default") or line.startswith("0/0"):
                        parts = line.split()
                        if len(parts) >= 2:
                            return RouteInfo(
                                gateway=parts[1],
                                interface=parts[3] if len(parts) > 3 else "",
                                metric=0,
                            )
        except Exception as e:
            logger.debug(f"获取默认路由失败: {e}")
        return None

    def _resolve_target_ip(self, target: str) -> Optional[str]:
        """解析目标设备为 IP 地址"""
        if self._is_valid_ip(target):
            return target
        try:
            return socket.gethostbyname(target)
        except Exception:
            return None

    def _is_local_ip(self, ip: str) -> bool:
        """判断 IP 是否为本地局域网 IP"""
        try:
            local_ip = self._get_local_ip()
            if not local_ip:
                return False
            parts = ip.split(".")
            local_parts = local_ip.split(".")
            return parts[:3] == local_parts[:3]
        except Exception:
            return False

    def _get_local_interface(self) -> str:
        """获取本地网络接口名称"""
        system = platform.system()
        try:
            if system == "Windows":
                result = subprocess.run(
                    ["ipconfig"],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                for line in result.stdout.split("\n"):
                    if "适配器" in line or "adapter" in line.lower():
                        current_adapter = line.split()[0]
                    elif "IPv4" in line or "IPv4" in line:
                        return current_adapter
            elif system == "Linux":
                result = subprocess.run(
                    ["ip", "route", "get", "8.8.8.8"],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                if "dev" in result.stdout:
                    parts = result.stdout.split()
                    idx = parts.index("dev") + 1
                    return parts[idx]
            elif system == "Darwin":
                result = subprocess.run(
                    ["route", "-n", "get", "8.8.8.8"],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                for line in result.stdout.split("\n"):
                    if "interface:" in line:
                        return line.split(":")[1].strip()
        except Exception:
            pass
        return ""

    # ==================== mDNS 服务注册 ====================

    def register_service(
        self,
        name: str,
        port: int,
        txt_record: Optional[dict] = None,
    ) -> bool:
        """
        注册 mDNS/DNS-SD 服务

        Args:
            name: 服务名称
            port: 服务端口
            txt_record: TXT 记录字典

        Returns:
            bool: 注册是否成功
        """
        try:
            from zeroconf import ServiceInfo, Zeroconf

            service_type = "_fusion._tcp."
            service_name = f"{name}.{service_type}"

            txt_dict = txt_record or {}
            txt_bytes = {k: str(v).encode() for k, v in txt_dict.items()}

            service_info = ServiceInfo(
                service_type,
                service_name,
                port=port,
                properties=txt_bytes,
            )

            if self._zeroconf_instance is None:
                self._zeroconf_instance = Zeroconf()

            self._zeroconf_instance.register_service(service_info)
            self._registered_services[name] = service_info

            logger.info(f"mDNS 服务已注册: {name} (port={port})")
            return True

        except ImportError:
            logger.warning("zeroconf 库未安装，无法注册 mDNS 服务")
            return False
        except Exception as e:
            logger.error(f"注册 mDNS 服务失败: {e}")
            return False

    def unregister_service(self, name: str) -> bool:
        """
        注销 mDNS 服务

        Args:
            name: 服务名称

        Returns:
            bool: 注销是否成功
        """
        try:
            if self._zeroconf_instance is None or name not in self._registered_services:
                return False

            service_info = self._registered_services[name]
            self._zeroconf_instance.unregister_service(service_info)
            del self._registered_services[name]

            logger.info(f"mDNS 服务已注销: {name}")
            return True

        except Exception as e:
            logger.error(f"注销 mDNS 服务失败: {e}")
            return False

    def _unregister_all_services(self):
        """注销所有已注册的服务"""
        try:
            if self._zeroconf_instance:
                for name in list(self._registered_services.keys()):
                    self.unregister_service(name)
                self._zeroconf_instance.close()
                self._zeroconf_instance = None
        except Exception:
            pass

    # ==================== 工具方法 ====================

    @staticmethod
    def _is_valid_ip(ip: str) -> bool:
        """验证 IP 地址格式"""
        try:
            socket.inet_aton(ip)
            return True
        except Exception:
            return False

    def get_discovered_devices(self) -> dict[str, NetworkDevice]:
        """获取已发现的设备字典"""
        with self._lock:
            return dict(self._discovered_devices)
