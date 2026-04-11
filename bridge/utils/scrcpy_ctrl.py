"""
Scrcpy 进程与窗口管理工具
"""

import ctypes
import ctypes.wintypes
import subprocess
import time
import logging
import os

logger = logging.getLogger(__name__)

user32 = ctypes.windll.user32


class ScrcpyController:
    """Scrcpy 进程管理 + 窗口控制"""

    def __init__(self, config: dict):
        self.config = config["scrcpy"]
        self.scrcpy_dir = config["scrcpy"]["path"]
        self.scrcpy_exe = os.path.join(self.scrcpy_dir, "scrcpy.exe")
        self.window_title = self.config.get("window_title", "Phone")
        self.process = None

    def start(self) -> bool:
        """启动 Scrcpy 进程"""
        cmd = [
            self.scrcpy_exe,
            f"--window-title={self.window_title}",
            f"--window-x={self.config.get('x', 1440)}",
            f"--window-y={self.config.get('y', 0)}",
            f"--window-width={self.config.get('width', 480)}",
            f"--window-height={self.config.get('height', 1080)}",
        ]

        if self.config.get("no_border", True):
            cmd.append("--window-borderless")
        if self.config.get("always_on_top", True):
            cmd.append("--always-on-top")
        if self.config.get("max_fps"):
            cmd.append(f"--max-fps={self.config['max_fps']}")
        if self.config.get("bit_rate"):
            cmd.append(f"--video-bit-rate={self.config['bit_rate']}")
        if self.config.get("turn_screen_off", True):
            cmd.append("--turn-screen-off")
        if self.config.get("stay_awake", True):
            cmd.append("--stay-awake")

        try:
            logger.info(f"启动 Scrcpy: {' '.join(cmd)}")
            self.process = subprocess.Popen(
                cmd,
                cwd=self.scrcpy_dir,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            # 等待窗口出现
            time.sleep(2)
            return True
        except FileNotFoundError:
            logger.error(f"Scrcpy 不存在: {self.scrcpy_exe}")
            return False
        except Exception as e:
            logger.error(f"启动 Scrcpy 失败: {e}")
            return False

    def stop(self):
        """停止 Scrcpy 进程"""
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
            logger.info("Scrcpy 已停止")

    def find_window(self) -> int:
        """查找 Scrcpy 窗口句柄"""
        hwnd = user32.FindWindowW(None, self.window_title)
        return hwnd

    def bring_to_front(self) -> bool:
        """将 Scrcpy 窗口置顶"""
        hwnd = self.find_window()
        if not hwnd:
            logger.debug(f"未找到窗口: {self.window_title}")
            return False

        # 先恢复窗口 (如果最小化)
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, 9)  # SW_RESTORE

        user32.SetForegroundWindow(hwnd)
        user32.BringWindowToTop(hwnd)
        return True

    def minimize(self) -> bool:
        """最小化 Scrcpy 窗口"""
        hwnd = self.find_window()
        if not hwnd:
            return False
        user32.ShowWindow(hwnd, 6)  # SW_MINIMIZE
        return True

    def is_focused(self) -> bool:
        """检查 Scrcpy 窗口是否获得焦点"""
        foreground = user32.GetForegroundWindow()
        hwnd = self.find_window()
        return foreground == hwnd if hwnd else False

    def is_running(self) -> bool:
        """检查 Scrcpy 进程是否在运行"""
        if self.process is None:
            return False
        return self.process.poll() is None
