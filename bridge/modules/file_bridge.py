"""
文件桥接模块
电脑与手机之间的无缝文件传输
"""

import os
import subprocess
import logging
from pathlib import Path

logger = logging.getLogger(__name__)


class FileBridge:
    """文件桥接器"""

    def __init__(self, bridge):
        self.bridge = bridge
        config = bridge.config.get("file", {})
        self.push_target = config.get("push_target", "/sdcard/Download/")
        self.pull_target = config.get("pull_target", str(Path.home() / "Downloads"))

    def start(self):
        """启动文件桥接"""
        logger.info("文件桥接已启动 - 支持 push/pull/install")

    def stop(self):
        """停止文件桥接"""
        pass

    def push_file(self, local_path: str, remote_path: str = None) -> bool:
        """推送文件到手机"""
        if not os.path.exists(local_path):
            logger.error(f"文件不存在: {local_path}")
            return False

        if remote_path is None:
            filename = os.path.basename(local_path)
            remote_path = f"{self.push_target}{filename}"

        logger.info(f"推送: {local_path} -> {remote_path}")

        device_arg = ["-s", self.bridge.device_serial] if self.bridge.device_serial else []
        cmd = [self.bridge.adb_path] + device_arg + ["push", local_path, remote_path]

        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
            if result.returncode == 0:
                logger.info(f"推送成功: {os.path.basename(local_path)}")
                return True
            else:
                logger.error(f"推送失败: {result.stderr}")
                return False
        except Exception as e:
            logger.error(f"推送错误: {e}")
            return False

    def pull_file(self, remote_path: str, local_path: str = None) -> bool:
        """从手机拉取文件"""
        if local_path is None:
            filename = os.path.basename(remote_path)
            local_path = os.path.join(self.pull_target, filename)

        logger.info(f"拉取: {remote_path} -> {local_path}")

        device_arg = ["-s", self.bridge.device_serial] if self.bridge.device_serial else []
        cmd = [self.bridge.adb_path] + device_arg + ["pull", remote_path, local_path]

        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
            if result.returncode == 0:
                logger.info(f"拉取成功: {local_path}")
                return True
            else:
                logger.error(f"拉取失败: {result.stderr}")
                return False
        except Exception as e:
            logger.error(f"拉取错误: {e}")
            return False

    def list_files(self, remote_path: str) -> str:
        """列出手机目录文件"""
        output, _, code = self.bridge.adb_shell(f"ls -la '{remote_path}'")
        if code == 0 and output:
            logger.info(f"{remote_path} 内容:\n{output}")
        else:
            logger.warning(f"无法访问: {remote_path}")
        return output or ""

    def install_apk(self, apk_path: str) -> bool:
        """安装 APK"""
        logger.info(f"安装 APK: {apk_path}")
        device_arg = ["-s", self.bridge.device_serial] if self.bridge.device_serial else []
        cmd = [self.bridge.adb_path] + device_arg + ["install", "-r", apk_path]

        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
            if result.returncode == 0:
                logger.info("APK 安装成功!")
                return True
            else:
                logger.error(f"APK 安装失败: {result.stderr}")
                return False
        except Exception as e:
            logger.error(f"安装错误: {e}")
            return False
