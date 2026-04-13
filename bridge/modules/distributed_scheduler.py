"""
分布式计算调度器
负责在 Android 和 PC 设备之间智能分配计算任务

核心功能：
  - 设备性能监控
  - 应用迁移管理
  - 负载均衡调度
  - 设备评分系统
"""

import time
import logging
import threading
import subprocess
from typing import Dict, Optional, List
from dataclasses import dataclass, field
from enum import Enum

logger = logging.getLogger(__name__)


class DeviceType(Enum):
    """设备类型枚举"""
    ANDROID = "android"
    WINDOWS = "windows"
    LINUX = "linux"


class AppState(Enum):
    """应用状态枚举"""
    RUNNING = "running"
    PAUSED = "paused"
    MIGRATING = "migrating"
    STOPPED = "stopped"


@dataclass
class DeviceMetrics:
    """设备性能指标"""
    device_type: DeviceType
    device_id: str
    device_name: str

    cpu_usage: float = 0.0
    memory_usage: float = 0.0
    memory_total: float = 0.0
    memory_available: float = 0.0

    gpu_usage: float = 0.0
    gpu_memory_usage: float = 0.0
    gpu_total_memory: float = 0.0
    gpu_name: str = ""

    battery_level: int = 100
    battery_temperature: float = 25.0
    is_charging: bool = False

    cpu_temperature: float = 0.0
    gpu_temperature: float = 0.0

    timestamp: float = field(default_factory=time.time)


@dataclass
class AppInfo:
    """应用信息"""
    app_id: str
    name: str
    package_name: str
    memory_requirement: int
    gpu_requirement: float
    priority: int = 5
    state: AppState = AppState.STOPPED
    current_device: str = ""
    min_cpu_cores: int = 1


