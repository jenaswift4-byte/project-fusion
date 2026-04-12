"""
传感器健康监控模块
通过 ADB dumpsys sensorservice 检测传感器状态、噪声等级、漂移等健康指标

手机作为环境感知设备:
  - 检测传感器可用性和健康状态
  - 计算传感器噪声等级和漂移
  - 生成传感器综合健康报告
  - 支持的传感器: light, proximity, accelerometer, gyroscope, magnetic, noise, steps
"""

import time
import statistics
import logging
from typing import Dict, List, Optional, Any

logger = logging.getLogger(__name__)


class SensorHealthMonitor:
    """传感器健康监控"""

    SUPPORTED_SENSORS = [
        "light",
        "proximity",
        "accelerometer",
        "gyroscope",
        "magnetic",
        "noise",
        "steps"
    ]

    def __init__(self, daemon):
        self.daemon = daemon
        self._sample_cache: Dict[str, List[float]] = {}

    def _check_sensor_available(self, sensor_name: str) -> bool:
        """检测传感器是否可用

        Args:
            sensor_name: 传感器名称

        Returns:
            传感器是否可用
        """
        output, _, rc = self.daemon.adb_shell("dumpsys sensorservice")
        if rc != 0 or not output:
            logger.warning(f"[传感器健康] 无法获取传感器服务状态: {sensor_name}")
            return False

        sensor_map = {
            "light": "light",
            "proximity": "proximity",
            "accelerometer": "accelerometer",
            "gyroscope": "gyroscope",
            "magnetic": "magnetic",
            "noise": "sound",
            "steps": "step"
        }

        search_name = sensor_map.get(sensor_name, sensor_name)

        for line in output.split("\n"):
            line_lower = line.lower()
            if search_name in line_lower and "enabled" in line_lower:
                return True
            if search_name in line_lower and "active" in line_lower:
                return True

        sensor_list, _, list_rc = self.daemon.adb_shell(
            f"dumpsys sensorservice | grep -i {search_name}"
        )
        if list_rc == 0 and sensor_list:
            return True

        return False

    def _collect_sensor_samples(self, sensor_name: str, count: int = 50) -> List[float]:
        """收集传感器样本数据

        Args:
            sensor_name: 传感器名称
            count: 采样数量

        Returns:
            传感器样本值列表
        """
        samples = []

        sensor_cmd_map = {
            "light": "cat /sys/class/sensors/light_sensor/lux",
            "proximity": "cat /sys/class/sensors/proximity_sensor/proximity",
            "accelerometer": "cat /sys/class/sensors/accelerometer_0/value",
            "gyroscope": "cat /sys/class/sensors/gyroscope_0/value",
            "magnetic": "cat /sys/class/sensors/magnetic_sensor/value",
            "noise": "media.volume --get",
            "steps": "dumpsys stepcounter"
        }

        cmd = sensor_cmd_map.get(sensor_name)
        if not cmd:
            logger.warning(f"[传感器健康] 未知传感器类型: {sensor_name}")
            return samples

        for _ in range(count):
            try:
                output, _, rc = self.daemon.adb_shell(cmd)
                if rc == 0 and output:
                    value_str = output.strip().split()[-1]
                    try:
                        value = float(value_str)
                        samples.append(value)
                    except ValueError:
                        pass
            except Exception as e:
                logger.debug(f"[传感器健康] 采样失败 ({sensor_name}): {e}")

            time.sleep(0.05)

        if len(samples) < 10:
            alt_samples = self._collect_sensor_samples_alternative(sensor_name, count)
            if alt_samples:
                samples.extend(alt_samples)
                samples = samples[:count]

        self._sample_cache[sensor_name] = samples
        return samples

    def _collect_sensor_samples_alternative(self, sensor_name: str, count: int) -> List[float]:
        """使用备选方案收集传感器样本

        Args:
            sensor_name: 传感器名称
            count: 采样数量

        Returns:
            备选的传感器样本值列表
        """
        samples = []

        alt_cmd_map = {
            "light": "getevent -type 2 -lt /dev/input/event5 2>/dev/null | head -5",
            "proximity": "getevent -type 2 -lt /dev/input/event6 2>/dev/null | head -5",
        }

        cmd = alt_cmd_map.get(sensor_name)
        if not cmd:
            return samples

        try:
            output, _, rc = self.daemon.adb_shell(cmd)
            if rc == 0 and output:
                for line in output.split("\n"):
                    parts = line.strip().split()
                    if parts:
                        try:
                            value = int(parts[-1], 16)
                            samples.append(float(value))
                        except (ValueError, IndexError):
                            pass
        except Exception:
            pass

        return samples

    def _calculate_noise_level(self, samples: List[float]) -> float:
        """使用标准差计算噪声等级

        Args:
            samples: 传感器样本列表

        Returns:
            噪声等级 (标准差值)
        """
        if len(samples) < 2:
            return 0.0

        try:
            std_dev = statistics.stdev(samples)
            return round(std_dev, 4)
        except statistics.StatisticsError:
            return 0.0

    def _calculate_drift(self, samples: List[float]) -> float:
        """计算漂移 (前后样本均值差异)

        Args:
            samples: 传感器样本列表

        Returns:
            漂移值 (前后均值差异的绝对值)
        """
        if len(samples) < 10:
            return 0.0

        half = len(samples) // 2
        first_half_mean = statistics.mean(samples[:half])
        second_half_mean = statistics.mean(samples[half:])

        drift = abs(second_half_mean - first_half_mean)
        return round(drift, 4)

    def _diagnose_sensor(self, sensor_name: str) -> Dict[str, Any]:
        """诊断单个传感器的健康状态

        Args:
            sensor_name: 传感器名称

        Returns:
            传感器诊断结果字典
        """
        result = {
            "sensor": sensor_name,
            "available": False,
            "noise_level": 0.0,
            "drift": 0.0,
            "status": "unknown",
            "suggestions": []
        }

        is_available = self._check_sensor_available(sensor_name)
        result["available"] = is_available

        if not is_available:
            result["status"] = "unavailable"
            result["suggestions"].append("传感器不可用，可能被禁用或硬件故障")
            return result

        samples = self._collect_sensor_samples(sensor_name, count=50)

        if len(samples) < 10:
            result["status"] = "insufficient_data"
            result["suggestions"].append("采样数据不足，尝试重新检测或检查传感器驱动")
            return result

        noise_level = self._calculate_noise_level(samples)
        drift = self._calculate_drift(samples)

        result["noise_level"] = noise_level
        result["drift"] = drift
        result["sample_count"] = len(samples)

        status = "good"
        suggestions = []

        noise_thresholds = {
            "light": 50.0,
            "proximity": 1.0,
            "accelerometer": 2.0,
            "gyroscope": 0.5,
            "magnetic": 30.0,
            "noise": 20.0,
            "steps": 5.0
        }

        threshold = noise_thresholds.get(sensor_name, 10.0)
        if noise_level > threshold:
            status = "warning"
            suggestions.append(f"噪声等级较高 ({noise_level:.2f})，可能存在干扰")

        drift_thresholds = {
            "light": 20.0,
            "proximity": 0.5,
            "accelerometer": 1.0,
            "gyroscope": 0.3,
            "magnetic": 15.0,
            "noise": 10.0,
            "steps": 3.0
        }

        drift_threshold = drift_thresholds.get(sensor_name, 5.0)
        if drift > drift_threshold:
            status = "drift_detected" if status == "good" else status
            suggestions.append(f"检测到漂移 ({drift:.2f})，传感器可能需要校准")

        if status == "good":
            suggestions.append("传感器工作正常")

        result["status"] = status
        result["suggestions"] = suggestions

        return result

    def _generate_health_report(self, results: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
        """生成综合健康报告

        Args:
            results: 各传感器诊断结果字典

        Returns:
            综合健康报告
        """
        report = {
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "overall_status": "unknown",
            "sensor_count": len(results),
            "available_count": 0,
            "warning_count": 0,
            "error_count": 0,
            "sensors": results,
            "summary": "",
            "recommendations": []
        }

        for sensor_result in results.values():
            if sensor_result.get("available"):
                report["available_count"] += 1

            status = sensor_result.get("status", "unknown")
            if status in ("warning", "drift_detected"):
                report["warning_count"] += 1
            elif status == "unavailable" or status == "error":
                report["error_count"] += 1

        if report["error_count"] > 0:
            report["overall_status"] = "critical"
            report["summary"] = f"检测到 {report['error_count']} 个传感器异常，建议检查硬件"
            report["recommendations"].append("存在不可用传感器，请检查系统设置中是否启用")
            report["recommendations"].append("如持续异常，可能需要校准或维修")
        elif report["warning_count"] > 0:
            report["overall_status"] = "warning"
            report["summary"] = f"检测到 {report['warning_count']} 个传感器存在轻微问题"
            report["recommendations"].append("部分传感器噪声或漂移偏大，建议进行校准")
        else:
            report["overall_status"] = "healthy"
            report["summary"] = "所有传感器工作正常"
            report["recommendations"].append("传感器健康状态良好，无需特殊处理")

        return report

    def run_full_diagnostics(self) -> Dict[str, Any]:
        """运行完整传感器诊断

        Returns:
            综合健康报告字典
        """
        logger.info("[传感器健康] 开始完整诊断...")

        results = {}

        for sensor_name in self.SUPPORTED_SENSORS:
            try:
                logger.debug(f"[传感器健康] 诊断中: {sensor_name}")
                result = self._diagnose_sensor(sensor_name)
                results[sensor_name] = result

                status_icon = {
                    "good": "✅",
                    "warning": "⚠️",
                    "drift_detected": "📊",
                    "unavailable": "❌",
                    "insufficient_data": "❓",
                    "unknown": "❓"
                }.get(result.get("status", "unknown"), "❓")

                logger.info(
                    f"[传感器健康] {sensor_name}: {status_icon} "
                    f"噪声={result.get('noise_level', 0):.4f} "
                    f"漂移={result.get('drift', 0):.4f}"
                )
            except Exception as e:
                logger.error(f"[传感器健康] 诊断失败 ({sensor_name}): {e}")
                results[sensor_name] = {
                    "sensor": sensor_name,
                    "available": False,
                    "noise_level": 0.0,
                    "drift": 0.0,
                    "status": "error",
                    "suggestions": [f"诊断过程出错: {str(e)}"]
                }

        report = self._generate_health_report(results)

        logger.info(
            f"[传感器健康] 诊断完成: "
            f"总数={report['sensor_count']} "
            f"可用={report['available_count']} "
            f"警告={report['warning_count']} "
            f"异常={report['error_count']} "
            f"整体状态={report['overall_status']}"
        )

        return report

    def get_sensor_status(self, sensor_name: str) -> Optional[Dict[str, Any]]:
        """获取单个传感器的健康状态

        Args:
            sensor_name: 传感器名称

        Returns:
            传感器诊断结果，如果传感器不支持则返回 None
        """
        if sensor_name not in self.SUPPORTED_SENSORS:
            logger.warning(f"[传感器健康] 不支持的传感器: {sensor_name}")
            return None

        return self._diagnose_sensor(sensor_name)
