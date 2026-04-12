"""
链接接力 (Handoff) 模块
浏览器标签跨设备传递 - 手机和 PC 之间共享链接

手机作为 PC 外设扩展板:
  - PC → 手机: 把 PC 当前浏览器链接推送到手机打开
  - 手机 → PC: 手机浏览器链接在 PC 自动打开 (已有: 剪贴板链接自动打开)
  - 批量推送: 收藏/阅读列表跨设备同步
  - 扫描 PC 浏览器活动标签 (Chrome/Edge/Firefox)
"""

import json
import time
import threading
import logging
import subprocess
import ctypes
import ctypes.wintypes as wintypes

logger = logging.getLogger(__name__)

user32 = ctypes.windll.user32


class HandoffBridge:
    """链接接力 - 跨设备链接传递"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False

        # 配置
        handoff_cfg = daemon.config.get("handoff", {})
        self.enabled = handoff_cfg.get("enabled", True)
        self.clipboard_urls = handoff_cfg.get("clipboard_urls", True)  # 剪贴板 URL 自动接力
        self.open_on_phone = handoff_cfg.get("open_on_phone", True)  # PC 链接自动在手机打开

    def start(self):
        """启动链接接力"""
        if not self.enabled:
            logger.info("链接接力已禁用")
            return
        self.running = True
        logger.info("链接接力已启动")

    def stop(self):
        """停止链接接力"""
        self.running = False

    def send_url_to_phone(self, url: str) -> bool:
        """推送链接到手机浏览器打开

        Args:
            url: 要打开的 URL

        Returns:
            是否成功
        """
        if not url or not (url.startswith("http://") or url.startswith("https://")):
            return False

        try:
            # 方式1: 通过 ADB intent 打开手机浏览器
            escaped_url = url.replace("&", "\\&").replace("?", "\\?")
            self.daemon.adb_shell(
                f'am start -a android.intent.action.VIEW -d "{url}"',
                capture=False,
            )
            logger.info(f"[接力] 链接已推送到手机: {url[:60]}")
            return True
        except Exception as e:
            logger.error(f"[接力] 推送链接失败: {e}")
            return False

    def send_url_to_pc(self, url: str) -> bool:
        """推送链接到 PC 浏览器打开 (手机→PC 方向)

        Args:
            url: 要打开的 URL

        Returns:
            是否成功
        """
        if not url or not (url.startswith("http://") or url.startswith("https://")):
            return False

        try:
            import os
            os.startfile(url)
            logger.info(f"[接力] 链接已在 PC 打开: {url[:60]}")
            return True
        except Exception as e:
            logger.error(f"[接力] PC 打开链接失败: {e}")
            return False

    def get_pc_browser_url(self) -> str:
        """获取 PC 当前浏览器活动标签 URL

        支持: Chrome, Edge, Firefox

        Returns:
            URL 字符串，获取失败返回空字符串
        """
        # 通过窗口标题推断 + DDE/OLE 获取 URL
        # 简化方案: 使用 PowerShell 获取浏览器 URL

        # Chrome
        url = self._get_chrome_url()
        if url:
            return url

        # Edge
        url = self._get_edge_url()
        if url:
            return url

        # Firefox
        url = self._get_firefox_url()
        if url:
            return url

        return ""

    def _get_chrome_url(self) -> str:
        """获取 Chrome 当前标签 URL (通过 Niagara/自动化)"""
        # 简化方案: 通过 PowerShell + Chrome 的远程调试 API
        # 更简单: 通过窗口标题提取
        return self._get_browser_url_via_powershell("chrome")

    def _get_edge_url(self) -> str:
        """获取 Edge 当前标签 URL"""
        return self._get_browser_url_via_powershell("msedge")

    def _get_firefox_url(self) -> str:
        """获取 Firefox 当前标签 URL"""
        return self._get_browser_url_via_powershell("firefox")

    def _get_browser_url_via_powershell(self, browser: str) -> str:
        """通过 PowerShell 获取浏览器 URL (通用方案)"""
        # 使用 UI Automation API 获取地址栏内容
        ps_script = f'''
Add-Type -AssemblyName UIAutomationClient
try {{
    $browser = Get-Process {browser} -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($browser) {{
        $element = [System.Windows.Automation.AutomationElement]::FromHandle($browser.MainWindowHandle)
        $cond = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
            [System.Windows.Automation.ControlType]::Edit
        )
        $edit = $element.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
        if ($edit) {{
            $vp = $edit.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
            if ($vp) {{ $vp.Current.Value }}
        }}
    }}
}} catch {{ }}
'''
        try:
            result = subprocess.run(
                ["powershell", "-WindowStyle", "Hidden", "-NoProfile", "-Command", ps_script],
                capture_output=True, text=True, timeout=5,
            )
            url = result.stdout.strip()
            if url and (url.startswith("http://") or url.startswith("https://")):
                return url
        except Exception:
            pass
        return ""

    def send_pc_url_to_phone(self) -> bool:
        """把 PC 当前浏览器链接发送到手机

        Returns:
            是否成功
        """
        url = self.get_pc_browser_url()
        if url:
            return self.send_url_to_phone(url)
        else:
            logger.warning("[接力] 未检测到 PC 浏览器 URL")
            return False

    def push_reading_list(self, urls: list) -> int:
        """批量推送链接到手机

        Args:
            urls: URL 列表

        Returns:
            成功推送数量
        """
        count = 0
        for url in urls:
            if self.send_url_to_phone(url):
                count += 1
            time.sleep(0.5)  # 间隔发送
        logger.info(f"[接力] 批量推送 {count}/{len(urls)} 条链接")
        return count
