"""
AI 推理引擎模块
统一的 AI 推理接口，支持多种后端和模型

支持的后端:
  - LLAMA_CPP: llama.cpp 本地推理 (Android 端 / PC 端)
  - OLLAMA: Ollama API 推理
  - OPENAI_API: OpenAI 兼容 API 推理
  - MLC_LLM: MLC LLM 推理

用法:
    engine = AIInferenceEngine(bridge_daemon)
    result = engine.infer("你好，请介绍一下自己")
"""

import logging
import subprocess
import json
import os
import re
from enum import Enum
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any

try:
    import aiohttp
    HAS_AIOHTTP = True
except ImportError:
    HAS_AIOHTTP = False

logger = logging.getLogger(__name__)


class InferenceBackend(Enum):
    """支持的 AI 推理后端"""
    LLAMA_CPP = "llama_cpp"
    OLLAMA = "ollama"
    OPENAI_API = "openai_api"
    MLC_LLM = "mlc_llm"


class TaskType(Enum):
    """任务类型"""
    CHAT = "chat"
    CODE = "code"
    REASONING = "reasoning"
    GENERAL = "general"


@dataclass
class ModelInfo:
    """模型信息"""
    name: str
    path: Optional[str] = None
    backend: InferenceBackend = InferenceBackend.LLAMA_CPP
    max_context: int = 4096
    capabilities: List[str] = field(default_factory=list)
    quantization: str = "q4_0"
    is_mobile_optimized: bool = False
    parameters_b: float = 0.0


AVAILABLE_MODELS: Dict[str, ModelInfo] = {
    "qwen2-1.5b-chat-q4": ModelInfo(
        name="Qwen2-1.5B-Instruct-Q4",
        path=None,
        backend=InferenceBackend.LLAMA_CPP,
        max_context=8192,
        capabilities=["chat", "general", "multilingual"],
        quantization="q4_0",
        is_mobile_optimized=True,
        parameters_b=1.5,
    ),
    "qwen2.5-7b": ModelInfo(
        name="Qwen2.5-7B-Instruct",
        path=None,
        backend=InferenceBackend.LLAMA_CPP,
        max_context=8192,
        capabilities=["chat", "code", "reasoning", "general", "multilingual"],
        quantization="q4_0",
        is_mobile_optimized=False,
        parameters_b=7.0,
    ),
    "phi-2-q4": ModelInfo(
        name="phi-2-Q4",
        path=None,
        backend=InferenceBackend.LLAMA_CPP,
        max_context=2048,
        capabilities=["chat", "general"],
        quantization="q4_0",
        is_mobile_optimized=True,
        parameters_b=2.7,
    ),
    "llama-3-8b": ModelInfo(
        name="Llama-3-8B-Instruct",
        path=None,
        backend=InferenceBackend.OLLAMA,
        max_context=8192,
        capabilities=["chat", "code", "reasoning", "general"],
        quantization="q4_0",
        is_mobile_optimized=False,
        parameters_b=8.0,
    ),
    "qwen2.5-coder-7b": ModelInfo(
        name="Qwen2.5-Coder-7B-Instruct",
        path=None,
        backend=InferenceBackend.OLLAMA,
        max_context=8192,
        capabilities=["code", "chat"],
        quantization="q4_0",
        is_mobile_optimized=False,
        parameters_b=7.0,
    ),
}


