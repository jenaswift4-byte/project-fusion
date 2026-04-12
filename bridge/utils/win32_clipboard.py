"""
Win32 剪贴板操作工具
支持文本和图片的读写
"""

import ctypes
import ctypes.wintypes
import time
import logging

logger = logging.getLogger(__name__)

# Win32 常量
CF_TEXT = 1
CF_UNICODETEXT = 13
CF_DIB = 8
CF_HDROP = 15

GMEM_MOVEABLE = 0x0002
GMEM_ZEROINIT = 0x0040

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

# 64 位安全的 API 声明 (返回值和 handle 参数必须用 c_void_p 避免截断)
kernel32.GlobalAlloc.restype = ctypes.c_void_p
kernel32.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
kernel32.GlobalFree.argtypes = [ctypes.c_void_p]
user32.GetClipboardData.restype = ctypes.c_void_p
user32.GetClipboardData.argtypes = [ctypes.c_uint]
user32.SetClipboardData.restype = ctypes.c_void_p
user32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]


def get_clipboard_text() -> str:
    """获取剪贴板文本"""
    try:
        if not user32.OpenClipboard(0):
            return ""
        try:
            handle = user32.GetClipboardData(CF_UNICODETEXT)
            if not handle:
                return ""
            ptr = kernel32.GlobalLock(handle)
            if not ptr:
                return ""
            try:
                return ctypes.c_wchar_p(ptr).value or ""
            finally:
                kernel32.GlobalUnlock(handle)
        finally:
            user32.CloseClipboard()
    except Exception as e:
        logger.debug(f"读取剪贴板失败: {e}")
        return ""


def set_clipboard_text(text: str) -> bool:
    """设置剪贴板文本"""
    if not text:
        return False
    try:
        if not user32.OpenClipboard(0):
            return False
        try:
            user32.EmptyClipboard()
            size = (len(text) + 1) * 2  # UTF-16 LE + null terminator
            handle = kernel32.GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, size)
            if not handle:
                return False
            ptr = kernel32.GlobalLock(handle)
            if not ptr:
                kernel32.GlobalFree(handle)
                return False
            try:
                encoded = text.encode("utf-16-le")
                ctypes.memmove(ptr, encoded, len(encoded))
            finally:
                kernel32.GlobalUnlock(handle)  # 解锁 handle，不是 ptr
            result = user32.SetClipboardData(CF_UNICODETEXT, handle)
            if not result:
                logger.error("SetClipboardData failed")
                return False
            return True
        finally:
            user32.CloseClipboard()
    except Exception as e:
        logger.error(f"设置剪贴板失败: {e}")
        return False


def set_clipboard_image(image_path: str) -> bool:
    """将图片文件设置到剪贴板"""
    try:
        from PIL import Image, ImageOps
        import io

        img = Image.open(image_path)
        # 转换为 BMP (DIB 格式)
        output = io.BytesIO()
        img.save(output, "BMP")
        dib_data = output.getvalue()[14:]  # 跳过 BMP 文件头

        if not user32.OpenClipboard(0):
            return False
        try:
            user32.EmptyClipboard()
            handle = kernel32.GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, len(dib_data))
            if not handle:
                return False
            ptr = kernel32.GlobalLock(handle)
            if not ptr:
                kernel32.GlobalFree(handle)
                return False
            try:
                ctypes.memmove(ptr, dib_data, len(dib_data))
            finally:
                kernel32.GlobalUnlock(handle)
            result = user32.SetClipboardData(CF_DIB, handle)
            if not result:
                logger.error("SetClipboardData (image) failed")
                return False
            return True
        finally:
            user32.CloseClipboard()
    except ImportError:
        logger.warning("PIL 未安装，无法设置图片剪贴板。pip install Pillow")
        return False
    except Exception as e:
        logger.error(f"设置图片剪贴板失败: {e}")
        return False
