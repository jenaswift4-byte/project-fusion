"""
Windows Toast 通知工具
使用 PowerShell 发送原生 Windows 10/11 Toast 通知
"""

import subprocess
import logging
import uuid

logger = logging.getLogger(__name__)


def send_toast(title: str, text: str, app_name: str = "Project Fusion") -> bool:
    """发送 Windows Toast 通知

    Args:
        title: 通知标题
        text: 通知内容
        app_name: 应用名称 (显示在通知源)
    """
    if not title:
        return False

    # 清理文本，防止 PowerShell 注入
    title = _escape_ps(title[:100])
    text = _escape_ps(text[:200] if text else "")
    app_id = _escape_ps(app_name)

    ps_script = f'''
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime] | Out-Null

$template = @"
<toast>
  <visual>
    <binding template="ToastText02">
      <text id="1">{title}</text>
      <text id="2">{text}</text>
    </binding>
  </visual>
  <audio silent="true"/>
</toast>
"@

$xml = New-Object Windows.Data.Xml.Dom.XmlDocument
$xml.LoadXml($template)

$notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("{app_id}")
$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
$notifier.Show($toast)
'''

    try:
        subprocess.Popen(
            ["powershell", "-WindowStyle", "Hidden", "-NoProfile", "-Command", ps_script],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return True
    except Exception as e:
        logger.error(f"发送 Toast 通知失败: {e}")
        return False


def send_toast_with_action(title: str, text: str, action_label: str = "查看",
                           app_name: str = "Project Fusion") -> bool:
    """发送带操作按钮的 Toast 通知"""
    title = _escape_ps(title[:100])
    text = _escape_ps(text[:200] if text else "")
    action_label = _escape_ps(action_label)
    app_id = _escape_ps(app_name)

    ps_script = f'''
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime] | Out-Null

$template = @"
<toast>
  <visual>
    <binding template="ToastText02">
      <text id="1">{title}</text>
      <text id="2">{text}</text>
    </binding>
  </visual>
  <actions>
    <action content="{action_label}" arguments="focus"/>
  </actions>
</toast>
"@

$xml = New-Object Windows.Data.Xml.Dom.XmlDocument
$xml.LoadXml($template)

$notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("{app_id}")
$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
$notifier.Show($toast)
'''

    try:
        subprocess.Popen(
            ["powershell", "-WindowStyle", "Hidden", "-NoProfile", "-Command", ps_script],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return True
    except Exception as e:
        logger.error(f"发送 Toast 通知失败: {e}")
        return False


def _escape_ps(text: str) -> str:
    """转义 PowerShell 特殊字符"""
    if not text:
        return ""
    return text.replace('"', '&quot;').replace("'", "&apos;").replace("<", "&lt;").replace(">", "&gt;")
