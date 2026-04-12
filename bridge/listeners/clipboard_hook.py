"""
Win32 剪贴板实时变化监听
使用 AddClipboardFormatListener 替代轮询
零延迟，零 CPU 开销
"""

import ctypes
import ctypes.wintypes
import threading
import logging

logger = logging.getLogger(__name__)

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

# 64 位安全的 API 声明
kernel32.GetModuleHandleW.restype = ctypes.c_void_p
kernel32.GetModuleHandleW.argtypes = [ctypes.c_wchar_p]
user32.CreateWindowExW.restype = ctypes.wintypes.HWND
user32.AddClipboardFormatListener.argtypes = [ctypes.wintypes.HWND]
user32.AddClipboardFormatListener.restype = ctypes.c_bool
user32.RemoveClipboardFormatListener.argtypes = [ctypes.wintypes.HWND]
user32.RemoveClipboardFormatListener.restype = ctypes.c_bool

# Windows 消息
WM_CLIPBOARDUPDATE = 0x031D
WM_DESTROY = 0x0002
WM_QUIT = 0x0012

# 手动定义 WNDCLASSW (ctypes.wintypes 不包含此结构体)
class WNDCLASSW(ctypes.Structure):
    _fields_ = [
        ("style", ctypes.c_uint),
        ("lpfnWndProc", ctypes.c_void_p),
        ("cbClsExtra", ctypes.c_int),
        ("cbWndExtra", ctypes.c_int),
        ("hInstance", ctypes.wintypes.HINSTANCE),
        ("hIcon", ctypes.wintypes.HICON),
        ("hCursor", ctypes.wintypes.HANDLE),
        ("hbrBackground", ctypes.wintypes.HBRUSH),
        ("lpszMenuName", ctypes.c_wchar_p),
        ("lpszClassName", ctypes.c_wchar_p),
    ]

# WNDPROC 回调函数类型
# 64 位系统上 LRESULT/WPARAM/LPARAM 都是 8 字节，必须用 c_ssize_t / c_size_t
LRESULT = ctypes.c_ssize_t

WNDPROC = ctypes.CFUNCTYPE(
    LRESULT,
    ctypes.wintypes.HWND,
    ctypes.c_uint,
    ctypes.c_size_t,  # WPARAM (unsigned, pointer-sized)
    ctypes.c_ssize_t,  # LPARAM (signed, pointer-sized)
)

# 正确声明 CreateWindowExW 签名 (防止 64 位 overflow)
user32.CreateWindowExW.argtypes = [
    ctypes.c_uint,        # dwExStyle
    ctypes.c_wchar_p,     # lpClassName
    ctypes.c_wchar_p,     # lpWindowName
    ctypes.c_uint,        # dwStyle
    ctypes.c_int,         # x
    ctypes.c_int,         # y
    ctypes.c_int,         # nWidth
    ctypes.c_int,         # nHeight
    ctypes.wintypes.HWND, # hWndParent
    ctypes.wintypes.HMENU,# hMenu
    ctypes.c_void_p,      # hInstance (64 位安全)
    ctypes.c_void_p,      # lpParam
]
user32.CreateWindowExW.restype = ctypes.wintypes.HWND

# 正确声明 DefWindowProcW 签名 (防止 64 位 overflow)
user32.DefWindowProcW.argtypes = [
    ctypes.wintypes.HWND,
    ctypes.c_uint,
    ctypes.c_size_t,   # WPARAM
    ctypes.c_ssize_t,  # LPARAM
]
user32.DefWindowProcW.restype = LRESULT

# 正确声明 PostMessageW / GetMessageW 签名 (防止 64 位 overflow)
user32.PostMessageW.argtypes = [
    ctypes.wintypes.HWND,
    ctypes.c_uint,
    ctypes.c_size_t,   # WPARAM
    ctypes.c_ssize_t,  # LPARAM
]
user32.PostMessageW.restype = ctypes.c_bool

user32.GetMessageW.argtypes = [
    ctypes.POINTER(ctypes.wintypes.MSG),
    ctypes.wintypes.HWND,
    ctypes.c_uint,
    ctypes.c_uint,
]
user32.GetMessageW.restype = ctypes.c_bool

user32.DestroyWindow.argtypes = [ctypes.wintypes.HWND]
user32.DestroyWindow.restype = ctypes.c_bool

