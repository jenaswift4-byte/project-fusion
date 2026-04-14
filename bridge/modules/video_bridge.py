"""
视频流转模块
手机 ↔ PC 之间流转视频播放

功能:
  - 手机视频 → PC 播放 (通过 Scrcpy 投屏 + 相册 App 控制)
  - PC 视频 → 手机播放 (通过 ADB am start 打开 URL/本地文件)
  - 视频播放状态同步 (播放/暂停/进度)
  - 快捷投射: PC 正在看的视频一键转到手机继续看

实现方式:
  - 手机→PC: Scrcpy 投屏 + ADB 控制相册/播放器 App
  - PC→手机: ADB am start -a android.intent.action.VIEW -d <url>
  - 状态同步: MQTT fusion/video/{deviceId}/command + response
"""

import os
import time
import logging
import subprocess
from typing import Dict, Optional, Any

logger = logging.getLogger(__name__)


class VideoBridge:
    """视频流转桥接"""

    def __init__(self, daemon):
        self.daemon = daemon
        self.running = False

        # 当前播放状态
        self._current_video: Dict[str, Any] = {}
        self._playback_history: list = []

        # 配置
        video_cfg = daemon.config.get("video", {})
        self.enabled = video_cfg.get("enabled", True)
        self.default_player = video_cfg.get("default_player", "com.miui.videoplayer")
        self._supported_players = {
            "miui": "com.miui.video/.localvideoplayer.LocalPlayerActivity",
            "vlc": "org.videolan.vlc",
            "mx": "com.mxtech.videoplayer.ad",
            "bilibili": "tv.danmaku.bili",
            "youtube": "com.google.android.youtube",
        }

    def start(self):
        """启动视频流转"""
        if not self.enabled:
            logger.info("[视频流转] 已禁用")
            return
        self.running = True
        logger.info("[视频流转] 已启动")

    def stop(self):
        """停止视频流转"""
        self.running = False
        logger.info("[视频流转] 已停止")

    # ═══════════════════════════════════════════════════════
    # PC → 手机: 把视频推到手机播放
    # ═══════════════════════════════════════════════════════

    def cast_url_to_phone(self, url: str, player: str = "") -> bool:
        """
        在手机上打开视频 URL

        Args:
            url: 视频 URL (http/https)
            player: 播放器包名 (空=系统默认)

        Returns:
            是否成功
        """
        if not url:
            return False

        try:
            package = self._supported_players.get(player, player) or ""

            if package:
                # 指定播放器 (package 可能是完整 component 或只有包名)
                if "/" in package:
                    component = package  # 已包含完整 component (如 com.miui.video/.localvideoplayer.LocalPlayerActivity)
                else:
                    component = f"{package}/.ActivityMain"  # 只有包名，拼默认 Activity
                cmd = (
                    f"am start -a android.intent.action.VIEW "
                    f"-d \"{url}\" "
                    f"-t \"video/*\" "
                    f"-n {component}"
                )
            else:
                # 系统默认
                cmd = f"am start -a android.intent.action.VIEW -d \"{url}\" -t \"video/*\""

            _, err, rc = self.daemon.adb_shell(cmd)
            if rc == 0:
                self._current_video = {
                    "url": url,
                    "player": package or "default",
                    "direction": "pc_to_phone",
                    "started_at": time.time(),
                }
                self._playback_history.append(self._current_video.copy())
                logger.info(f"[视频流转] URL 已投射到手机: {url[:60]}")
                return True
            else:
                logger.error(f"[视频流转] 打开 URL 失败: {err}")
                return False

        except Exception as e:
            logger.error(f"[视频流转] 投射失败: {e}")
            return False

    def cast_local_video_to_phone(self, local_path: str) -> bool:
        """
        将本地视频文件推送到手机并播放

        Args:
            local_path: PC 端视频文件路径

        Returns:
            是否成功
        """
        if not os.path.exists(local_path):
            logger.error(f"[视频流转] 文件不存在: {local_path}")
            return False

        try:
            filename = os.path.basename(local_path)
            phone_path = f"/sdcard/Movies/FusionCast/{filename}"

            # 推送文件
            device_arg = ["-s", self.daemon.device_serial] if self.daemon.device_serial else []
            push_cmd = [self.daemon.adb_path] + device_arg + ["push", local_path, phone_path]
            result = subprocess.run(push_cmd, capture_output=True, text=True, timeout=60)

            if result.returncode != 0:
                logger.error(f"[视频流转] 推送文件失败: {result.stderr}")
                return False

            # 播放
            cmd = (
                f"am start -a android.intent.action.VIEW "
                f"-d \"file://{phone_path}\" "
                f"-t \"video/*\""
            )
            _, err, rc = self.daemon.adb_shell(cmd)

            if rc == 0:
                self._current_video = {
                    "local_path": local_path,
                    "phone_path": phone_path,
                    "direction": "pc_to_phone",
                    "started_at": time.time(),
                }
                self._playback_history.append(self._current_video.copy())
                logger.info(f"[视频流转] 本地视频已投射: {filename}")
                return True
            else:
                logger.error(f"[视频流转] 播放失败: {err}")
                return False

        except Exception as e:
            logger.error(f"[视频流转] 本地视频投射失败: {e}")
            return False

    # ═══════════════════════════════════════════════════════
    # 手机 → PC: 手机视频在 PC 上播放
    # ═══════════════════════════════════════════════════════

    def open_gallery_on_phone(self) -> bool:
        """打开手机相册/视频库 (配合 Scrcpy 投屏在 PC 上操控)"""
        try:
            cmd = "am start -a android.intent.action.MAIN -n com.miui.gallery/.home.HomeActivity"
            _, err, rc = self.daemon.adb_shell(cmd)
            if rc != 0:
                # 通用方案
                cmd = "am start -a android.intent.action.VIEW -t vnd.android.cursor.dir/video"
                _, err, rc = self.daemon.adb_shell(cmd)

            if rc == 0:
                # 聚焦 Scrcpy 窗口
                if self.daemon.scrcpy_ctrl:
                    self.daemon.scrcpy_ctrl.bring_to_front()
                logger.info("[视频流转] 相册已打开")
                return True
            return False
        except Exception as e:
            logger.error(f"[视频流转] 打开相册失败: {e}")
            return False

    # ═══════════════════════════════════════════════════════
    # 播放控制 (通过 ADB 模拟按键)
    # ═══════════════════════════════════════════════════════

    def play_pause(self) -> bool:
        """播放/暂停"""
        _, _, rc = self.daemon.adb_shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
        return rc == 0

    def seek_forward(self, seconds: int = 10) -> bool:
        """快进"""
        _, _, rc = self.daemon.adb_shell("input keyevent KEYCODE_MEDIA_FAST_FORWARD")
        return rc == 0

    def seek_backward(self, seconds: int = 10) -> bool:
        """快退"""
        _, _, rc = self.daemon.adb_shell("input keyevent KEYCODE_MEDIA_REWIND")
        return rc == 0

    def stop_playback(self) -> bool:
        """停止播放"""
        _, _, rc = self.daemon.adb_shell("input keyevent KEYCODE_MEDIA_STOP")
        self._current_video = {}
        return rc == 0

    def next_video(self) -> bool:
        """下一个视频"""
        _, _, rc = self.daemon.adb_shell("input keyevent KEYCODE_MEDIA_NEXT")
        return rc == 0

    def previous_video(self) -> bool:
        """上一个视频"""
        _, _, rc = self.daemon.adb_shell("input keyevent KEYCODE_MEDIA_PREVIOUS")
        return rc == 0

    # ═══════════════════════════════════════════════════════
    # 状态查询
    # ═══════════════════════════════════════════════════════

    def get_status(self) -> Dict[str, Any]:
        """获取视频流转状态"""
        return {
            "running": self.running,
            "current_video": self._current_video,
            "history_count": len(self._playback_history),
            "supported_players": list(self._supported_players.keys()),
        }

    def get_history(self, limit: int = 10) -> list:
        """获取播放历史"""
        return self._playback_history[-limit:]
