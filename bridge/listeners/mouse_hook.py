"""
全局鼠标钩子 + 屏幕边缘检测
用于检测鼠标是否到达屏幕边缘，触发键鼠流转
"""

import ctypes
import ctypes.wintypes
import threading
import time
import logging

logger = logging.getLogger(__name__)

user32 = ctypes.windll.user32

# 钩子类型
WH_MOUSE_LL = 14
WM_MOUSEMOVE = 0x0200

# 回调类型
HOOKPROC = ctypes.CFUNCTYPE(
    ctypes.c_long,
    ctypes.c_int,
    ctypes.wintypes.WPARAM,
    ctypes.wintypes.LPARAM,
)


class MOUSEHOOKSTRUCT(ctypes.Structure):
    _fields_ = [
        ("pt", ctypes.wintypes.POINT),
        ("hwnd", ctypes.wintypes.HWND),
        ("wHitTestCode", ctypes.wintypes.UINT),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class EdgeDetector:
    """鼠标边缘检测器

    检测鼠标是否到达屏幕边缘:
    - 右边缘 → 鼠标进入手机区域 (Scrcpy 窗口在右侧)
    - 左边缘 (从手机回来) → 鼠标回到 Windows

    配合 Scrcpy 窗口使用:
    - 鼠标到达右边缘 → 自动聚焦 Scrcpy，键鼠操控手机
    - 鼠标从 Scrcpy 移出 → 自动失焦，键鼠回到 Windows
    """

    def __init__(self, edge_side="right", trigger_pixels=5):
        self.edge_side = edge_side  # "right" or "left"
        self.trigger_pixels = trigger_pixels
        self._running = False
        self._thread = None
        self._hook_id = None
        self._on_edge_enter = None
        self._on_edge_leave = None
        self._was_at_edge = False
        self._screen_width = user32.GetSystemMetrics(0)  # SM_CXSCREEN
        self._screen_height = user32.GetSystemMetrics(1)  # SM_CYSCREEN

    def on_edge_enter(self, callback):
        """鼠标到达边缘回调"""
        self._on_edge_enter = callback

    def on_edge_leave(self, callback):
        """鼠标离开边缘回调"""
        self._on_edge_leave = callback

    def start(self):
        """启动鼠标钩子"""
        if self._running:
            return

        self._running = True
        self._thread = threading.Thread(target=self._hook_loop, daemon=True)
        self._thread.start()
        logger.info(f"鼠标边缘检测已启动 (边缘: {self.edge_side})")

    def stop(self):
        """停止钩子"""
        self._running = False
        if self._hook_id:
            user32.UnhookWindowsHookEx(self._hook_id)
            self._hook_id = None
        # 发 WM_QUIT 退出消息循环
        user32.PostThreadMessageW(
            self._thread.ident if self._thread else 0, 0x0012, 0, 0  # WM_QUIT
        )
        if self._thread:
            self._thread.join(timeout=3)
        self._callback = None  # 清除回调引用

    def _hook_loop(self):
        """钩子消息循环"""
        # 必须在同一个线程中安装钩子和运行消息循环
        # 关键: 将回调保存为实例变量，防止 Python GC 回收导致崩溃
        self._callback = HOOKPROC(self._low_level_mouse_proc)
        self._hook_id = user32.SetWindowsHookExW(
            WH_MOUSE_LL, self._callback,
            ctypes.windll.kernel32.GetModuleHandleW(None), 0
        )

        if not self._hook_id:
            logger.error("安装鼠标钩子失败")
            return

        # 消息循环
        msg = ctypes.wintypes.MSG()
        while self._running:
            ret = user32.GetMessageW(ctypes.byref(msg), None, 0, 0)
            if ret <= 0:
                break

        # 清理
        if self._hook_id:
            user32.UnhookWindowsHookEx(self._hook_id)
            self._hook_id = None

    def _low_level_mouse_proc(self, nCode, wParam, lParam):
        """低级鼠标钩子回调"""
        if nCode >= 0 and wParam == WM_MOUSEMOVE:
            hook_struct = ctypes.cast(lParam, ctypes.POINTER(MOUSEHOOKSTRUCT)).contents
            x = hook_struct.pt.x
            y = hook_struct.pt.y

            at_edge = False

            # 动态刷新屏幕分辨率
            current_width = user32.GetSystemMetrics(0)
            if current_width != self._screen_width:
                self._screen_width = current_width
                self._screen_height = user32.GetSystemMetrics(1)

            if self.edge_side == "right":
                # 鼠标到达屏幕右边缘
                at_edge = x >= (self._screen_width - self.trigger_pixels)
            elif self.edge_side == "left":
                # 鼠标到达屏幕左边缘
                at_edge = x <= self.trigger_pixels

            if at_edge and not self._was_at_edge:
                self._was_at_edge = True
                if self._on_edge_enter:
                    try:
                        self._on_edge_enter(x, y)
                    except Exception as e:
                        logger.error(f"边缘进入回调错误: {e}")

            elif not at_edge and self._was_at_edge:
                self._was_at_edge = False
                if self._on_edge_leave:
                    try:
                        self._on_edge_leave(x, y)
                    except Exception as e:
                        logger.error(f"边缘离开回调错误: {e}")

        return user32.CallNextHookEx(self._hook_id, nCode, wParam, lParam)