user32.UnregisterClassW.argtypes = [ctypes.c_wchar_p, ctypes.c_void_p]
user32.UnregisterClassW.restype = ctypes.c_bool

user32.RegisterClassW.argtypes = [ctypes.POINTER(WNDCLASSW)]
user32.RegisterClassW.restype = ctypes.c_ushort


class ClipboardChangeListener:
    """剪贴板实时变化监听器

    使用 Win32 AddClipboardFormatListener API，
    当剪贴板内容变化时立即收到 WM_CLIPBOARDUPDATE 消息，
    无需轮询，零延迟零 CPU 开销。
    """

    def __init__(self):
        self._running = False
        self._thread = None
        self._hwnd = None
        self._on_change = None
        self._suppress_next = False  # 防回弹: 跳过下一次变化通知

    def on_change(self, callback):
        """注册剪贴板变化回调

        Args:
            callback: 无参数函数，剪贴板变化时调用
        """
        self._on_change = callback

    def suppress_next(self):
        """抑制下一次剪贴板变化通知 (防回弹)

        当外部代码主动设置剪贴板时调用此方法，
        避免 Hook 回调将被自己设置的内容又推送回去。
        """
        self._suppress_next = True

    def _safe_call(self, callback):
        """安全执行回调 (独立线程)"""
        try:
            callback()
        except Exception as e:
            logger.error(f"剪贴板变化回调错误: {e}")

    def start(self):
        """启动监听"""
        if self._running:
            return

        self._running = True
        self._thread = threading.Thread(target=self._message_loop, daemon=True)
        self._thread.start()
        logger.info("剪贴板实时监听已启动 (Win32 Hook)")

    def stop(self):
        """停止监听"""
        self._running = False
        if self._hwnd:
            user32.PostMessageW(self._hwnd, WM_QUIT, 0, 0)
        if self._thread:
            self._thread.join(timeout=3)

    def _message_loop(self):
        """Win32 消息循环"""
        # 创建窗口过程回调 (必须保持引用防止 GC)
        self._wnd_proc_cb = WNDPROC(self._wnd_proc)

        # 注册窗口类
        wnd_class = WNDCLASSW()
        wnd_class.lpfnWndProc = ctypes.cast(self._wnd_proc_cb, ctypes.c_void_p)
        wnd_class.hInstance = kernel32.GetModuleHandleW(None)
        wnd_class.lpszClassName = "FusionClipboardListener"

        if not user32.RegisterClassW(ctypes.byref(wnd_class)):
            logger.error("注册窗口类失败")
            return

        # 创建隐藏窗口
        self._hwnd = user32.CreateWindowExW(
            0, wnd_class.lpszClassName, "FusionClipboardListener",
            0, 0, 0, 0, 0, 0, 0, wnd_class.hInstance, None
        )

        if not self._hwnd:
            logger.error("创建窗口失败")
            return

        # 注册剪贴板监听
        if not user32.AddClipboardFormatListener(self._hwnd):
            logger.error("注册剪贴板监听失败 (需要 Windows Vista+)")
            return

        # 消息循环
        msg = ctypes.wintypes.MSG()
        while self._running:
            ret = user32.GetMessageW(ctypes.byref(msg), None, 0, 0)
            if ret <= 0:
                break
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))

        # 清理
        user32.RemoveClipboardFormatListener(self._hwnd)
        user32.DestroyWindow(self._hwnd)
        user32.UnregisterClassW(wnd_class.lpszClassName, wnd_class.hInstance)

    def _wnd_proc(self, hwnd, msg, wparam, lparam):
        """窗口过程"""
        if msg == WM_CLIPBOARDUPDATE:
            if self._suppress_next:
                # 跳过此次变化 (由自身 set_clipboard_text 触发)
                self._suppress_next = False
                logger.info("剪贴板变化 (已抑制回弹)")
                return 0

            logger.info("剪贴板变化检测到 (WM_CLIPBOARDUPDATE)")
            if self._on_change:
                # 在独立线程执行回调，避免阻塞消息循环
                # 回调中可能需要 time.sleep 重试读取剪贴板
                threading.Thread(target=self._safe_call, args=(self._on_change,), daemon=True).start()
            return 0

        if msg == WM_DESTROY:
            user32.PostQuitMessage(0)
            return 0

        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)
