// Nexa SDK 引擎封装 - 使用真实 API (ai.nexa:core:0.0.24)
// 真实包名: com.nexa.sdk (不是 ai.nexa.sdk)
package com.fusion.companion.llm

import android.content.Context
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Nexa SDK 引擎封装（真实 API）
 *
 * 使用 ai.nexa:core:0.0.24 的 LlmWrapper API：
 * - LlmWrapper.builder().llmCreateInput(...).build() — suspend 函数
 * - applyChatTemplate() — suspend 函数，格式化消息
 * - generateStreamFlow() — 返回 Flow<LlmStreamResult>
 *
 * 小米8 (骁龙845) 不支持 NPU，使用 CPU 模式 (plugin_id="cpu_gpu", device_id=null)
 * 支持 GGUF 格式模型
 */
class NexaEngine(private val context: Context) : LLMEngine {

    companion object {
        private const val TAG = "NexaEngine"
        private const val INFERENCE_TIMEOUT_MS = 120_000L // 120秒超时（CPU 推理较慢）
    }

    private var llmWrapper: LlmWrapper? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var _isInitialized = false

    override fun loadModel(modelPath: String?): Boolean = initialize(modelPath)

    /**
     * 初始化 Nexa SDK
     *
     * 流程：
     * 1. NexaSdk.getInstance().init(context) - 全局初始化，加载插件 .so
     * 2. LlmWrapper.builder().llmCreateInput(...).build() - 构建推理引擎 (suspend)
     *
     * 注意：build() 是 suspend 函数，需要在协程中调用。
     * 这里使用 runBlocking 阻塞等待（因为 LLMEngine 接口要求同步返回）。
     */
    fun initialize(modelPath: String?): Boolean {
        try {
            // Step 1: 全局初始化 NexaSdk（加载原生插件）
            val initLatch = CountDownLatch(1)
            val initSuccess = AtomicBoolean(false)
            val initError = AtomicReference<String?>(null)

            NexaSdk.getInstance().init(context, object : NexaSdk.InitCallback {
                override fun onSuccess() {
                    initSuccess.set(true)
                    initLatch.countDown()
                    Log.i(TAG, "✓ NexaSdk 全局初始化成功")
                }

                override fun onFailure(reason: String) {
                    initSuccess.set(false)
                    initError.set(reason)
                    initLatch.countDown()
                    Log.w(TAG, "NexaSdk 初始化部分失败: $reason")
                    // 插件加载失败不一定是致命的，CPU 插件可能仍然可用
                    initSuccess.set(true) // 允许继续
                }
            })

            // 等待初始化完成
            if (!initLatch.await(30, TimeUnit.SECONDS)) {
                Log.e(TAG, "✗ NexaSdk 初始化超时")
                return false
            }

            // Step 2: 确定模型路径
            val effectivePath = if (!modelPath.isNullOrEmpty()) {
                modelPath
            } else {
                "${context.filesDir}/models/qwen/qwen3-0.6b-q4_k_m.gguf"
            }
            Log.i(TAG, "模型路径: $effectivePath")

            // Step 3: 构建 ModelConfig (CPU 模式)
            val modelConfig = ModelConfig(
                nCtx = 4096,
                nThreads = 4,        // 小米8 大核 4 线程
                nThreadsBatch = 4,
                nGpuLayers = 0,      // 0 = CPU 模式
                max_tokens = 2048
            )

            // Step 4: 构建 LlmCreateInput
            val llmCreateInput = LlmCreateInput(
                model_name = "",           // GGUF 模式留空
                model_path = effectivePath,
                tokenizer_path = null,     // 可选
                config = modelConfig,
                plugin_id = "cpu_gpu",     // CPU/GPU 插件
                device_id = null           // null = CPU
            )

            // Step 5: LlmWrapper.builder().llmCreateInput(input).build()
            // build() 是 suspend 函数，需要协程调用
            val buildResult = runBlocking {
                try {
                    LlmWrapper.builder()
                        .llmCreateInput(llmCreateInput)
                        .build()
                } catch (e: Exception) {
                    Log.e(TAG, "LlmWrapper 构建异常: ${e.message}")
                    Result.failure(e)
                }
            }

            val wrapper = buildResult.getOrNull()
            if (wrapper == null) {
                val exception = buildResult.exceptionOrNull()
                Log.e(TAG, "✗ LlmWrapper 构建失败: ${exception?.message}")
                Log.e(TAG, "  模型路径: $effectivePath")
                Log.e(TAG, "  请确认模型文件存在且为有效的 GGUF 格式")
                return false
            }

            this.llmWrapper = wrapper
            _isInitialized = true
            Log.i(TAG, "✓ Nexa SDK 初始化成功 (CPU模式, GGUF: $effectivePath)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Nexa SDK 初始化失败", e)
            return false
        }
    }