class AIInferenceEngine:
    """统一 AI 推理引擎"""

    def __init__(self, bridge_daemon):
        self.bridge = bridge_daemon
        self.config = bridge_daemon.config.get("ai_inference", {})

        self.ollama_host = self.config.get("ollama_host", "http://localhost:11434")
        self.openai_host = self.config.get("openai_host", "https://api.openai.com/v1")
        self.openai_api_key = self.config.get("openai_api_key", os.getenv("OPENAI_API_KEY", ""))

        self.mobile_llama_path = self.config.get("mobile_llama_path", "/data/local/tmp/llama")
        self.mobile_model_path = self.config.get("mobile_model_path", "/sdcard/Models")

        self.pc_llama_path = self.config.get("pc_llama_path", "llama-cli.exe")
        self.pc_model_dir = self.config.get("pc_model_dir", r"D:\Fusion\Models")

        self.default_model = self.config.get("default_model", "qwen2-1.5b-chat-q4")

        self._device_capabilities: Dict[str, Any] = {}
        self._sensor_status: Dict[str, bool] = {}
        self._initialized = False

    def initialize(self):
        """初始化推理引擎，检测设备和传感器"""
        if self._initialized:
            return

        logger.info("初始化 AI 推理引擎...")
        self._detect_capabilities()
        self._detect_sensors()
        self._initialized = True
        logger.info(f"推理引擎初始化完成，移动优化: {self._device_capabilities.get('is_mobile', False)}")

    def _detect_capabilities(self) -> Dict[str, Any]:
        """检测设备能力和传感器状态"""
        self._device_capabilities = {
            "is_mobile": False,
            "has_gpu": False,
            "memory_gb": 0,
            "cpu_cores": 0,
        }

        if self.bridge.device_serial:
            self._detect_android_capabilities()
        else:
            self._detect_pc_capabilities()

        return self._device_capabilities

    def _detect_android_capabilities(self):
        """检测 Android 设备能力"""
        try:
            output, _, _ = self.bridge.adb_shell("getprop ro.product.device")
            device_name = output.strip() if output else "unknown"
            self._device_capabilities["device_name"] = device_name
            self._device_capabilities["is_mobile"] = True

            output, _, _ = self.bridge.adb_shell("getprop ro.hardware")
            hardware = output.strip() if output else "unknown"
            self._device_capabilities["hardware"] = hardware

            mem_info, _, _ = self.bridge.adb_shell("cat /proc/meminfo | grep MemTotal")
            if mem_info:
                mem_match = re.search(r'MemTotal:\s*(\d+)\s*kB', mem_info)
                if mem_match:
                    self._device_capabilities["memory_gb"] = int(mem_match.group(1)) / 1024 / 1024

            cpu_cores, _, _ = self.bridge.adb_shell("cat /proc/cpuinfo | grep 'processor' | wc -l")
            if cpu_cores:
                try:
                    self._device_capabilities["cpu_cores"] = int(cpu_cores.strip())
                except ValueError:
                    pass

            gpu_output, _, _ = self.bridge.adb_shell("dumpsys SurfaceFlinger | grep GPU")
            self._device_capabilities["has_gpu"] = "GPU" in gpu_output if gpu_output else False

            logger.info(f"Android 设备: {device_name}, 内存: {self._device_capabilities['memory_gb']:.1f}GB, "
                        f"核心数: {self._device_capabilities['cpu_cores']}")

        except Exception as e:
            logger.warning(f"检测 Android 设备能力失败: {e}")

    def _detect_pc_capabilities(self):
        """检测 PC 端能力"""
        try:
            import platform
            self._device_capabilities["os"] = platform.system()
            self._device_capabilities["is_mobile"] = False

            if platform.system() == "Windows":
                import shutil
                mem = shutil.disk_usage("/")
                self._device_capabilities["memory_gb"] = mem.total / (1024 ** 3)

                cpu_cores = os.cpu_count() or 4
                self._device_capabilities["cpu_cores"] = cpu_cores

                try:
                    import psutil
                    self._device_capabilities["has_gpu"] = psutil.cuda.is_available() if hasattr(psutil, 'cuda') else False
                except Exception:
                    self._device_capabilities["has_gpu"] = False

            logger.info(f"PC 设备: {self._device_capabilities.get('os', 'unknown')}, "
                        f"内存: {self._device_capabilities.get('memory_gb', 0):.1f}GB, "
                        f"核心数: {self._device_capabilities.get('cpu_cores', 0)}")

        except Exception as e:
            logger.warning(f"检测 PC 能力失败: {e}")

    def _detect_sensors(self) -> Dict[str, bool]:
        """检测手机传感器可用性和健康状态"""
        self._sensor_status = {
            "accelerometer": False,
            "gyroscope": False,
            "proximity": False,
            "light": False,
            "battery_present": False,
        }

        if not self._device_capabilities.get("is_mobile", False):
            return self._sensor_status

        try:
            sensor_list, _, _ = self.bridge.adb_shell("sensor list")
            if sensor_list:
                sensor_text = sensor_list.lower()
                self._sensor_status["accelerometer"] = "accelerometer" in sensor_text or "accel" in sensor_text
                self._sensor_status["gyroscope"] = "gyroscope" in sensor_text or "gyro" in sensor_text
                self._sensor_status["proximity"] = "proximity" in sensor_text
                self._sensor_status["light"] = "light" in sensor_text or "lux" in sensor_text

            battery_status, _, _ = self.bridge.adb_shell("dumpsys battery")
            if battery_status:
                self._sensor_status["battery_present"] = "level:" in battery_status.lower()

            logger.debug(f"传感器状态: {self._sensor_status}")

        except Exception as e:
            logger.warning(f"检测传感器失败: {e}")

        return self._sensor_status

    def _select_best_model(self, prompt: str, task_type: Optional[TaskType] = None) -> str:
        """根据任务类型选择最佳模型

        Args:
            prompt: 输入提示
            task_type: 任务类型，如果为 None 则自动检测

        Returns:
            模型名称
        """
        if task_type is None:
            task_type = self._classify_task(prompt)

        is_mobile = self._device_capabilities.get("is_mobile", False)
        memory_gb = self._device_capabilities.get("memory_gb", 0)

        if task_type == TaskType.CHAT:
            if is_mobile and memory_gb > 4:
                logger.info("任务类型: chat, 选择移动端 Q4 量化模型")
                return "qwen2-1.5b-chat-q4"
            else:
                logger.info("任务类型: chat, 选择 PC 端模型")
                return "qwen2.5-7b"

        elif task_type == TaskType.CODE:
            if not is_mobile:
                logger.info("任务类型: code, 选择 PC 端较大模型")
                return "qwen2.5-coder-7b"
            else:
                logger.info("任务类型: code, 回退到通用模型")
                return "qwen2.5-7b"

        elif task_type == TaskType.REASONING:
            if not is_mobile:
                logger.info("任务类型: reasoning, 选择 PC 端或云端模型")
                return "qwen2.5-7b"
            else:
                logger.info("任务类型: reasoning, 回退到手机端模型")
                return "qwen2-1.5b-chat-q4"

        else:
            logger.info("任务类型: general, 使用默认模型")
            return self.default_model

    def _classify_task(self, prompt: str) -> TaskType:
        """根据提示内容分类任务类型

        Args:
            prompt: 输入提示

        Returns:
            任务类型
        """
        prompt_lower = prompt.lower()

        code_keywords = ["code", "python", "javascript", "java", "cpp", "c++", "编程", "函数", "class", "def ", "import "]
        for keyword in code_keywords:
            if keyword in prompt_lower:
                return TaskType.CODE

        reasoning_keywords = ["why", "how", "reason", "explain", "think", "分析", "推理", "为什么", "如何思考"]
        for keyword in reasoning_keywords:
            if keyword in prompt_lower:
                return TaskType.REASONING

        chat_keywords = ["hello", "hi", "你好", "请问", "帮我", "我想", "介绍", "chat"]
        for keyword in chat_keywords:
            if keyword in prompt_lower:
                return TaskType.CHAT

        return TaskType.GENERAL

    def infer(
        self,
        prompt: str,
        model: Optional[str] = None,
        max_tokens: int = 512,
        temperature: float = 0.7,
        task_type: Optional[TaskType] = None,
    ) -> Dict[str, Any]:
        """统一推理接口

        Args:
            prompt: 输入提示
            model: 指定模型名称，如果为 None 则自动选择
            max_tokens: 最大生成 token 数
            temperature: 采样温度
            task_type: 任务类型

        Returns:
            推理结果字典，包含 text, model, backend, success 等字段
        """
        if not self._initialized:
            self.initialize()

        if model is None:
            model = self._select_best_model(prompt, task_type)

        model_info = AVAILABLE_MODELS.get(model)
        if model_info is None:
            logger.error(f"未知模型: {model}，使用默认模型")
            model = self.default_model
            model_info = AVAILABLE_MODELS.get(model, AVAILABLE_MODELS["qwen2-1.5b-chat-q4"])

        backend = model_info.backend
        logger.info(f"推理请求: 模型={model}, 后端={backend.value}, 最大Token={max_tokens}")

        try:
            if backend == InferenceBackend.LLAMA_CPP:
                if self._device_capabilities.get("is_mobile", False):
                    return self._infer_android_llama(prompt, model_info, max_tokens, temperature)
                else:
                    return self._infer_pc_llama(prompt, model_info, max_tokens, temperature)

            elif backend == InferenceBackend.OLLAMA:
                return self._infer_ollama(prompt, model, max_tokens, temperature)

            elif backend == InferenceBackend.OPENAI_API:
                return self._infer_openai(prompt, model, max_tokens, temperature)

            elif backend == InferenceBackend.MLC_LLM:
                return self._infer_mlc_llm(prompt, model_info, max_tokens, temperature)

            else:
                return {
                    "success": False,
                    "error": f"不支持的后端: {backend}",
                    "text": "",
                    "model": model,
                    "backend": backend.value,
                }

        except Exception as e:
            logger.error(f"推理失败: {e}")
            return {
                "success": False,
                "error": str(e),
                "text": "",
                "model": model,
                "backend": backend.value,
            }

    def _infer_android_llama(
        self,
        prompt: str,
        model_info: ModelInfo,
        max_tokens: int,
        temperature: float,
    ) -> Dict[str, Any]:
        """Android 端 llama.cpp 推理

        Args:
            prompt: 输入提示
            model_info: 模型信息
            max_tokens: 最大 token 数
            temperature: 采样温度

        Returns:
            推理结果
        """
        model_path = model_info.path or os.path.join(self.mobile_model_path, f"{model_info.name}.gguf")

        check_model_cmd = f"ls -la {model_path}"
        _, _, code = self.bridge.adb_shell(check_model_cmd)
        if code != 0:
            return {
                "success": False,
                "error": f"模型文件不存在: {model_path}",
                "text": "",
                "model": model_info.name,
                "backend": InferenceBackend.LLAMA_CPP.value,
            }

        escaped_prompt = prompt.replace("'", "'\"'\"'").replace("\n", "\\n")

        llama_cmd = (
            f"{self.mobile_llama_path} "
            f"-m {model_path} "
            f"-p '{escaped_prompt}' "
            f"-n {max_tokens} "
            f"-temp {temperature} "
            f"--log-disable"
        )

        logger.debug(f"执行命令: {llama_cmd}")
        output, stderr, code = self.bridge.adb_shell(llama_cmd)

        if code == 0 and output:
            return {
                "success": True,
                "text": output.strip(),
                "model": model_info.name,
                "backend": InferenceBackend.LLAMA_CPP.value,
            }
        else:
            return {
                "success": False,
                "error": stderr or "推理失败",
                "text": output.strip() if output else "",
                "model": model_info.name,
                "backend": InferenceBackend.LLAMA_CPP.value,
            }

    def _infer_pc_llama(
        self,
        prompt: str,
        model_info: ModelInfo,
        max_tokens: int,
        temperature: float,
    ) -> Dict[str, Any]:
        """PC 端 llama.cpp 推理

        Args:
            prompt: 输入提示
            model_info: 模型信息
            max_tokens: 最大 token 数
            temperature: 采样温度

        Returns:
            推理结果
        """
        model_path = model_info.path or os.path.join(self.pc_model_dir, f"{model_info.name}.gguf")

        if not os.path.exists(model_path):
            return {
                "success": False,
                "error": f"模型文件不存在: {model_path}",
                "text": "",
                "model": model_info.name,
                "backend": InferenceBackend.LLAMA_CPP.value,
            }

        try:
            cmd = [
                self.pc_llama_path,
                "-m", model_path,
                "-p", prompt,
                "-n", str(max_tokens),
                "--temp", str(temperature),
                "--log-disable",
            ]

            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=120,
            )

            if result.returncode == 0 and result.stdout:
                return {
                    "success": True,
                    "text": result.stdout.strip(),
                    "model": model_info.name,
                    "backend": InferenceBackend.LLAMA_CPP.value,
                }
            else:
                return {
                    "success": False,
                    "error": result.stderr or "推理失败",
                    "text": result.stdout.strip() if result.stdout else "",
                    "model": model_info.name,
                    "backend": InferenceBackend.LLAMA_CPP.value,
                }

        except subprocess.TimeoutExpired:
            return {
                "success": False,
                "error": "推理超时",
                "text": "",
                "model": model_info.name,
                "backend": InferenceBackend.LLAMA_CPP.value,
            }
        except FileNotFoundError:
            return {
                "success": False,
                "error": f"llama-cli 未找到: {self.pc_llama_path}",
                "text": "",
                "model": model_info.name,
                "backend": InferenceBackend.LLAMA_CPP.value,
            }
        except Exception as e:
            return {
                "success": False,
                "error": str(e),
                "text": "",
                "model": model_info.name,
                "backend": InferenceBackend.LLAMA_CPP.value,
            }

    def _infer_ollama(
        self,
        prompt: str,
        model: str,
        max_tokens: int,
        temperature: float,
    ) -> Dict[str, Any]:
        """Ollama API 推理

        Args:
            prompt: 输入提示
            model: 模型名称 (Ollama 中的模型名)
            max_tokens: 最大 token 数
            temperature: 采样温度

        Returns:
            推理结果
        """
        if not HAS_AIOHTTP:
            return {
                "success": False,
                "error": "aiohttp 未安装，请运行: pip install aiohttp",
                "text": "",
                "model": model,
                "backend": InferenceBackend.OLLAMA.value,
            }

        import asyncio

        async def _call_ollama():
            url = f"{self.ollama_host}/api/generate"
            payload = {
                "model": model,
                "prompt": prompt,
                "stream": False,
                "options": {
                    "num_predict": max_tokens,
                    "temperature": temperature,
                },
            }

            async with aiohttp.ClientSession() as session:
                async with session.post(url, json=payload, timeout=aiohttp.ClientTimeout(total=120)) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        return {
                            "success": True,
                            "text": data.get("response", "").strip(),
                            "model": model,
                            "backend": InferenceBackend.OLLAMA.value,
                        }
                    else:
                        error_text = await resp.text()
                        return {
                            "success": False,
                            "error": f"Ollama API 错误 ({resp.status}): {error_text}",
                            "text": "",
                            "model": model,
                            "backend": InferenceBackend.OLLAMA.value,
                        }

        try:
            return asyncio.run(_call_ollama())
        except Exception as e:
            return {
                "success": False,
                "error": f"Ollama 调用失败: {str(e)}",
                "text": "",
                "model": model,
                "backend": InferenceBackend.OLLAMA.value,
            }

    def _infer_openai(
        self,
        prompt: str,
        model: str,
        max_tokens: int,
        temperature: float,
    ) -> Dict[str, Any]:
        """OpenAI API 推理

        Args:
            prompt: 输入提示
            model: 模型名称 (如 gpt-3.5-turbo, gpt-4)
            max_tokens: 最大 token 数
            temperature: 采样温度

        Returns:
            推理结果
        """
        if not HAS_AIOHTTP:
            return {
                "success": False,
                "error": "aiohttp 未安装，请运行: pip install aiohttp",
                "text": "",
                "model": model,
                "backend": InferenceBackend.OPENAI_API.value,
            }

        if not self.openai_api_key:
            return {
                "success": False,
                "error": "OpenAI API Key 未配置",
                "text": "",
                "model": model,
                "backend": InferenceBackend.OPENAI_API.value,
            }

        import asyncio

        async def _call_openai():
            url = f"{self.openai_host}/chat/completions"
            headers = {
                "Authorization": f"Bearer {self.openai_api_key}",
                "Content-Type": "application/json",
            }
            payload = {
                "model": model,
                "messages": [{"role": "user", "content": prompt}],
                "max_tokens": max_tokens,
                "temperature": temperature,
            }

            async with aiohttp.ClientSession() as session:
                async with session.post(url, json=payload, headers=headers,
                                        timeout=aiohttp.ClientTimeout(total=120)) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        choices = data.get("choices", [])
                        if choices:
                            return {
                                "success": True,
                                "text": choices[0].get("message", {}).get("content", "").strip(),
                                "model": model,
                                "backend": InferenceBackend.OPENAI_API.value,
                            }
                        return {
                            "success": False,
                            "error": "OpenAI 返回格式错误",
                            "text": "",
                            "model": model,
                            "backend": InferenceBackend.OPENAI_API.value,
                        }
                    else:
                        error_text = await resp.text()
                        return {
                            "success": False,
                            "error": f"OpenAI API 错误 ({resp.status}): {error_text}",
                            "text": "",
                            "model": model,
                            "backend": InferenceBackend.OPENAI_API.value,
                        }

        try:
            return asyncio.run(_call_openai())
        except Exception as e:
            return {
                "success": False,
                "error": f"OpenAI API 调用失败: {str(e)}",
                "text": "",
                "model": model,
                "backend": InferenceBackend.OPENAI_API.value,
            }

    def _infer_mlc_llm(
        self,
        prompt: str,
        model_info: ModelInfo,
        max_tokens: int,
        temperature: float,
    ) -> Dict[str, Any]:
        """MLC LLM 推理

        Args:
            prompt: 输入提示
            model_info: 模型信息
            max_tokens: 最大 token 数
            temperature: 采样温度

        Returns:
            推理结果
        """
        return {
            "success": False,
            "error": "MLC LLM 后端尚未实现",
            "text": "",
            "model": model_info.name,
            "backend": InferenceBackend.MLC_LLM.value,
        }

    def list_available_models(self) -> List[Dict[str, Any]]:
        """列出所有可用模型"""
        return [
            {
                "id": model_id,
                "name": model_info.name,
                "backend": model_info.backend.value,
                "max_context": model_info.max_context,
                "capabilities": model_info.capabilities,
                "quantization": model_info.quantization,
                "is_mobile_optimized": model_info.is_mobile_optimized,
                "parameters_b": model_info.parameters_b,
            }
            for model_id, model_info in AVAILABLE_MODELS.items()
        ]

    def get_device_info(self) -> Dict[str, Any]:
        """获取设备信息"""
        return {
            "capabilities": self._device_capabilities,
            "sensors": self._sensor_status,
            "config": {
                "ollama_host": self.ollama_host,
                "openai_host": self.openai_host,
                "default_model": self.default_model,
            },
        }
