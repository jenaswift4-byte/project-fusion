// Nexa SDK 引擎封装 - Qwen 本地推理
package com.fusion.companion.llm;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/**
 * Nexa SDK 引擎封装
 * - 支持 Qwen-3-VL 多模态模型
 * - NPU/GPU/CPU 自动加速
 * - 本地推理，无需云端
 */
public class NexaEngine implements LLMEngine {
    private static final String TAG = "NexaEngine";

    private Context context;
    private boolean isInitialized = false;
    private String modelPath;

    // Nexa SDK 实例（反射加载，避免编译错误）
    private Object nexaInstance;
    private Class<?> nexaClass;

    public NexaEngine(Context context) {
        this.context = context;
    }

    /**
     * 加载模型（实现 LLMEngine 接口）
     */
    @Override
    public boolean loadModel(String modelPath) {
        return initialize(modelPath);
    }

    /**
     * 初始化 Nexa SDK
     * @param modelPath 模型路径（可为 null，使用内置 Qwen）
     * @return 是否成功
     */
    public boolean initialize(String modelPath) {
        try {
            this.modelPath = modelPath;

            // 反射加载 Nexa SDK（避免依赖缺失导致编译失败）
            nexaClass = Class.forName("ai.nexa.core.NexaSDK");
            nexaInstance = nexaClass.getConstructor(Context.class).newInstance(context);

            // 调用初始化方法
            Class<?> configClass = Class.forName("ai.nexa.core.NexaConfig");
            Object config = configClass.getConstructor().newInstance();

            // 设置模型路径
            if (modelPath != null) {
                configClass.getMethod("setModelPath", String.class).invoke(config, modelPath);
            }

            // 启用 NPU 加速
            configClass.getMethod("setUseNPU", boolean.class).invoke(config, true);

            // 初始化
            nexaClass.getMethod("initialize", configClass).invoke(nexaInstance, config);

            isInitialized = true;
            Log.i(TAG, "✓ Nexa SDK 初始化成功");
            return true;

        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Nexa SDK 未安装，使用简化版引擎");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Nexa SDK 初始化失败", e);
            return false;
        }
    }

    /**
     * 文本推理
     * @param prompt 提示词
     * @param maxTokens 最大 token 数
     * @return 推理结果
     */
    public String inferText(String prompt, int maxTokens) {
        if (!isInitialized || nexaInstance == null) {
            return null;
        }

        try {
            // 调用推理方法
            Object result = nexaClass.getMethod("infer", String.class, int.class)
                    .invoke(nexaInstance, prompt, maxTokens);

            return (String) result;

        } catch (Exception e) {
            Log.e(TAG, "推理失败", e);
            return null;
        }
    }

    /**
     * 多模态推理（图像）
     * @param prompt 提示词
     * @param imagePath 图像路径
     * @param maxTokens 最大 token 数
     * @return 推理结果
     */
    public String inferImage(String prompt, String imagePath, int maxTokens) {
        if (!isInitialized || nexaInstance == null) {
            return null;
        }

        try {
            // 调用多模态推理方法
            Object result = nexaClass.getMethod("inferMultimodal", String.class, String.class, int.class)
                    .invoke(nexaInstance, prompt, imagePath, maxTokens);

            return (String) result;

        } catch (Exception e) {
            Log.e(TAG, "多模态推理失败", e);
            return null;
        }
    }

    /**
     * 异步推理（回调方式）
     */
    @Override
    public void inferTextAsync(String prompt, int maxTokens, LLMEngine.InferenceCallback callback) {
        new Thread(() -> {
            String result = inferText(prompt, maxTokens);
            if (result != null) {
                callback.onResult(result);
            } else {
                callback.onError("推理失败");
            }
        }).start();
    }

    @Override
    public void inferImageAsync(String prompt, String imagePath, int maxTokens, LLMEngine.InferenceCallback callback) {
        new Thread(() -> {
            String result = inferImage(prompt, imagePath, maxTokens);
            if (result != null) {
                callback.onResult(result);
            } else {
                callback.onError("多模态推理失败");
            }
        }).start();
    }

    /**
     * 释放资源
     */
    public void release() {
        if (nexaInstance != null) {
            try {
                nexaClass.getMethod("release").invoke(nexaInstance);
            } catch (Exception e) {
                Log.e(TAG, "释放资源失败", e);
            }
        }
        isInitialized = false;
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * 获取引擎信息
     */
    public String getEngineInfo() {
        return "Nexa SDK (Qwen-3-VL, NPU加速)";
    }
}
