"""
Input Leap 控制模块
程序化启停 Input Leap 服务，实现键鼠跨屏流转
"""

import os
import subprocess
import threading
import time
import logging
from pathlib import Path

logger = logging.getLogger(__name__)


class InputLeapController:
    """Input Leap 控制器"""

    def __init__(self, config: dict):
        self.config = config.get("input_leap", {})
        self.enabled = self.config.get("enabled", False)
        self.process = None
        self._config_path = self._resolve_config_path()
        self._monitor_thread = None
        self.running = False

        # 键鼠状态
        self._mouse_on_phone = False
        self._keyboard_captured = False

    def _resolve_config_path(self) -> str:
        """解析 Input Leap 配置文件路径"""
        path = self.config.get("config_path", "")
        if path:
            return path

        # 默认路径
        appdata = os.environ.get("APPDATA", "")
        default = os.path.join(appdata, "InputLeap", "InputLeap.conf")
        if os.path.exists(default):
            return default

        # 生成路径
        return default

    def start(self) -> bool:
        """启动 Input Leap"""
        if not self.enabled:
            logger.info("Input Leap 已禁用 (配置中 input_leap.enabled=false)")
            return False

        # 检查 Input Leap 是否已安装
        if not self._check_installed():
            logger.warning("Input Leap 未安装，键鼠流转不可用")
            logger.warning("下载: https://github.com/input-leap/input-leap/releases")
            return False

        # 生成配置
        self._ensure_config()

        # 启动服务端
        try:
            cmd = self._build_server_command()
            self.process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.running = True

            # 启动监控线程
            self._monitor_thread = threading.Thread(
                target=self._monitor_process, daemon=True
            )
            self._monitor_thread.start()

            logger.info("Input Leap 已启动 (Server 模式)")
            return True
        except FileNotFoundError:
            logger.error("input-leapd 未找到，请确认安装路径")
            return False
        except Exception as e:
            logger.error(f"启动 Input Leap 失败: {e}")
            return False

    def stop(self):
        """停止 Input Leap"""
        self.running = False
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
            logger.info("Input Leap 已停止")

    def _check_installed(self) -> bool:
        """检查 Input Leap 是否已安装"""
        # 检查常见安装路径
        possible_paths = [
            r"C:\Program Files\InputLeap\input-leapd.exe",
            r"C:\Program Files (x86)\InputLeap\input-leapd.exe",
        ]
        for path in possible_paths:
            if os.path.exists(path):
                return True

        # 检查 PATH
        try:
            result = subprocess.run(
                ["input-leapd", "--version"],
                capture_output=True, text=True, timeout=5,
            )
            return result.returncode == 0
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return False

    def _ensure_config(self):
        """确保配置文件存在"""
        if os.path.exists(self._config_path):
            return

        # 生成基础配置
        config_dir = os.path.dirname(self._config_path)
        os.makedirs(config_dir, exist_ok=True)

        config_content = """\
section: screens
  # Windows 主屏幕
  windows:
  # Android (通过 Scrcpy 窗口模拟)
  android:
end

section: links
  windows:
    right = android
  android:
    left = windows
end

section: options
  screenSaverSync = false
  relativeMouseMoves = true
  clipboardSharing = true
end
"""
        with open(self._config_path, "w", encoding="utf-8") as f:
            f.write(config_content)
        logger.info(f"已生成 Input Leap 配置: {self._config_path}")

    def _build_server_command(self) -> list:
        """构建服务端启动命令"""
        cmd = ["input-leapd", "-f", "--config", self._config_path]
        # 添加屏幕名称
        cmd.extend(["--name", "windows"])
        return cmd

    def _monitor_process(self):
        """监控 Input Leap 进程"""
        while self.running:
            if self.process and self.process.poll() is not None:
                exit_code = self.process.poll()
                logger.warning(f"Input Leap 进程退出 (code={exit_code})")
                # 自动重启
                if self.running:
                    logger.info("正在重启 Input Leap...")
                    try:
                        cmd = self._build_server_command()
                        self.process = subprocess.Popen(
                            cmd,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE,
                        )
                    except Exception as e:
                        logger.error(f"重启 Input Leap 失败: {e}")
                        break
            time.sleep(5)

    # === 状态接口 ===

    @property
    def mouse_on_phone(self) -> bool:
        """鼠标是否在手机屏幕区域"""
        return self._mouse_on_phone

    @mouse_on_phone.setter
    def mouse_on_phone(self, value: bool):
        self._mouse_on_phone = value
        if value:
            logger.debug("鼠标进入手机区域")
        else:
            logger.debug("鼠标离开手机区域")

    @property
    def keyboard_captured(self) -> bool:
        """键盘是否被手机捕获"""
        return self._keyboard_captured

    @keyboard_captured.setter
    def keyboard_captured(self, value: bool):
        self._keyboard_captured = value
