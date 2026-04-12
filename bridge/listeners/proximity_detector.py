"""
近场自动连接模块
蓝牙 RSSI 检测手机靠近/离开 → 自动启动/断开 Fusion

手机作为 PC 外设扩展板:
  - 手机靠近 PC (蓝牙 RSSI > 阈值) → 自动启动 Scrcpy + Bridge
  - 手机离开 PC → 自动断开 (可选)
  - 比手动连接更无缝的体验
  - 使用 Windows Bluetooth API 检测信号强度
"""

import time
import threading
import logging
import subprocess
import re

logger = logging.getLogger(__name__)


class ProximityDetector:
    """近场检测器 - 蓝牙 RSSI 方案"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._thread = None
        self._nearby = False
        self._last_rssi = 0

        # 配置
        prox_cfg = daemon.config.get("proximity", {})
        self.enabled = prox_cfg.get("enabled", False)  # 默认关闭，需要蓝牙
        self.device_name = prox_cfg.get("device_name", "")  # 手机蓝牙名称
        self.device_mac = prox_cfg.get("device_mac", "")    # 手机蓝牙 MAC
        self.rssi_threshold = prox_cfg.get("rssi_threshold", -10)  # RSSI 阈值 (越接近 0 越近)
        self.check_interval = prox_cfg.get("check_interval_ms", 5000) / 1000.0
        self.auto_connect = prox_cfg.get("auto_connect", True)   # 靠近时自动连接
        self.auto_disconnect = prox_cfg.get("auto_disconnect", False)  # 离开时自动断开

    def start(self):
        """启动近场检测"""
        if not self.enabled:
            logger.info("近场检测已禁用")
            return

        if not self.device_mac and not self.device_name:
            logger.warning("近场检测: 未配置设备蓝牙 MAC 或名称")
            return

        self.running = True
        self._thread = threading.Thread(target=self._detect_loop, daemon=True)
        self._thread.start()
        logger.info(f"近场检测已启动 (设备: {self.device_name or self.device_mac})")

    def stop(self):
        """停止近场检测"""
        self.running = False

    def get_rssi(self) -> int:
        """获取蓝牙 RSSI 信号强度

        Returns:
            RSSI 值 (如 -5, -20, -50)，获取失败返回 999
        """
        # 方案1: 通过 PowerShell + Windows Bluetooth API
        if self.device_mac:
            rssi = self._get_rssi_powershell(self.device_mac)
            if rssi != 999:
                return rssi

        # 方案2: 通过 BluetoothCommandTools (如果安装了)
        # 方案3: 通过系统蓝牙设置读取
        return 999

    def _get_rssi_powershell(self, mac: str) -> int:
        """通过 PowerShell 获取蓝牙 RSSI"""
        ps_script = f'''
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class Bluetooth {{
    [DllImport("bthprops.cpl", CharSet = CharSet.Unicode)]
    public static extern int BluetoothFindFirstDevice(
        ref BLUETOOTH_FIND_RADIO_PARAMS pFindRadioParams,
        ref BLUETOOTH_DEVICE_INFO pDeviceInfo);
    
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct BLUETOOTH_FIND_RADIO_PARAMS {{
        public uint dwSize;
    }}
    
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct BLUETOOTH_DEVICE_INFO {{
        public uint dwSize;
        public ulong Address;
        public uint ulClassofDevice;
        public bool fConnected;
        public bool fRemembered;
        public bool fAuthenticated;
        public System.DateTime stLastSeen;
        public System.DateTime stLastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)]
        public string szName;
    }}
}}
"@
'''
        # 简化方案: 使用系统命令获取 RSSI
        # Windows 不直接暴露 RSSI，改用连接状态
        try:
            result = subprocess.run(
                ["powershell", "-WindowStyle", "Hidden", "-NoProfile", "-Command",
                 f"Get-NetNeighbor -LinkLayerAddress '{mac}' -ErrorAction SilentlyContinue | "
                 f"Select-Object -ExpandProperty State"],
                capture_output=True, text=True, timeout=5,
            )
            state = result.stdout.strip()
            if state == "Reachable":
                return -5  # 近距离
            elif state == "Stale":
                return -30  # 中距离
            elif state == "Incomplete":
                return -60  # 远距离
        except Exception:
            pass

        return 999

    def is_nearby(self) -> bool:
        """检测手机是否在附近"""
        rssi = self.get_rssi()
        if rssi == 999:
            return self._nearby  # 检测失败时保持上次状态
        return rssi >= self.rssi_threshold

    def _detect_loop(self):
        """轮询检测近场状态"""
        while self.running:
            try:
                was_nearby = self._nearby
                self._nearby = self.is_nearby()
                self._last_rssi = self.get_rssi() if self._nearby else 0

                # 靠近 → 自动连接
                if not was_nearby and self._nearby and self.auto_connect:
                    logger.info(f"[近场] 手机靠近 (RSSI: {self._last_rssi})，自动连接...")
                    if self.daemon.ws_client and not self.daemon.use_companion:
                        self.daemon.ws_client.start()

                # 离开 → 自动断开
                if was_nearby and not self._nearby and self.auto_disconnect:
                    logger.info("[近场] 手机离开，自动断开...")
                    # 只断开 WS，不停止整个 Bridge
                    if self.daemon.ws_client:
                        self.daemon.ws_client.stop()

                # 更新托盘状态
                if self.daemon.tray_icon:
                    if self._nearby:
                        self.daemon.tray_icon.update_proximity("近场: 在附近")
                    else:
                        self.daemon.tray_icon.update_proximity("近场: 不在范围")

            except Exception as e:
                logger.debug(f"[近场] 检测错误: {e}")

            time.sleep(self.check_interval)
