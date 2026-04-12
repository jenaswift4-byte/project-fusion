"""
快捷截图模块
全局热键触发手机截图 + 自动拉取到 PC

手机作为 PC 外设扩展板:
  - PC 端按 Win+Shift+S 截取手机屏幕
  - 截图自动保存到 PC 指定目录
  - 截图自动放入 PC 剪贴板 (可直接粘贴)
  - 支持连续截图 (快速多次按下)
"""

import os
import time
import subprocess
import threading
import logging

logger = logging.getLogger(__name__)


class ScreenshotBridge:
    """快捷截图 - 手机截图拉取到 PC"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self._last_screenshot = None

        # 配置
        screenshot_cfg = daemon.config.get("screenshot", {})
        self.save_dir = screenshot_cfg.get(
            "save_dir",
            daemon.config.get("paths", {}).get("clipboard_images", r"D:\Fusion\Screenshots")
        )
        self.auto_clipboard = screenshot_cfg.get("auto_clipboard", True)  # 自动放入剪贴板
        self.format = screenshot_cfg.get("format", "png")  # png 或 jpg
        self.quality = screenshot_cfg.get("quality", 100)  # jpg 质量

    def start(self):
        """启动截图模块"""
        self.running = True
        os.makedirs(self.save_dir, exist_ok=True)
        logger.info(f"快捷截图已启动 (保存: {self.save_dir})")

    def stop(self):
        """停止截图模块"""
        self.running = False

    def take_screenshot(self) -> str:
        """截取手机屏幕并拉取到 PC

        Returns:
            保存的文件路径，失败返回 None
        """
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        phone_path = f"/sdcard/Pictures/fusion_screenshot_{timestamp}.{self.format}"
        local_filename = f"phone_{timestamp}.{self.format}"
        local_path = os.path.join(self.save_dir, local_filename)

        try:
            # 1. 手机端截图
            rc = self.daemon.adb_shell(
                f"screencap -p {phone_path}" if self.format == "png"
                else f"screencap -j -q {self.quality} {phone_path}",
                capture=False,
            )

            # 等待截图完成
            time.sleep(0.3)

            # 2. 拉取到 PC
            device_arg = ["-s", self.daemon.device_serial] if self.daemon.device_serial else []
            result = subprocess.run(
                [self.daemon.adb_path] + device_arg + ["pull", phone_path, local_path],
                capture_output=True, text=True, timeout=15,
            )

            if result.returncode == 0 and os.path.exists(local_path):
                # 3. 删除手机端临时文件
                self.daemon.adb_shell(f"rm {phone_path}", capture=False)

                # 4. 放入 PC 剪贴板
                if self.auto_clipboard:
                    self._set_clipboard_image(local_path)

                self._last_screenshot = local_path
                logger.info(f"[截图] 已保存: {local_path}")
                return local_path
            else:
                logger.error(f"[截图] 拉取失败: {result.stderr}")
                return None

        except subprocess.TimeoutExpired:
            logger.error("[截图] 操作超时")
            return None
        except Exception as e:
            logger.error(f"[截图] 失败: {e}")
            return None

    def take_screenshot_async(self, callback=None):
        """异步截图

        Args:
            callback: 截图完成后的回调 (参数: 文件路径或 None)
        """
        def _do():
            path = self.take_screenshot()
            if callback:
                callback(path)

        threading.Thread(target=_do, daemon=True).start()

    def _set_clipboard_image(self, image_path: str):
        """将图片放入 PC 剪贴板"""
        try:
            from bridge.utils.win32_clipboard import set_clipboard_image
            set_clipboard_image(image_path)
            logger.debug(f"[截图] 已复制到剪贴板: {image_path}")
        except Exception as e:
            logger.warning(f"[截图] 复制到剪贴板失败: {e}")

    def get_last_screenshot(self) -> str:
        """获取最后一次截图路径"""
        return self._last_screenshot

    def open_screenshot_folder(self):
        """打开截图文件夹"""
        os.makedirs(self.save_dir, exist_ok=True)
        os.startfile(self.save_dir)
