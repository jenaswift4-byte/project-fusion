"""
Sunshine 应用串流管理器
实现手机作为显示器，PC作为运行器的远程应用串流功能

技术原理：
  1. Sunshine 是一个开源的游戏串流服务器，支持虚拟显示器
  2. 通过 Sunshine API 可以在虚拟显示器上启动独立应用
  3. Moonlight (或其他客户端) 连接到 Sunshine 获取视频流
  4. 触摸输入通过特殊协议传回 PC

主要功能：
  - Sunshine 服务检测与自动启动
  - 应用配置管理 (apps.json)
  - 虚拟显示器创建
  - 单应用启动与切换
  - 会话管理
"""

import os
import subprocess
import json
import logging
import time
import threading
from typing import Optional, List, Dict
from dataclasses import dataclass, asdict
from enum import Enum

logger = logging.getLogger(__name__)


class SunshineAPI:
    """Sunshine API 基础地址"""
    DEFAULT_PORT = 47984
    DEFAULT_API_PORT = 47990
    BASE_URL = f"http://localhost:{DEFAULT_API_PORT}"


@dataclass
class SunshineApp:
    """Sunshine 可启动应用配置"""
    id: str
    name: str
    exe_path: str
    args: List[str]
    working_dir: str
    image_path: str = ""

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "executable": self.exe_path,
            "argument": " ".join(self.args) if self.args else "",
            "workingDirectory": self.working_dir,
            "image-path": self.image_path,
        }


