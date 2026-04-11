"""
开机自启管理模块
注册/取消 Windows 开机自启动
"""

import os
import sys
import logging
import subprocess
from pathlib import Path

logger = logging.getLogger(__name__)

# 注册表路径
REG_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"
APP_NAME = "ProjectFusion"


def is_autostart_enabled() -> bool:
    """检查是否已启用开机自启"""
    try:
        result = subprocess.run(
            ["reg", "query", f"HKCU\\{REG_PATH}", "/v", APP_NAME],
            capture_output=True, text=True, timeout=5,
        )
        return result.returncode == 0
    except Exception:
        return False


def enable_autostart() -> bool:
    """启用开机自启"""
    # 获取启动脚本路径
    script_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    bat_path = os.path.join(script_dir, "start_fusion.bat")

    if not os.path.exists(bat_path):
        logger.error(f"启动脚本不存在: {bat_path}")
        return False

    # 使用 VBScript 无窗口启动
    vbs_path = os.path.join(script_dir, "start_fusion_silent.vbs")
    vbs_content = f'''Set WshShell = CreateObject("WScript.Shell")
WshShell.Run chr(34) & "{bat_path}" & Chr(34), 0
Set WshShell = Nothing
'''
    with open(vbs_path, "w", encoding="utf-8") as f:
        f.write(vbs_content)

    try:
        result = subprocess.run(
            ["reg", "add", f"HKCU\\{REG_PATH}",
             "/v", APP_NAME, "/t", "REG_SZ",
             "/d", f'wscript "{vbs_path}"', "/f"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0:
            logger.info("开机自启已启用")
            return True
        else:
            logger.error(f"注册自启失败: {result.stderr}")
            return False
    except Exception as e:
        logger.error(f"启用开机自启失败: {e}")
        return False


def disable_autostart() -> bool:
    """取消开机自启"""
    try:
        result = subprocess.run(
            ["reg", "delete", f"HKCU\\{REG_PATH}",
             "/v", APP_NAME, "/f"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0:
            logger.info("开机自启已取消")
            return True
        else:
            logger.error(f"取消自启失败: {result.stderr}")
            return False
    except Exception as e:
        logger.error(f"取消开机自启失败: {e}")
        return False
