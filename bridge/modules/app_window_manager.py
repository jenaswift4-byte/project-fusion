"""
手机应用窗口管理器
使用 VirtualDisplay + Scrcpy 实现手机应用在 PC 上的独立窗口化
"""

import logging
import os
import subprocess
import time
from typing import Optional, Dict, List, Tuple

logger = logging.getLogger(__name__)

DEFAULT_APPS_CONFIG = {
    "抖音": {
        "package": "com.ss.android.psycho",
        "activity": ".main.MainActivity",
        "display_width": 1080,
        "display_height": 1920,
    },
    "哔哩哔哩": {
        "package": "tv.danmaku.bili",
        "activity": ".ui.splash.SplashActivity",
        "display_width": 1080,
        "display_height": 1920,
    },
    "微信": {
        "package": "com.tencent.mm",
        "activity": ".ui.LauncherUI",
        "display_width": 1080,
        "display_height": 1920,
    },
    "QQ": {
        "package": "com.tencent.mobileqq",
        "activity": ".activity.SplashActivity",
        "display_width": 1080,
        "display_height": 1920,
    },
    "QQ音乐": {
        "package": "com.tencent.qqmusic",
        "activity": ".activity.AppStarterActivity",
        "display_width": 1080,
        "display_height": 1920,
    },
    "网易云音乐": {
        "package": "com.netease.cloudmusic",
        "activity": ".activity.IconTransitionDefaultActivity",
        "display_width": 1080,
        "display_height": 1920,
    },
    "淘宝": {
        "package": "com.taobao.taobao",
        "activity": ".home.MainActivity3",
        "display_width": 1080,
        "display_height": 1920,
    },
    "京东": {
        "package": "com.jingdong.app.mall",
        "activity": ".main.MainActivity",
        "display_width": 1080,
        "display_height": 1920,
    },
    "钉钉": {
        "package": "com.alibaba.android.rimet",
        "activity": ".biz.attendance.AttendanceActivity",
        "display_width": 1080,
        "display_height": 1920,
    },
    "腾讯会议": {
        "package": "com.tencent.wemeet.app",
        "activity": ".manager.SplashManagerActivity",
        "display_width": 1920,
        "display_height": 1080,
    },
}


