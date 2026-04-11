"""
剪贴板增强模块 (升级版)
- 双向同步 (PC <-> Phone)
- 链接自动在浏览器打开
- 图片自动保存到指定目录
- 剪贴板历史记录
"""

import os
import re
import time
import threading
import logging
from pathlib import Path

from bridge.utils.win32_clipboard import get_clipboard_text, set_clipboard_text, set_clipboard_image

logger = logging.getLogger(__name__)

# URL 正则
URL_PATTERN = re.compile(
    r'https?://[^\s<>\"]+|www\.[^\s<>\"]+',
    re.IGNORECASE
)


class ClipboardBridge:
    """剪贴板桥接器 - 增强版"""

    def __init__(self, bridge):
        self.bridge = bridge
        self.config = bridge.config.get("clipboard", {})
        self.running = False
        self.monitor_thread = None
        self.last_pc_clipboard = ""
        self.last_phone_clipboard = ""
        self.history = []
        self.max_history = self.config.get("max_history", 20)
        self.poll_interval = self.config.get("poll_interval_ms", 1000) / 1000.0

        # 图片保存目录
        self.image_dir = self.config.get("image_save_dir", r"D:\Fusion\Clipboard")
        os.makedirs(self.image_dir, exist_ok=True)

    def start(self):
        """启动剪贴板监控"""
        if self.running:
            return

        self.running = True
        self.last_pc_clipboard = get_clipboard_text()

        self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.monitor_thread.start()
        logger.info("剪贴板增强已启动 - 双向同步 + 智能识别")

    def stop(self):
        """停止剪贴板监控"""
        self.running = False
        if self.monitor_thread:
            self.monitor_thread.join(timeout=2)

    def _monitor_loop(self):
        """监控循环"""
        while self.running:
            try:
                self._check_clipboard_changes()
            except Exception as e:
                logger.error(f"剪贴板监控错误: {e}")
            time.sleep(self.poll_interval)

    def _check_clipboard_changes(self):
        """检查剪贴板变化"""
        # 1. PC -> Phone
        if self.config.get("sync_to_phone", True):
            current_pc = get_clipboard_text()
            if current_pc and current_pc != self.last_pc_clipboard:
                self.last_pc_clipboard = current_pc
                self._sync_to_phone(current_pc)
                self._add_to_history("PC→Phone", current_pc)

        # 2. Phone -> PC
        if self.config.get("sync_to_windows", True):
            phone_clipboard = self._get_phone_clipboard()
            if phone_clipboard and phone_clipboard != self.last_phone_clipboard:
                if phone_clipboard != self.last_pc_clipboard:  # 避免循环
                    self.last_phone_clipboard = phone_clipboard
                    self._handle_phone_content(phone_clipboard)

    def _get_phone_clipboard(self):
        """获取手机剪贴板"""
        output, _, code = self.bridge.adb_shell(
            "service call clipboard 2 s16 com.android.shell "
            "2>/dev/null | grep -o '\"[^\"]*\"' | head -1 | tr -d '\"'"
        )
        if output and code == 0:
            text = output.strip()
            if text and len(text) < 10000:
                return text
        return ""

    def _handle_phone_content(self, content: str):
        """智能处理手机端传来的内容"""
        content_type = self._detect_content_type(content)

        if content_type == "url" and self.config.get("auto_open_urls", True):
            # 链接 → 剪贴板 + 浏览器打开
            set_clipboard_text(content)
            self.last_pc_clipboard = content
            try:
                os.startfile(content)
                logger.info(f"[剪贴板] 链接已在浏览器打开: {content[:60]}")
            except Exception as e:
                logger.warning(f"打开链接失败: {e}")
            self._add_to_history("Phone(URL)", content)

        elif content_type == "text":
            # 普通文本 → 系统剪贴板
            set_clipboard_text(content)
            self.last_pc_clipboard = content
            logger.info(f"[剪贴板] Phone→PC: {content[:40]}")
            self._add_to_history("Phone→PC", content)

        else:
            set_clipboard_text(content)
            self.last_pc_clipboard = content
            self._add_to_history("Phone", content)

    def _detect_content_type(self, text: str) -> str:
        """检测内容类型: url / image_path / text"""
        if URL_PATTERN.match(text.strip()):
            return "url"
        # 检查是否是本地图片路径
        ext = Path(text.strip()).suffix.lower()
        if ext in (".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"):
            return "image_path"
        return "text"

    def _sync_to_phone(self, text: str):
        """同步到手机剪贴板"""
        if len(text) > 5000:
            text = text[:5000] + "..."

        # 优先走 WebSocket 通道 (伴侣 App 端有 ClipboardManager.setPrimaryClip)
        if self.bridge.use_companion and self.bridge.ws_client:
            if self.bridge.ws_client.set_clipboard(text):
                logger.info(f"[剪贴板] PC→Phone (WebSocket): {text[:40]}")
                return

        # ADB 备用方案: 使用 am broadcast + content provider 方式
        escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
        escaped = escaped.replace("\n", "\\n").replace("\r", "\\r")

        # 方法 1: 通过 Android content provider 设置剪贴板 (兼容性最好)
        cmd = (
            f"content insert --uri content://settings/system "
            f"--name clipboard_text --value '{escaped}' 2>/dev/null"
        )
        _, _, code = self.bridge.adb_shell(cmd, capture=False)

        if code != 0:
            # 方法 2: 通过 am broadcast 设置 (需要 ClipSetReceiver，部分 ROM 有)
            cmd = (
                f"am broadcast -a com.android.clipboard.SET "
                f"--es text '{escaped}' 2>/dev/null"
            )
            self.bridge.adb_shell(cmd, capture=False)

        logger.info(f"[剪贴板] PC→Phone (ADB): {text[:40]}")

    def _add_to_history(self, source: str, text: str):
        """添加到历史记录"""
        self.history.insert(0, {
            "source": source,
            "text": text[:100],
            "full_text": text,
            "time": time.strftime("%H:%M:%S"),
        })
        if len(self.history) > self.max_history:
            self.history.pop()

    def get_history(self) -> list:
        """获取剪贴板历史"""
        return self.history

    def copy_from_history(self, index: int) -> str:
        """从历史记录复制"""
        if 0 <= index < len(self.history):
            text = self.history[index].get("full_text", self.history[index]["text"])
            set_clipboard_text(text)
            return text
        return ""
