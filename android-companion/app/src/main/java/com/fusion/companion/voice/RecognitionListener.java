package com.fusion.companion.voice;

/**
 * 语音识别监听器接口
 * 
 * 用于接收语音识别的回调结果，包括：
 * - 识别开始
 * - 识别中的实时结果
 * - 识别最终结果
 * - 识别错误
 * - 音量变化
 * 
 * 使用示例：
 * <pre>
 * {@code
 * recognizer.setRecognitionListener(new RecognitionListener() {
 *     @Override
 *     public void onResult(String text) {
 *         Log.i("Voice", "识别结果：" + text);
 *     }
 *     
 *     @Override
 *     public void onError(int errorCode, String message) {
 *         Log.e("Voice", "识别错误：" + message);
 *     }
 * });
 * }
 * </pre>
 */
public interface VoiceRecognitionListener {
    
    /**
     * 识别开始回调
     * 当录音开始并准备识别时调用
     */
    void onBeginOfSpeech();
    
    /**
     * 识别结束回调
     * 当录音停止时调用
     */
    void onEndOfSpeech();
    
    /**
     * 实时识别结果回调
     * 在识别过程中持续调用，返回中间识别结果
     * 
     * @param text 当前识别的文本（可能不完整）
     */
    void onPartialResult(String text);
    
    /**
     * 最终识别结果回调
     * 当识别完成时调用，返回最终识别结果
     * 
     * @param text 最终识别的文本
     */
    void onResult(String text);
    
    /**
     * 识别错误回调
     * 当识别过程中发生错误时调用
     * 
     * @param errorCode 错误代码
     *        - ERROR_AUDIO: 音频录制错误
     *        - ERROR_NETWORK: 网络错误（如果使用在线识别）
     *        - ERROR_SERVER: 服务器错误
     *        - ERROR_CLIENT: 客户端错误
     *        - ERROR_SPEECH_TIMEOUT: 说话超时
     *        - ERROR_NO_MATCH: 无法识别
     */
    void onError(int errorCode, String message);
    
    /**
     * 音量变化回调
     * 可用于显示音量波形或检测是否有人说话
     * 
     * @param rmsdB 音量大小（分贝），通常在 0-100 之间
     */
    void onVolumeChanged(int rmsdB);
    
    /**
     * 识别准备就绪回调
     * 当识别器初始化完成，可以开始录音时调用
     */
    void onReadyForSpeech();
    
    // ==================== 错误代码常量 ====================
    
    /** 音频录制错误 */
    int ERROR_AUDIO = 1;
    
    /** 网络错误（仅在线识别） */
    int ERROR_NETWORK = 2;
    
    /** 服务器错误（仅在线识别） */
    int ERROR_SERVER = 3;
    
    /** 客户端错误 */
    int ERROR_CLIENT = 4;
    
    /** 说话超时 */
    int ERROR_SPEECH_TIMEOUT = 5;
    
    /** 无法识别 */
    int ERROR_NO_MATCH = 6;
}