    /**
     * 文本推理（同步阻塞）
     *
     * 使用 generateStreamFlow 收集所有 token，拼接为完整字符串
     */
    override fun inferText(prompt: String, maxTokens: Int): String? {
        val wrapper = llmWrapper ?: return null
        if (!_isInitialized) return null

        return try {
            // Step 1: 构造 ChatMessage
            val chatList = arrayListOf(
                ChatMessage("system", "你是一个智能助手，请用简洁的中文回答。"),
                ChatMessage("user", prompt)
            )

            // Step 2: applyChatTemplate (suspend)
            val templateResult = runBlocking {
                wrapper.applyChatTemplate(
                    chatList.toTypedArray(),
                    null,       // tools
                    false,      // enableThinking
                    true        // addGenerationPrompt
                )
            }

            val template = templateResult.getOrNull()
            if (template == null) {
                val ex = templateResult.exceptionOrNull()
                Log.e(TAG, "applyChatTemplate 失败: ${ex?.message}")
                return null
            }

            // Step 3: 构建 GenerationConfig
            val genConfig = GenerationConfig(maxTokens = maxTokens)

            // Step 4: 流式生成，收集所有 token
            val resultBuilder = StringBuilder()
            val errorRef = AtomicReference<Throwable?>(null)
            val latch = CountDownLatch(1)

            coroutineScope.launch {
                try {
                    wrapper.generateStreamFlow(template.formattedText, genConfig).collect { streamResult ->
                        when (streamResult) {
                            is LlmStreamResult.Token -> {
                                resultBuilder.append(streamResult.text)
                            }
                            is LlmStreamResult.Completed -> {
                                Log.d(TAG, "推理完成 (profile: ${streamResult.profile})")
                                latch.countDown()
                            }
                            is LlmStreamResult.Error -> {
                                errorRef.set(streamResult.throwable)
                                Log.e(TAG, "流式推理错误: ${streamResult.throwable.message}")
                                latch.countDown()
                            }
                        }
                    }
                    // Flow 正常结束也算完成
                    latch.countDown()
                } catch (e: Exception) {
                    errorRef.set(e)
                    Log.e(TAG, "Flow 收集异常", e)
                    latch.countDown()
                }
            }

            // 等待推理完成（最长 INFERENCE_TIMEOUT_MS）
            val completed = latch.await(INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) {
                Log.w(TAG, "推理超时 (${INFERENCE_TIMEOUT_MS / 1000}s)，返回已收集的结果")
            }

            val error = errorRef.get()
            if (error != null && resultBuilder.isEmpty()) {
                Log.e(TAG, "推理失败: ${error.message}")
                return null
            }

            val result = resultBuilder.toString().trim()
            if (result.isEmpty()) {
                Log.w(TAG, "推理结果为空")
                return null
            }

            Log.i(TAG, "✓ 推理完成 (${result.length}字): ${result.take(100)}${if (result.length > 100) "..." else ""}")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "推理失败", e)
            return null
        }
    }

    /**
     * 多模态推理（图像）
     * Nexa SDK 使用 VlmWrapper 进行视觉推理
     * 当前仅支持文本，图像推理需要单独构建 VlmWrapper
     */
    override fun inferImage(prompt: String, imagePath: String, maxTokens: Int): String? {
        if (!_isInitialized || llmWrapper == null) return null
        Log.w(TAG, "多模态推理需要 VlmWrapper，当前 LlmWrapper 仅支持文本")
        return "[多模态推理暂未实现] 图像: $imagePath，提示: $prompt"
    }

    override fun inferTextAsync(prompt: String, maxTokens: Int, callback: LLMEngine.InferenceCallback) {
        Thread {
            try {
                val result = inferText(prompt, maxTokens)
                if (result != null) {
                    callback.onResult(result)
                } else {
                    callback.onError("推理返回空结果")
                }
            } catch (e: Exception) {
                callback.onError("推理异常: ${e.message}")
            }
        }.start()
    }

    override fun inferImageAsync(
        prompt: String,
        imagePath: String,
        maxTokens: Int,
        callback: LLMEngine.InferenceCallback
    ) {
        Thread {
            val result = inferImage(prompt, imagePath, maxTokens)
            if (result != null) {
                callback.onResult(result)
            } else {
                callback.onError("多模态推理暂未实现")
            }
        }.start()
    }

    override fun release() {
        llmWrapper?.let {
            try {
                it.close()  // LlmWrapper 实现了 Closeable
            } catch (e: Exception) {
                Log.w(TAG, "释放 LlmWrapper 失败", e)
            }
        }
        _isInitialized = false
        llmWrapper = null
        Log.i(TAG, "Nexa SDK 资源已释放")
    }

    override fun isInitialized(): Boolean = _isInitialized

    override fun getEngineInfo(): String = "Nexa SDK 0.0.24 (LlmWrapper, CPU模式, GGUF)"
}
