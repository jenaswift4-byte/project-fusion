// LLM 引擎接口
package com.fusion.companion.llm;

/**
 * LLM 引擎接口
 * 统一抽象不同推理引擎（Nexa SDK、简化版引擎等）
 */
public interface LLMEngine {

    /**
     * 加载模型
     * @param modelPath 模型路径（可为 null）
     * @return 是否成功
     */
    boolean loadModel(String modelPath);

    /**
     * 文本推理
     * @param prompt 提示词
     * @param maxTokens 最大 token 数
     * @return 推理结果
     */
    String inferText(String prompt, int maxTokens);

    /**
     * 多模态推理（图像）
     * @param prompt 提示词
     * @param imagePath 图像路径
     * @param maxTokens 最大 token 数
     * @return 推理结果
     */
    String inferImage(String prompt, String imagePath, int maxTokens);

    /**
     * 异步推理回调接口
     */
    interface InferenceCallback {
        void onResult(String result);
        void onError(String error);
    }

    /**
     * 异步文本推理
     */
    void inferTextAsync(String prompt, int maxTokens, InferenceCallback callback);

    /**
     * 异步多模态推理
     */
    void inferImageAsync(String prompt, String imagePath, int maxTokens, InferenceCallback callback);

    /**
     * 释放资源
     */
    void release();

    /**
     * 检查是否已初始化
     */
    boolean isInitialized();

    /**
     * 获取引擎信息
     */
    String getEngineInfo();
}