class AppWindowManager:
    """手机应用窗口管理器 - 使用虚拟显示器实现多应用独立窗口"""

    def __init__(self, bridge):
        self.bridge = bridge
        self.config = bridge.config
        self.apps_config = self.config.get("app_windows", {}).get("apps", DEFAULT_APPS_CONFIG)

        self.scrcpy_dir = self.config.get("scrcpy", {}).get("path", r"C:\Users\wang\Desktop\scrcpy-win64-v3.3.4")
        self.scrcpy_exe = os.path.join(self.scrcpy_dir, "scrcpy.exe")

        self.running_windows: Dict[str, Dict] = {}
        self.next_display_id = 10

    def adb_shell(self, command: str, timeout: int = 10) -> Tuple[str, str, int]:
        """执行 ADB shell 命令"""
        return self.bridge.adb_shell(command)

    def create_virtual_display(self, width: int = 1080, height: int = 1920, dpi: int = 480) -> Optional[int]:
        """创建 Android 虚拟显示器

        Args:
            width: 虚拟显示器宽度
            height: 虚拟显示器高度
            dpi: 虚拟显示器 DPI

        Returns:
            display_id: 虚拟显示器 ID，失败返回 None
        """
        display_id = self.next_display_id
        self.next_display_id += 1

        size_str = f"{width}x{dpi}"
        density_str = f"{dpi}"

        output, err, code = self.adb_shell(
            f"dumpsys display 2>/dev/null | grep 'mDisplayId=' | grep -v 'Physical' | head -1"
        )

        cmd = (
            f"am display --create-display --size {size_str} --density {density_str} 2>/dev/null || "
            f"dumpsys display 2>/dev/null | grep -E '^  Display ID' | wc -l"
        )

        output, err, code = self.adb_shell(cmd)

        if code == 0:
            try:
                lines = output.strip().split('\n') if output else []
                for line in lines:
                    if 'Display ID' in line or 'display' in line.lower():
                        parts = line.split()
                        for i, part in enumerate(parts):
                            if part.isdigit() and int(part) >= 10:
                                display_id = int(part)
                                break
                        break

                logger.info(f"虚拟显示器已创建 (ID: {display_id}, 分辨率: {width}x{height}, DPI: {dpi})")
                return display_id
            except Exception as e:
                logger.error(f"解析虚拟显示器 ID 失败: {e}")

        logger.warning(f"创建虚拟显示器失败，将使用默认显示器: {err}")
        return None

    def launch_app_window(self, package_name: str, display_id: Optional[int] = None,
                          app_name: Optional[str] = None) -> bool:
        """在虚拟显示器上启动应用并用 Scrcpy 捕获

        Args:
            package_name: 应用包名
            display_id: 虚拟显示器 ID，为 None 时创建新显示器
            app_name: 应用显示名称（用于窗口标题）

        Returns:
            bool: 启动是否成功
        """
        if package_name in self.running_windows:
            logger.warning(f"应用已在运行: {package_name}")
            self._focus_window(package_name)
            return True

        app_info = self._get_app_info(package_name)
        if not app_info:
            logger.error(f"未找到应用配置: {package_name}")
            return False

        if display_id is None:
            display_id = self.create_virtual_display(
                width=app_info.get("display_width", 1080),
                height=app_info.get("display_height", 1920)
            )
            if display_id is None:
                display_id = 0

        launch_activity = app_info.get("activity", f".MainActivity")
        full_activity = launch_activity if launch_activity.startswith(".") else launch_activity

        stop_cmd = f"am force-stop {package_name}"
        self.adb_shell(stop_cmd)
        time.sleep(0.3)

        start_cmd = f"am start -n {package_name}/{full_activity} --display {display_id}"
        output, err, code = self.adb_shell(start_cmd)

        if code != 0:
            logger.error(f"启动应用失败: {err}")
            fallback_cmd = f"monkey -p {package_name} -c android.intent.category.LAUNCHER 1 --display {display_id}"
            output, err, code = self.adb_shell(fallback_cmd)
            if code != 0:
                logger.error(f"monkey 启动应用失败: {err}")
                return False

        time.sleep(1.5)

        window_title = app_name or package_name
        scrcpy_cmd = self._build_scrcpy_command(window_title, display_id)

        try:
            logger.info(f"启动 Scrcpy 窗口: {window_title}, 命令: {' '.join(scrcpy_cmd)}")
            process = subprocess.Popen(
                scrcpy_cmd,
                cwd=self.scrcpy_dir,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )

            time.sleep(2)

            if process.poll() is not None:
                logger.error(f"Scrcpy 进程启动失败")
                return False

            self.running_windows[package_name] = {
                "display_id": display_id,
                "scrcpy_process": process,
                "window_title": window_title,
                "app_info": app_info,
            }

            logger.info(f"应用窗口已启动: {window_title} (包名: {package_name}, 显示器: {display_id})")
            return True

        except FileNotFoundError:
            logger.error(f"Scrcpy 不存在: {self.scrcpy_exe}")
            return False
        except Exception as e:
            logger.error(f"启动应用窗口失败: {e}")
            return False

    def close_app_window(self, package_name: str) -> bool:
        """关闭应用窗口

        Args:
            package_name: 应用包名

        Returns:
            bool: 关闭是否成功
        """
        if package_name not in self.running_windows:
            logger.warning(f"应用窗口未运行: {package_name}")
            self.adb_shell(f"am force-stop {package_name}")
            return True

        window_info = self.running_windows[package_name]
        scrcpy_process = window_info.get("scrcpy_process")

        if scrcpy_process and scrcpy_process.poll() is None:
            scrcpy_process.terminate()
            try:
                scrcpy_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                scrcpy_process.kill()
            logger.info(f"Scrcpy 进程已终止: {package_name}")

        self.adb_shell(f"am force-stop {package_name}")

        display_id = window_info.get("display_id")
        if display_id is not None and display_id != 0:
            self.adb_shell(f"am display --remove {display_id}")

        del self.running_windows[package_name]
        logger.info(f"应用窗口已关闭: {package_name}")
        return True

    def list_running_apps(self) -> List[Dict]:
        """列出正在运行的应用

        Returns:
            List[Dict]: 运行中的应用列表
        """
        running_apps = []

        output, err, code = self.adb_shell(
            "dumpsys activity activities 2>/dev/null | grep 'mResumedActivity\\|mFocusedApp' | head -5"
        )

        if output:
            for line in output.strip().split('\n'):
                if 'ACTIVITY' in line or 'package=' in line:
                    parts = line.split()
                    for part in parts:
                        if part.startswith('package='):
                            pkg = part.split('=')[1].rstrip('}').rstrip('/')
                            if pkg:
                                app_entry = {
                                    "package": pkg,
                                    "display": 0,
                                    "window_title": self._get_app_display_name(pkg),
                                }
                                if app_entry not in running_apps:
                                    running_apps.append(app_entry)

        for pkg, info in self.running_windows.items():
            display_id = info.get("display_id", 0)
            app_entry = {
                "package": pkg,
                "display": display_id,
                "window_title": info.get("window_title", pkg),
            }
            if app_entry not in running_apps:
                running_apps.append(app_entry)

        return running_apps

    def _build_scrcpy_command(self, window_title: str, display_id: int) -> List[str]:
        """构建 Scrcpy 命令参数

        Args:
            window_title: 窗口标题
            display_id: 显示器 ID

        Returns:
            List[str]: Scrcpy 命令参数列表
        """
        scrcpy_cfg = self.config.get("scrcpy", {})

        cmd = [
            self.scrcpy_exe,
            f"--window-title={window_title}",
            f"--display-id={display_id}",
        ]

        if scrcpy_cfg.get("always_on_top", True):
            cmd.append("--always-on-top")
        if scrcpy_cfg.get("no_border", True):
            cmd.append("--window-borderless")

        cmd.extend([
            f"--window-width={scrcpy_cfg.get('width', 540)}",
            f"--window-height={scrcpy_cfg.get('height', 960)}",
            f"--max-fps={scrcpy_cfg.get('max_fps', 60)}",
            f"--video-bit-rate={scrcpy_cfg.get('bit_rate', '4M')}",
        ])

        if scrcpy_cfg.get("turn_screen_off", False):
            cmd.append("--turn-screen-off")
        if scrcpy_cfg.get("stay_awake", True):
            cmd.append("--stay-awake")

        return cmd

    def _get_app_info(self, package_name: str) -> Optional[Dict]:
        """获取应用配置信息

        Args:
            package_name: 应用包名

        Returns:
            Optional[Dict]: 应用配置信息
        """
        for app_name, app_info in self.apps_config.items():
            if app_info.get("package") == package_name:
                return app_info

        for app_name, app_info in DEFAULT_APPS_CONFIG.items():
            if app_info.get("package") == package_name:
                return app_info

        return {
            "package": package_name,
            "activity": ".MainActivity",
            "display_width": 1080,
            "display_height": 1920,
        }

    def _get_app_display_name(self, package_name: str) -> str:
        """获取应用的显示名称

        Args:
            package_name: 应用包名

        Returns:
            str: 应用显示名称
        """
        for app_name, app_info in self.apps_config.items():
            if app_info.get("package") == package_name:
                return app_name

        for app_name, app_info in DEFAULT_APPS_CONFIG.items():
            if app_info.get("package") == package_name:
                return app_name

        return package_name

    def _focus_window(self, package_name: str) -> bool:
        """将指定应用的窗口置顶

        Args:
            package_name: 应用包名

        Returns:
            bool: 是否成功
        """
        if package_name not in self.running_windows:
            return False

        window_title = self.running_windows[package_name].get("window_title", package_name)

        try:
            import ctypes
            user32 = ctypes.windll.user32
            hwnd = user32.FindWindowW(None, window_title)
            if hwnd:
                if user32.IsIconic(hwnd):
                    user32.ShowWindow(hwnd, 9)
                user32.SetForegroundWindow(hwnd)
                user32.BringWindowToTop(hwnd)
                logger.info(f"窗口已置顶: {window_title}")
                return True
        except Exception as e:
            logger.error(f"置顶窗口失败: {e}")

        return False

    def stop_all_windows(self) -> bool:
        """关闭所有应用窗口

        Returns:
            bool: 是否成功
        """
        package_names = list(self.running_windows.keys())
        for package_name in package_names:
            self.close_app_window(package_name)

        logger.info("所有应用窗口已关闭")
        return True


if __name__ == "__main__":
    import sys
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

    from bridge.config import load_config

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s"
    )

    print("=" * 50)
    print("AppWindowManager 测试")
    print("=" * 50)

    config = load_config()
    config["scrcpy"]["path"] = r"C:\Users\wang\Desktop\scrcpy-win64-v3.3.4"

    class MockBridge:
        def __init__(self, cfg):
            self.config = cfg
            self.adb_path = cfg.get("adb", {}).get("path", "adb")
            self.device_serial = None

        def adb_shell(self, command, timeout=10):
            device_arg = ["-s", self.device_serial] if self.device_serial else []
            cmd = [self.adb_path] + device_arg + ["shell", command]
            try:
                result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
                return result.stdout.strip(), result.stderr.strip(), result.returncode
            except subprocess.TimeoutExpired:
                return None, "Timeout", 1
            except Exception as e:
                return None, str(e), 1

    bridge = MockBridge(config)
    manager = AppWindowManager(bridge)

    print("\n支持的应用程序:")
    for name, info in DEFAULT_APPS_CONFIG.items():
        print(f"  - {name}: {info['package']}")

    print("\n测试列出运行中的应用...")
    running = manager.list_running_apps()
    print(f"当前运行中的应用: {running}")