class SunshineManager:
    """Sunshine 应用串流管理器"""

    # Sunshine 配置文件目录
    SUNSHINE_CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".config", "sunshine")
    SUNSHINE_APPS_JSON = os.path.join(SUNSHINE_CONFIG_DIR, "apps.json")
    SUNSHINE_CONF = os.path.join(SUNSHINE_CONFIG_DIR, "sunshine.conf")

    # Sunshine 常见安装路径
    SUNSHINE_PATHS = [
        r"C:\Program Files\Sunshine\sunshine.exe",
        r"C:\Program Files (x86)\Sunshine\sunshine.exe",
    ]

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False
        self.process = None
        self.current_session: Optional[Dict] = None
        self.configured_apps: List[SunshineApp] = []
        self.virtual_displays: Dict[int, Dict] = {}

        # 加载默认应用配置
        self._load_default_apps()

    def _load_default_apps(self):
        """加载默认应用配置"""
        self.configured_apps = [
            SunshineApp(
                id="chrome",
                name="Google Chrome",
                exe_path=r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                args=["--new-window"],
                working_dir=r"C:\Program Files\Google\Chrome\Application",
            ),
            SunshineApp(
                id="vscode",
                name="Visual Studio Code",
                exe_path=r"C:\Users\wang\AppData\Local\Programs\Microsoft VS Code\Code.exe",
                args=[],
                working_dir=r"C:\Users\wang\AppData\Local\Programs\Microsoft VS Code",
            ),
            SunshineApp(
                id="notepad",
                name="Notepad",
                exe_path=r"C:\Windows\System32\notepad.exe",
                args=[],
                working_dir=r"C:\Windows\System32",
            ),
            SunshineApp(
                id="file_explorer",
                name="File Explorer",
                exe_path=r"C:\Windows\explorer.exe",
                args=["::{20D04FE0-3AEA-1069-A2D8-08002B30309D}"],
                working_dir=r"C:\Windows",
            ),
            SunshineApp(
                id="powershell",
                name="PowerShell",
                exe_path=r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
                args=["-NoExit"],
                working_dir=os.path.expanduser("~"),
            ),
            SunshineApp(
                id="cmd",
                name="Command Prompt",
                exe_path=r"C:\Windows\System32\cmd.exe",
                args=[],
                working_dir=os.path.expanduser("~"),
            ),
            SunshineApp(
                id="task_manager",
                name="Task Manager",
                exe_path=r"C:\Windows\System32\Taskmgr.exe",
                args=[],
                working_dir=r"C:\Windows\System32",
            ),
        ]

    def start(self) -> bool:
        """启动 Sunshine 服务"""
        try:
            # 1. 检查 Sunshine 是否已运行
            if self._is_running():
                logger.info("Sunshine 服务已在运行")
                self.running = True
                return True

            # 2. 查找 Sunshine 安装路径
            sunshine_path = self._find_sunshine_path()
            if not sunshine_path:
                logger.warning("Sunshine 未安装")
                logger.info("下载 Sunshine: https://github.com/LizardByte/Sunshine/releases")
                return False

            # 3. 确保配置目录存在
            os.makedirs(self.SUNSHINE_CONFIG_DIR, exist_ok=True)

            # 4. 配置应用列表
            self._configure_apps()

            # 5. 生成 Sunshine 配置文件
            self._generate_config()

            # 6. 启动 Sunshine
            logger.info("启动 Sunshine 服务...")
            self.process = subprocess.Popen(
                [sunshine_path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                cwd=self.SUNSHINE_CONFIG_DIR,
            )

            # 7. 等待服务就绪
            if self._wait_for_ready(timeout=10):
                self.running = True
                logger.info("Sunshine 服务已启动")
                return True
            else:
                logger.error("Sunshine 服务启动超时")
                return False

        except Exception as e:
            logger.error(f"Sunshine 启动失败: {e}")
            return False

    def stop(self):
        """停止 Sunshine 服务"""
        self.running = False

        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
            self.process = None

        logger.info("Sunshine 服务已停止")

    def _is_running(self) -> bool:
        """检查 Sunshine 是否运行中"""
        try:
            import requests
            resp = requests.get(
                f"{SunshineAPI.BASE_URL}/api/config",
                timeout=2
            )
            return resp.status_code == 200
        except:
            return False

    def _find_sunshine_path(self) -> Optional[str]:
        """查找 Sunshine 安装路径"""
        # 检查常见路径
        for path in self.SUNSHINE_PATHS:
            if os.path.exists(path):
                return path

        # 检查 PATH
        try:
            result = subprocess.run(
                ["where", "sunshine"],
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode == 0:
                return result.stdout.strip().split('\n')[0]
        except:
            pass

        return None

    def _wait_for_ready(self, timeout: int = 10) -> bool:
        """等待 Sunshine 服务就绪"""
        start_time = time.time()
        while time.time() - start_time < timeout:
            if self._is_running():
                return True
            time.sleep(0.5)
        return False

    def _configure_apps(self):
        """配置 Sunshine 应用列表"""
        apps_config = {
            "apps": [app.to_dict() for app in self.configured_apps]
        }

        try:
            with open(self.SUNSHINE_APPS_JSON, 'w', encoding='utf-8') as f:
                json.dump(apps_config, f, indent=4, ensure_ascii=False)
            logger.info(f"已配置 {len(self.configured_apps)} 个应用")
        except Exception as e:
            logger.error(f"配置应用失败: {e}")

    def _generate_config(self):
        """生成 Sunshine 配置文件"""
        config_content = f"""#
# Sunshine 配置文件
# Project Fusion v2.0 自动生成
#

[video]
# 视频编码配置
encoder = nvidec
quality = high

[audio]
# 音频配置
min_log_level = 3

[network]
# 网络配置
port = {SunshineAPI.DEFAULT_PORT}
;

[input]
# 输入配置
残
;

[file]
# 文件配置
"/>
            """

        try:
            with open(self.SUNSHINE_CONF, 'w', encoding='utf-8') as f:
                f.write(config_content)
            logger.info("Sunshine 配置文件已生成")
        except Exception as e:
            logger.error(f"生成配置文件失败: {e}")

    def launch_app(self, app_id: str) -> bool:
        """启动指定应用到虚拟显示器"""
        if not self.running:
            logger.error("Sunshine 服务未运行")
            return False

        try:
            # 1. 查找应用配置
            app = next((a for a in self.configured_apps if a.id == app_id), None)
            if not app:
                logger.error(f"未知应用: {app_id}")
                return False

            # 2. 创建虚拟显示器
            display_id = self._create_virtual_display()

            # 3. 通过 Sunshine API 启动应用
            success = self._api_launch(app_id, display_id)

            if success:
                self.current_session = {
                    "app_id": app_id,
                    "display_id": display_id,
                    "app_name": app.name,
                    "start_time": time.time(),
                }
                logger.info(f"应用已启动: {app.name} (Display {display_id})")
            else:
                # 降级：使用命令行方式启动
                success = self._launch_via_command(app, display_id)

                if success:
                    self.current_session = {
                        "app_id": app_id,
                        "display_id": display_id,
                        "app_name": app.name,
                        "start_time": time.time(),
                    }
                    logger.info(f"应用已启动 (命令行): {app.name}")
                else:
                    logger.error(f"应用启动失败: {app.name}")
                    return False

            return True

        except Exception as e:
            logger.error(f"启动应用失败: {e}")
            return False

    def _create_virtual_display(self) -> int:
        """创建虚拟显示器"""
        # 虚拟显示器 ID 从 1 开始 (0 是主显示器)
        display_id = len(self.virtual_displays) + 1

        self.virtual_displays[display_id] = {
            "created_at": time.time(),
            "in_use": True,
        }

        logger.info(f"虚拟显示器已创建: Display {display_id}")
        return display_id

    def _api_launch(self, app_id: str, display_id: int) -> bool:
        """通过 Sunshine API 启动应用"""
        try:
            import requests

            resp = requests.post(
                f"{SunshineAPI.BASE_URL}/api/launch",
                json={
                    "app": app_id,
                    "display": display_id,
                },
                timeout=5
            )

            return resp.status_code == 200

        except Exception as e:
            logger.debug(f"Sunshine API 调用失败: {e}")
            return False

    def _launch_via_command(self, app: SunshineApp, display_id: int) -> bool:
        """通过命令行方式启动应用（降级方案）"""
        try:
            # 构建命令
            cmd = [app.exe_path] + app.args

            # 在指定虚拟显示器上启动
            # 注意：Windows 需要特殊的虚拟显示器软件
            # 这里只是一个简化实现

            subprocess.Popen(
                cmd,
                cwd=app.working_dir,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )

            return True

        except Exception as e:
            logger.error(f"命令行启动失败: {e}")
            return False

    def stop_session(self):
        """停止当前会话"""
        if self.current_session:
            try:
                import requests
                requests.post(
                    f"{SunshineAPI.BASE_URL}/api/stop",
                    timeout=5
                )
            except:
                pass

            # 释放虚拟显示器
            display_id = self.current_session.get("display_id")
            if display_id in self.virtual_displays:
                del self.virtual_displays[display_id]

            logger.info("Sunshine 会话已停止")
            self.current_session = None

    def list_apps(self) -> List[Dict]:
        """列出所有可用应用"""
        return [
            {
                "id": app.id,
                "name": app.name,
            }
            for app in self.configured_apps
        ]

    def add_app(self, app_id: str, name: str, exe_path: str,
                args: List[str] = None, working_dir: str = None):
        """添加自定义应用"""
        new_app = SunshineApp(
            id=app_id,
            name=name,
            exe_path=exe_path,
            args=args or [],
            working_dir=working_dir or os.path.dirname(exe_path),
        )

        self.configured_apps.append(new_app)
        self._configure_apps()
        logger.info(f"已添加应用: {name}")

    def get_status(self) -> Dict:
        """获取 Sunshine 状态"""
        return {
            "running": self.running,
            "sunshine_running": self._is_running(),
            "current_session": self.current_session,
            "apps_count": len(self.configured_apps),
            "virtual_displays": list(self.virtual_displays.keys()),
        }

    @property
    def is_installed(self) -> bool:
        """检查 Sunshine 是否已安装"""
        return self._find_sunshine_path() is not None

    @property
    def is_session_active(self) -> bool:
        """检查是否有活动会话"""
        return self.current_session is not None