class DistributedScheduler:
    """分布式计算调度器"""

    SCORE_THRESHOLD_LOW = 30
    SCORE_THRESHOLD_DIFF = 30
    MONITOR_INTERVAL = 5

    WEIGHT_CPU = 0.25
    WEIGHT_MEMORY = 0.25
    WEIGHT_GPU = 0.25
    WEIGHT_TEMPERATURE = 0.15
    WEIGHT_BATTERY = 0.10

    def __init__(self, daemon=None):
        self.daemon = daemon
        self.known_apps: Dict[str, AppInfo] = {}
        self.registered_apps: Dict[str, AppInfo] = {}
        self.device_metrics: Dict[str, DeviceMetrics] = {}
        self.monitoring = False
        self.monitor_thread: Optional[threading.Thread] = None
        self.lock = threading.Lock()
        self._load_known_apps()

    def _load_known_apps(self):
        """加载已知应用配置"""
        known_apps_config = [
            {
                "app_id": "wechat",
                "name": "微信",
                "package_name": "com.tencent.mm",
                "memory_requirement": 512,
                "gpu_requirement": 0.2,
                "priority": 8,
                "min_cpu_cores": 2
            },
            {
                "app_id": "qq",
                "name": "QQ",
                "package_name": "com.tencent.mobileqq",
                "memory_requirement": 384,
                "gpu_requirement": 0.15,
                "priority": 7,
                "min_cpu_cores": 2
            },
            {
                "app_id": "douyin",
                "name": "抖音",
                "package_name": "com.ss.android.ugc.aweme",
                "memory_requirement": 256,
                "gpu_requirement": 0.3,
                "priority": 6,
                "min_cpu_cores": 2
            },
            {
                "app_id": "bilibili",
                "name": "哔哩哔哩",
                "package_name": "tv.danmaku.bili",
                "memory_requirement": 384,
                "gpu_requirement": 0.25,
                "priority": 6,
                "min_cpu_cores": 2
            },
            {
                "app_id": "chrome",
                "name": "Chrome",
                "package_name": "com.android.chrome",
                "memory_requirement": 512,
                "gpu_requirement": 0.3,
                "priority": 5,
                "min_cpu_cores": 1
            },
            {
                "app_id": "vscode",
                "name": "VSCode",
                "package_name": "",
                "memory_requirement": 1024,
                "gpu_requirement": 0.4,
                "priority": 9,
                "min_cpu_cores": 2
            },
            {
                "app_id": "office",
                "name": "Office",
                "package_name": "",
                "memory_requirement": 512,
                "gpu_requirement": 0.2,
                "priority": 7,
                "min_cpu_cores": 1
            },
            {
                "app_id": "game",
                "name": "游戏",
                "package_name": "",
                "memory_requirement": 2048,
                "gpu_requirement": 0.8,
                "priority": 4,
                "min_cpu_cores": 4
            },
        ]

        for app_config in known_apps_config:
            app_info = AppInfo(**app_config)
            self.known_apps[app_config["app_id"]] = app_info

        logger.info(f"已加载 {len(self.known_apps)} 个已知应用配置")

    def start_monitoring(self):
        """启动性能监控线程"""
        if self.monitoring:
            logger.warning("监控已在运行中")
            return

        self.monitoring = True
        self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.monitor_thread.start()
        logger.info("分布式调度器监控已启动")

    def stop_monitoring(self):
        """停止监控"""
        self.monitoring = False
        if self.monitor_thread:
            self.monitor_thread.join(timeout=5)
        logger.info("分布式调度器监控已停止")

    def _monitor_loop(self):
        """监控循环"""
        while self.monitoring:
            try:
                self._collect_all_metrics()
                self._check_load_balance()
                self.handle_task_timeout()
            except Exception as e:
                logger.debug(f"监控循环错误: {e}")

            time.sleep(self.MONITOR_INTERVAL)

    def _collect_all_metrics(self):
        """采集所有设备指标"""
        with self.lock:
            if self.daemon:
                try:
                    android_metrics = self._collect_android_metrics()
                    if android_metrics:
                        self.device_metrics["android"] = android_metrics
                except Exception as e:
                    logger.debug(f"采集 Android 指标失败: {e}")

            try:
                pc_metrics = self._collect_pc_metrics()
                if pc_metrics:
                    self.device_metrics["pc"] = pc_metrics
            except Exception as e:
                logger.debug(f"采集 PC 指标失败: {e}")

    def _collect_android_metrics(self) -> Optional[DeviceMetrics]:
        """采集 Android 设备指标"""
        if not self.daemon:
            return None

        try:
            metrics = DeviceMetrics(
                device_type=DeviceType.ANDROID,
                device_id="android",
                device_name="Android Device"
            )

            output, _, _ = self.daemon.adb_shell("dumpsys cpuinfo | grep 'cpu'")
            if output:
                import re
                cpu_match = re.search(r"CPU usage: (\d+)% / (\d+)%", output)
                if cpu_match:
                    user_cpu = int(cpu_match.group(1))
                    kernel_cpu = int(cpu_match.group(2))
                    metrics.cpu_usage = min((user_cpu + kernel_cpu) / 2, 100.0)

            output, _, _ = self.daemon.adb_shell("dumpsys meminfo | grep 'Total RAM'")
            if output:
                import re
                mem_match = re.search(r"Total RAM:\s*([\d.]+)(G|M)", output)
                if mem_match:
                    value = float(mem_match.group(1))
                    unit = mem_match.group(2)
                    metrics.memory_total = value * 1024 if unit == "G" else value

            output, _, _ = self.daemon.adb_shell("dumpsys meminfo | grep 'Free RAM'")
            if output:
                import re
                free_match = re.search(r"Free RAM:\s*([\d.]+)(G|M)", output)
                if free_match:
                    value = float(free_match.group(1))
                    unit = free_match.group(2)
                    metrics.memory_available = value * 1024 if unit == "G" else value
                    if metrics.memory_total > 0:
                        metrics.memory_usage = (
                            (metrics.memory_total - metrics.memory_available) /
                            metrics.memory_total * 100
                        )

            output, _, _ = self.daemon.adb_shell(
                "dumpsys battery | grep -E 'level|status|temperature'"
            )
            if output:
                import re
                level_match = re.search(r"level:\s*(\d+)", output)
                if level_match:
                    metrics.battery_level = int(level_match.group(1))

                temp_match = re.search(r"temperature:\s*(\d+)", output)
                if temp_match:
                    metrics.battery_temperature = int(temp_match.group(1)) / 10.0

                status_match = re.search(r"status:\s*(\d+)", output)
                if status_match:
                    metrics.is_charging = int(status_match.group(1)) == 2

            output, _, _ = self.daemon.adb_shell(
                "cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo ''"
            )
            if output and output.strip().isdigit():
                metrics.cpu_temperature = int(output.strip()) / 1000.0

            metrics.gpu_usage = 0.0
            metrics.gpu_name = "Unknown"

            metrics.timestamp = time.time()
            return metrics

        except Exception as e:
            logger.debug(f"采集 Android 指标异常: {e}")
            return None

    def _collect_pc_metrics(self) -> Optional[DeviceMetrics]:
        """采集 PC 设备指标（Windows/Linux）"""
        try:
            import platform
            system = platform.system().lower()

            metrics = DeviceMetrics(
                device_type=DeviceType.WINDOWS if system == "windows" else DeviceType.LINUX,
                device_id="pc",
                device_name=f"PC ({platform.node()})"
            )

            try:
                import psutil

                metrics.cpu_usage = psutil.cpu_percent(interval=0.5)

                mem = psutil.virtual_memory()
                metrics.memory_total = mem.total / (1024 ** 3)
                metrics.memory_available = mem.available / (1024 ** 3)
                metrics.memory_usage = mem.percent

                metrics.cpu_temperature = 0.0
                try:
                    if hasattr(psutil, "sensors_temperatures"):
                        temps = psutil.sensors_temperatures()
                        if temps:
                            for name, entries in temps.items():
                                for entry in entries:
                                    if entry.current:
                                        metrics.cpu_temperature = entry.current
                                        break
                except Exception:
                    pass

            except ImportError:
                logger.debug("psutil 未安装，无法采集 PC 指标")
                return None

            try:
                result = subprocess.run(
                    ["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total,name,temperature.gpu",
                     "--format=csv,noheader,nounits"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode == 0 and result.stdout.strip():
                    parts = result.stdout.strip().split(",")
                    if len(parts) >= 5:
                        metrics.gpu_usage = float(parts[0].strip())
                        metrics.gpu_memory_usage = float(parts[1].strip()) / 1024
                        metrics.gpu_total_memory = float(parts[2].strip()) / 1024
                        metrics.gpu_name = parts[3].strip()
                        metrics.gpu_temperature = float(parts[4].strip())
            except (subprocess.TimeoutExpired, FileNotFoundError, ValueError) as e:
                logger.debug(f"nvidia-smi 查询失败: {e}")
                metrics.gpu_usage = 0.0
                metrics.gpu_name = "No NVIDIA GPU"

            metrics.battery_level = 100
            metrics.battery_temperature = 25.0
            metrics.is_charging = True

            metrics.timestamp = time.time()
            return metrics

        except Exception as e:
            logger.debug(f"采集 PC 指标异常: {e}")
            return None

    @property
    def compute_score(self) -> float:
        """计算当前活跃设备的综合评分（0-100）"""
        if not self.device_metrics:
            return 0.0

        scores = []
        for device_id, metrics in self.device_metrics.items():
            score = self._calculate_device_score(metrics)
            scores.append((device_id, score))

        if not scores:
            return 0.0

        primary_device = max(scores, key=lambda x: x[1])
        return primary_device[1]

    def _calculate_device_score(self, metrics: DeviceMetrics) -> float:
        """计算单个设备的综合评分"""
        cpu_score = max(0, 100 - metrics.cpu_usage)

        memory_score = max(0, 100 - metrics.memory_usage)

        gpu_score = max(0, 100 - metrics.gpu_usage) if metrics.gpu_name != "Unknown" else 50

        if metrics.device_type == DeviceType.ANDROID:
            temp_score = 100
            if metrics.battery_temperature > 40:
                temp_score = max(0, 100 - (metrics.battery_temperature - 40) * 5)
            elif metrics.cpu_temperature > 45:
                temp_score = max(0, 100 - (metrics.cpu_temperature - 45) * 5)

            battery_score = metrics.battery_level
            if not metrics.is_charging:
                if metrics.battery_level < 20:
                    battery_score *= 0.5
                elif metrics.battery_level < 50:
                    battery_score *= 0.8
        else:
            temp_score = 100
            if metrics.cpu_temperature > 80:
                temp_score = max(0, 100 - (metrics.cpu_temperature - 80) * 2)
            elif metrics.gpu_temperature > 80:
                temp_score = max(0, 100 - (metrics.gpu_temperature - 80) * 2)

            battery_score = 100

        total_score = (
            cpu_score * self.WEIGHT_CPU +
            memory_score * self.WEIGHT_MEMORY +
            gpu_score * self.WEIGHT_GPU +
            temp_score * self.WEIGHT_TEMPERATURE +
            battery_score * self.WEIGHT_BATTERY
        )

        return min(100.0, max(0.0, total_score))

    def _select_best_device(self, requirements: AppInfo) -> Optional[str]:
        """根据需求选择最佳设备"""
        if not self.device_metrics:
            return None

        candidates = []

        for device_id, metrics in self.device_metrics.items():
            if metrics.device_type == DeviceType.ANDROID and not requirements.package_name:
                continue
            if metrics.device_type in (DeviceType.WINDOWS, DeviceType.LINUX):
                if requirements.package_name and requirements.app_id not in ["wechat", "qq", "douyin", "bilibili", "chrome"]:
                    continue

            available_memory = (
                (metrics.memory_total - metrics.memory_available)
                if metrics.device_type == DeviceType.ANDROID
                else metrics.memory_available * 1024
            )
            if available_memory < requirements.memory_requirement:
                continue

            score = self._calculate_device_score(metrics)
            candidates.append((device_id, score, metrics))

        if not candidates:
            return None

        candidates.sort(key=lambda x: x[1], reverse=True)
        return candidates[0][0]

    def _check_load_balance(self):
        """检查是否需要负载均衡"""
        if len(self.device_metrics) < 2:
            return

        devices = list(self.device_metrics.items())
        for i, (dev_id, metrics) in enumerate(devices):
            score = self._calculate_device_score(metrics)

            if score < self.SCORE_THRESHOLD_LOW:
                logger.info(f"设备 {dev_id} 评分过低 ({score:.1f})，尝试迁移应用")
                self._try_migrate_from_device(dev_id, score)

            for j, (other_id, other_metrics) in enumerate(devices):
                if i == j:
                    continue

                other_score = self._calculate_device_score(other_metrics)
                score_diff = other_score - score

                if score_diff > self.SCORE_THRESHOLD_DIFF:
                    for app_id, app in self.registered_apps.items():
                        if app.current_device == dev_id and app.priority < 5:
                            logger.info(
                                f"触发负载均衡: {app_id} 从 {dev_id}({score:.1f}) "
                                f"迁移到 {other_id}({other_score:.1f})"
                            )
                            self._perform_migration(app_id, dev_id, other_id)

    def _try_migrate_from_device(self, source_device: str, source_score: float):
        """尝试从低分设备迁移应用到其他设备"""
        candidates = []

        for other_id, other_metrics in self.device_metrics.items():
            if other_id == source_device:
                continue

            other_score = self._calculate_device_score(other_metrics)
            if other_score > source_score + 10:
                candidates.append((other_id, other_score))

        if not candidates:
            return

        candidates.sort(key=lambda x: x[1], reverse=True)
        best_target = candidates[0][0]

        for app_id, app in self.registered_apps.items():
            if app.current_device == source_device and app.state == AppState.RUNNING:
                logger.info(f"从低分设备 {source_device} 迁移 {app_id} 到 {best_target}")
                self._perform_migration(app_id, source_device, best_target)
                break

    def register_app(self, app_id: str, device: str) -> bool:
        """注册应用"""
        with self.lock:
            if app_id not in self.known_apps:
                logger.warning(f"未知应用: {app_id}")
                return False

            if app_id in self.registered_apps:
                logger.warning(f"应用已注册: {app_id}")
                return False

            if device not in self.device_metrics:
                logger.warning(f"设备不存在: {device}")
                return False

            app = self.known_apps[app_id]
            app.current_device = device
            app.state = AppState.RUNNING
            self.registered_apps[app_id] = app

            logger.info(f"应用已注册: {app_id} on {device}")
            return True

    def unregister_app(self, app_id: str):
        """注销应用"""
        with self.lock:
            if app_id in self.registered_apps:
                app = self.registered_apps.pop(app_id)
                app.state = AppState.STOPPED
                app.current_device = ""
                logger.info(f"应用已注销: {app_id}")

    def _migrate_android_to_pc(self, app_id: str, source: str, target: str) -> bool:
        """Android→PC 迁移"""
        logger.info(f"执行 Android→PC 迁移: {app_id} ({source} -> {target})")

        try:
            app = self.registered_apps.get(app_id)
            if not app:
                return False

            app.state = AppState.MIGRATING

            if self.daemon:
                self.daemon.adb_shell(f"am force-stop {app.package_name}", capture=False)

            time.sleep(1)

            app.current_device = target
            app.state = AppState.RUNNING

            logger.info(f"Android→PC 迁移完成: {app_id}")
            return True

        except Exception as e:
            logger.error(f"Android→PC 迁移失败: {e}")
            if app:
                app.state = AppState.RUNNING
            return False

    def _migrate_pc_to_android(self, app_id: str, source: str, target: str) -> bool:
        """PC→Android 迁移"""
        logger.info(f"执行 PC→Android 迁移: {app_id} ({source} -> {target})")

        try:
            app = self.registered_apps.get(app_id)
            if not app:
                return False

            app.state = AppState.MIGRATING

            time.sleep(1)

            if self.daemon and app.package_name:
                self.daemon.adb_shell(
                    f"monkey -p {app.package_name} -c android.intent.category.LAUNCHER 1",
                    capture=False
                )

            app.current_device = target
            app.state = AppState.RUNNING

            logger.info(f"PC→Android 迁移完成: {app_id}")
            return True

        except Exception as e:
            logger.error(f"PC→Android 迁移失败: {e}")
            if app:
                app.state = AppState.RUNNING
            return False

    def _perform_migration(self, app_id: str, source: str, target: str):
        """执行迁移操作"""
        source_metrics = self.device_metrics.get(source)
        target_metrics = self.device_metrics.get(target)

        if not source_metrics or not target_metrics:
            return

        if (source_metrics.device_type == DeviceType.ANDROID and
            target_metrics.device_type in (DeviceType.WINDOWS, DeviceType.LINUX)):
            self._migrate_android_to_pc(app_id, source, target)
        elif (source_metrics.device_type in (DeviceType.WINDOWS, DeviceType.LINUX) and
              target_metrics.device_type == DeviceType.ANDROID):
            self._migrate_pc_to_android(app_id, source, target)
        else:
            logger.debug(f"相同类型设备间迁移，跳过: {source} -> {target}")

    def get_status(self) -> Dict:
        """获取调度器状态"""
        with self.lock:
            device_scores = {}
            for device_id, metrics in self.device_metrics.items():
                device_scores[device_id] = {
                    "score": round(self._calculate_device_score(metrics), 2),
                    "cpu_usage": metrics.cpu_usage,
                    "memory_usage": metrics.memory_usage,
                    "gpu_usage": metrics.gpu_usage,
                    "battery_level": metrics.battery_level,
                    "temperature": metrics.battery_temperature if metrics.device_type == DeviceType.ANDROID else metrics.cpu_temperature,
                    "is_charging": metrics.is_charging,
                    "device_type": metrics.device_type.value,
                    "device_name": metrics.device_name,
                    "gpu_name": metrics.gpu_name,
                }

            app_status = {}
            for app_id, app in self.registered_apps.items():
                app_status[app_id] = {
                    "name": app.name,
                    "state": app.state.value,
                    "current_device": app.current_device,
                    "priority": app.priority,
                    "memory_requirement": app.memory_requirement,
                    "gpu_requirement": app.gpu_requirement,
                }

            return {
                "monitoring": self.monitoring,
                "compute_score": round(self.compute_score, 2) if hasattr(self, 'compute_score') else 0.0,
                "devices": device_scores,
                "registered_apps": app_status,
                "known_apps_count": len(self.known_apps),
            }

    def get_device_metrics(self, device_id: str) -> Optional[DeviceMetrics]:
        """获取指定设备指标"""
        return self.device_metrics.get(device_id)

    def get_all_device_scores(self) -> Dict[str, float]:
        """获取所有设备评分"""
        scores = {}
        for device_id, metrics in self.device_metrics.items():
            scores[device_id] = round(self._calculate_device_score(metrics), 2)
        return scores

    # ═══════════════════════════════════════════════════════
    # 任务分发与结果回收 (MQTT / WebSocket)
    # ═══════════════════════════════════════════════════════

    def dispatch_task(self, task_type: str, task_data: dict, timeout: int = 60) -> Optional[str]:
        """
        分发计算任务到最佳设备

        Args:
            task_type: 任务类型 (e.g., "ai_inference", "sensor_batch", "encoding")
            task_data: 任务数据 (会通过 MQTT 发送到目标设备)
            timeout: 超时时间 (秒)

        Returns:
            task_id 或 None (无可用设备)
        """
        import uuid

        task_id = str(uuid.uuid4())[:12]
        task = {
            "task_id": task_id,
            "task_type": task_type,
            "data": task_data,
            "timestamp": int(time.time() * 1000),
            "timeout": timeout,
        }

        # 选择最佳设备
        best_device = self._select_best_device_for_task(task_type, task_data)
        if not best_device:
            logger.warning(f"[调度器] 无可用设备执行任务: {task_type}")
            return None

        # 通过 MQTT 发送任务
        topic = f"devices/{best_device}"
        if self.daemon and hasattr(self.daemon, 'mqtt_bridge'):
            self.daemon.mqtt_bridge.publish_json(topic, {
                "command": "compute_task",
                "params": task,
            }, qos=1)
            logger.info(f"[调度器] 任务已分发: {task_id} ({task_type}) -> {best_device}")
        elif self.daemon and hasattr(self.daemon, 'ws_client') and self.daemon.use_companion:
            # WebSocket 备用通道
            self.daemon.ws_client.send({
                "type": "compute_task",
                "task_id": task_id,
                "task_type": task_type,
                "data": task_data,
            })
            logger.info(f"[调度器] 任务已分发 (WS): {task_id} -> {best_device}")
        else:
            logger.warning(f"[调度器] 无通信通道，任务无法分发: {task_id}")
            return None

        # 记录待回收任务
        if not hasattr(self, '_pending_tasks'):
            self._pending_tasks = {}
        self._pending_tasks[task_id] = {
            "device": best_device,
            "task_type": task_type,
            "sent_at": time.time(),
            "timeout": timeout,
            "result": None,
            "status": "sent",
        }

        return task_id

    def collect_result(self, task_id: str, result: dict):
        """
        回收任务结果 (由 MQTT 回调或 WebSocket 回调调用)

        Args:
            task_id: 任务 ID
            result: 结果数据
        """
        if not hasattr(self, '_pending_tasks'):
            return

        task = self._pending_tasks.get(task_id)
        if task:
            task["result"] = result
            task["status"] = "completed"
            logger.info(f"[调度器] 任务完成: {task_id} ({task['task_type']}) from {task['device']}")

    def handle_task_timeout(self):
        """检查并清理超时任务"""
        if not hasattr(self, '_pending_tasks'):
            return

        now = time.time()
        timed_out = []
        for task_id, task in self._pending_tasks.items():
            if task["status"] == "sent" and now - task["sent_at"] > task["timeout"]:
                task["status"] = "timeout"
                timed_out.append(task_id)
                logger.warning(f"[调度器] 任务超时: {task_id} ({task['task_type']})")

        for tid in timed_out:
            del self._pending_tasks[tid]

    def _select_best_device_for_task(self, task_type: str, task_data: dict) -> Optional[str]:
        """根据任务类型选择最佳执行设备"""
        if not self.device_metrics:
            return None

        candidates = []

        for device_id, metrics in self.device_metrics.items():
            score = self._calculate_device_score(metrics)

            # AI 推理任务: 优先选择空闲设备 (低 CPU、高内存)
            if task_type == "ai_inference":
                if metrics.memory_available < 512:  # 需要至少 512MB 可用内存
                    continue
                score *= (1 + (100 - metrics.cpu_usage) / 200)  # CPU 越空闲权重越高

            # 编码任务: 需要 GPU
            elif task_type == "encoding":
                if metrics.gpu_name in ("No NVIDIA GPU", "Unknown", ""):
                    score *= 0.3  # 无 GPU 降权
                else:
                    score *= (1 + (100 - metrics.gpu_usage) / 200)

            # 传感器批量处理: 优先手机 (数据在手机本地)
            elif task_type == "sensor_batch":
                if metrics.device_type == DeviceType.ANDROID:
                    score *= 1.5

            # 手机低电量时降低优先级
            if metrics.device_type == DeviceType.ANDROID:
                if metrics.battery_level < 20 and not metrics.is_charging:
                    score *= 0.2
                elif metrics.battery_level < 50 and not metrics.is_charging:
                    score *= 0.5

            candidates.append((device_id, score))

        if not candidates:
            return None

        candidates.sort(key=lambda x: x[1], reverse=True)
        return candidates[0][0]

    def get_pending_tasks(self) -> Dict:
        """获取待处理任务列表"""
        if not hasattr(self, '_pending_tasks'):
            return {}
        return {tid: task for tid, task in self._pending_tasks.items()}
