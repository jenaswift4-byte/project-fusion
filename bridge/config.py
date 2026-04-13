"""
Project Fusion 配置管理
"""

import os
import yaml
from pathlib import Path


# 默认配置
DEFAULT_CONFIG = {
    "scrcpy": {
        "path": r"C:\Users\wang\Desktop\scrcpy-win64-v3.3.4",
        "window_title": "Phone",
        "x": 1440,
        "y": 0,
        "width": 480,
        "height": 1080,
        "max_fps": 60,
        "bit_rate": "8M",
        "always_on_top": True,
        "no_border": True,
        "turn_screen_off": True,
        "stay_awake": True,
    },
    "adb": {
        "path": r"C:\Users\wang\Desktop\scrcpy-win64-v3.3.4\adb.exe",
        "wifi_auto_connect": True,
        "wifi_port": 5555,
    },
    "clipboard": {
        "sync_to_windows": True,
        "sync_to_phone": True,
        "image_save_dir": r"D:\Fusion\Clipboard",
        "auto_open_urls": True,
        "poll_interval_ms": 1000,
        "max_history": 20,
    },
    "notification": {
        "show_toast": True,
        "auto_focus_scrcpy": True,
        "poll_interval_ms": 2000,
        "ignore_packages": [
            "com.android.systemui",
            "com.android.launcher",
            "android",
        ],
    },
    "telephony": {
        "show_call_popup": True,
        "auto_focus_scrcpy": True,
        "poll_interval_ms": 2000,
    },
    "file": {
        "push_target": "/sdcard/Download/",
        "pull_target": str(Path.home() / "Downloads"),
    },
    "kde_connect": {
        "enabled": False,  # Phase 2+ 再启用
        "device_id": "auto",
        "cli_path": "kdeconnect-cli",
        "poll_interval_ms": 1000,
    },
    "input_leap": {
        "enabled": False,  # Phase 3 再启用
        "config_path": "",
    },
    "battery": {
        "poll_interval_ms": 30000,
        "low_threshold": 20,
        "critical_threshold": 10,
    },
    "screenshot": {
        "save_dir": r"D:\Fusion\Screenshots",
        "auto_clipboard": True,
        "format": "png",
    },
    "sms": {
        "enabled": True,
        "poll_interval_ms": 5000,
        "max_display": 20,
    },
    "handoff": {
        "enabled": True,
        "clipboard_urls": True,
        "open_on_phone": True,
    },
    "audio": {
        "enabled": True,
        "mic_enabled": False,
        "mic_save_dir": r"D:\Fusion\Audio",
        "scrcpy_audio": False,
    },
    "hotkeys": {
        "enabled": True,
        "prefix": "win+shift",
    },
    "dnd": {
        "enabled": True,
        "check_interval_ms": 2000,
        "bypass_packages": ["com.tencent.mobileqq", "com.whatsapp"],
    },
    "proximity": {
        "enabled": False,
        "device_mac": "",
        "device_name": "",
        "rssi_threshold": -10,
        "auto_connect": True,
        "auto_disconnect": False,
    },
    "devices": {
        # 设备友好名称映射: ADB序列号/MQTT device_id → 中文标注
        # 按房间位置标注，方便区分
        "7254adb5": {"name": "客厅监控", "location": "客厅", "role": "摄像头+传感器+音箱"},
        # "device_serial_2": {"name": "卧室传感器", "location": "卧室", "role": "传感器+闹钟"},
        # "device_serial_3": {"name": "门口对讲", "location": "门口", "role": "摄像头+门铃"},
    },
    "mqtt": {
        "enabled": True,
        "port": 1883,
        "auto_start": True,
    },
    "dashboard": {
        "enabled": True,
        "port": 8080,
    },
    "paths": {
        "clipboard_images": r"D:\Fusion\Clipboard",
        "logs": r"D:\Fusion\Logs",
    },
}


def load_config(config_path: str = None) -> dict:
    """加载配置文件，不存在则使用默认值并创建"""
    if config_path is None:
        config_path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "config.yaml"
        )

    config = DEFAULT_CONFIG.copy()

    if os.path.exists(config_path):
        with open(config_path, "r", encoding="utf-8") as f:
            user_config = yaml.safe_load(f) or {}
        config = _deep_merge(config, user_config)
    else:
        # 写出默认配置供用户修改
        os.makedirs(os.path.dirname(config_path), exist_ok=True)
        with open(config_path, "w", encoding="utf-8") as f:
            yaml.dump(config, f, allow_unicode=True, default_flow_style=False)
        print(f"[Config] 已生成默认配置: {config_path}")

    # 确保必要目录存在
    for path_key in ["clipboard_images", "logs"]:
        path = config["paths"].get(path_key, "")
        if path:
            os.makedirs(path, exist_ok=True)

    return config


def _deep_merge(base: dict, override: dict) -> dict:
    """深度合并字典"""
    result = base.copy()
    for key, value in override.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = _deep_merge(result[key], value)
        else:
            result[key] = value
    return result
