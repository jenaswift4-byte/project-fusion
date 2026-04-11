"""
窗口焦点监听器
监听 Scrcpy 窗口的获得/失去焦点事件
用于实现键鼠流转的防误触逻辑
"""

import ctypes
import ctypes.wintypes
import threading
import time
import logging

logger = logging.getLogger(__name__)

user32 = ctypes.windll.user32

# Windows 消息常量
WM_ACTIVATE = 0x0006
WA_INACTIVE = 0
WA_ACTIVE = 1
WA_CLICKACTIVE = 2

# Win32 回调类型
WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.wintypes.BOOL, ctypes.wintypes.HWND, ctypes.wintypes.LPARAM)


class WindowFocusListener:
    """窗口焦点监听器"""

    def __init__(self, window_title: str = "Phone"):
        self.window_title = window_title
        self.running = False
        self._thread = None
        self._last_focused = False
        self._on_focus_gained = None
        self._on_focus_lost = None

    def on_focus_gained(self, callback):
        """设置获得焦点回调"""
        self._on_focus_gained = callback

    def on_focus_lost(self, callback):
        """设置失去焦点回调"""
        self._on_focus_lost = callback

    def start(self):
        """启动焦点监听"""
        self.running = True
        self._thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self._thread.start()
        logger.info(f"窗口焦点监听已启动 (窗口: {self.window_title})")

    def stop(self):
        """停止监听"""
        self.running = False
        if self._thread:
            self._thread.join(timeout=2)

    def _find_window(self) -> int:
        """查找窗口句柄"""
        return user32.FindWindowW(None, self.window_title)

    def _is_focused(self) -> bool:
        """检查窗口是否获得焦点"""
        hwnd = self._find_window()
        if not hwnd:
            return False
        foreground = user32.GetForegroundWindow()
        return foreground == hwnd

    def _monitor_loop(self):
        """监控循环"""
        while self.running:
            try:
                current = self._is_focused()

                if current and not self._last_focused:
                    logger.debug("Scrcpy 窗口获得焦点")
                    if self._on_focus_gained:
                        try:
                            self._on_focus_gained()
                        except Exception as e:
                            logger.error(f"焦点获得回调错误: {e}")

                elif not current and self._last_focused:
                    logger.debug("Scrcpy 窗口失去焦点")
                    if self._on_focus_lost:
                        try:
                            self._on_focus_lost()
                        except Exception as e:
                            logger.error(f"焦点失去回调错误: {e}")

                self._last_focused = current

            except Exception as e:
                logger.error(f"焦点监听错误: {e}")

            time.sleep(0.1)  # 100ms 轮询，足够响应
