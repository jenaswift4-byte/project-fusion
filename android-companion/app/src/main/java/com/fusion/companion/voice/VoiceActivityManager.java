package com.fusion.companion.voice;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.fusion.companion.ai.LocalAIEngine;
import com.fusion.companion.service.MQTTClientService;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音活动管理器
 * 
 * 统一管理语音识别、AI 回答、TTS 播放和 MQTT 通信
 * 
 * 功能：
 * - 语音识别（使用 AndroidSpeechRecognizer 或 WhisperVoiceRecognizer）
 * - 识别结果发送到 MQTT
 * - 发送识别结果到 AI 引擎
 * - TTS 播放 AI 回答
 * - 完整的语音交互流程
 * 
 * 使用示例：
 * <pre>
 * {@code
 * // 1. 创建管理器
 * VoiceActivityManager voiceManager = new VoiceActivityManager(context);
 * 
 * // 2. 初始化（设置 MQTT 和 AI 引擎）
 * voiceManager.initialize(mqttClientService, localAIEngine);
 * 
 * // 3. 检查权限
 * if (!voiceManager.hasRecordPermission()) {
 *     voiceManager.requestRecordPermission(activity);
 *     return;
 * }
 * 
 * // 4. 开始语音交互
 * voiceManager.startVoiceInteraction();
 * 
 * // 5. 停止语音交互
 * voiceManager.stopVoiceInteraction();
 * 
 * // 6. 释放资源
 * voiceManager.release();
 * }
 * </pre>
 */
public class VoiceActivityManager implements VoiceRecognitionListener {
    
    private static final String TAG = "VoiceActivityManager";
    
    // ==================== 成员变量 ====================
    
    // 上下文
    private Context context;
    
    // 语音识别器（使用 Android 原生 API）
    private AndroidSpeechRecognizer speechRecognizer;
    
    // TTS 引擎
    private TextToSpeech ttsEngine;
    
    // MQTT 客户端
    private MQTTClientService mqttClient;
    
    // AI 引擎
    private LocalAIEngine aiEngine;
    
    // 设备 ID
    private String deviceId;
    
    // 是否正在识别
    private boolean isRecognizing;
    
    // 是否正在播放 TTS
    private boolean isTTSPlaying;
    
    // 回调监听器
    private VoiceInteractionListener voiceListener;
    
    // 主线程 Handler
    private android.os.Handler mainHandler;
    
    // AI 推理线程池
    private ExecutorService aiExecutor;
    
    /**
     * 语音交互监听器接口
     */
    public interface VoiceInteractionListener {
        /**
         * 识别开始
         */
        void onRecognitionStart();
        
        /**
         * 识别结束
         */
        void onRecognitionEnd();
        
        /**
         * 识别到文本
         * @param text 识别的文本
         */
        void onTextRecognized(String text);
        
        /**
         * AI 回答生成
         * @param answer AI 的回答
         */
        void onAIResponse(String answer);
        
        /**
         * TTS 开始播放
         */
        void onTTSStart();
        
        /**
         * TTS 播放结束
         */
        void onTTSEnd();
        
        /**
         * 错误
         * @param errorCode 错误代码
         * @param message 错误消息
         */
        void onError(int errorCode, String message);
    }
    
    /**
     * 构造函数
     * 
     * @param context 应用上下文
     */
    public VoiceActivityManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        this.isRecognizing = false;
        this.isTTSPlaying = false;
        this.aiExecutor = Executors.newFixedThreadPool(2);
        
        Log.i(TAG, "VoiceActivityManager 初始化完成");
    }
    
    /**
     * 初始化管理器
     * 
     * @param mqttClient MQTT 客户端服务
     * @param aiEngine 本地 AI 引擎
     */
    public void initialize(MQTTClientService mqttClient, LocalAIEngine aiEngine) {
        this.mqttClient = mqttClient;
        this.aiEngine = aiEngine;
        
        // 获取设备 ID
        if (mqttClient != null) {
            this.deviceId = mqttClient.getMqttDeviceId();
        }
        
        // 创建语音识别器
        speechRecognizer = new AndroidSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(this);
        speechRecognizer.setDeviceId(deviceId);
        speechRecognizer.setMqttClient(mqttClient);
        
        // 初始化 TTS
        initTTSEngine();
        
        Log.i(TAG, "VoiceActivityManager 初始化完成 - 设备 ID: " + deviceId);
    }
    
    /**
     * 初始化 TTS 引擎
     */
    private void initTTSEngine() {
        Log.d(TAG, "初始化 TTS 引擎...");
        
        ttsEngine = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // 设置中文
                int result = ttsEngine.setLanguage(Locale.CHINA);
                if (result == TextToSpeech.LANG_MISSING_DATA || 
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS 不支持中文，使用默认语言");
                }
                
                // 设置语速和音调
                ttsEngine.setSpeechRate(1.0f);  // 正常语速
                ttsEngine.setPitch(1.0f);       // 正常音调
                
                // 设置监听器
                ttsEngine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        Log.d(TAG, "TTS 开始播放");
                        isTTSPlaying = true;
                        postTTSStart();
                    }
                    
                    @Override
                    public void onDone(String utteranceId) {
                        Log.d(TAG, "TTS 播放完成");
                        isTTSPlaying = false;
                        postTTSEnd();
                    }
                    
                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "TTS 播放错误");
                        isTTSPlaying = false;
                    }
                });
                
                Log.i(TAG, "TTS 引擎初始化完成");
            } else {
                Log.e(TAG, "TTS 引擎初始化失败");
            }
        });
    }
    
    /**
     * 设置语音交互监听器
     * 
     * @param listener 监听器
     */
    public void setVoiceInteractionListener(VoiceInteractionListener listener) {
        this.voiceListener = listener;
        Log.d(TAG, "语音交互监听器已设置");
    }
    
    /**
     * 检查是否有录音权限
     */
    public boolean hasRecordPermission() {
        return speechRecognizer != null && speechRecognizer.hasRecordPermission();
    }
    
    /**
     * 开始语音交互
     * 
     * @return true 如果开始成功
     */
    public boolean startVoiceInteraction() {
        if (speechRecognizer == null) {
            Log.e(TAG, "语音识别器未初始化");
            return false;
        }
        
        if (isRecognizing) {
            Log.w(TAG, "正在识别中，跳过");
            return false;
        }
        
        Log.i(TAG, "开始语音交互");
        
        // 停止当前 TTS（如果有）
        stopTTS();
        
        // 开始识别
        boolean success = speechRecognizer.startRecognition();
        
        if (success) {
            isRecognizing = true;
            postRecognitionStart();
        }
        
        return success;
    }
    
    /**
     * 停止语音交互
     */
    public void stopVoiceInteraction() {
        if (!isRecognizing) {
            return;
        }
        
        Log.i(TAG, "停止语音交互");
        
        if (speechRecognizer != null) {
            speechRecognizer.stopRecognition();
        }
        
        isRecognizing = false;
        postRecognitionEnd();
    }
    
    /**
     * 播放 TTS
     * 
     * @param text 要播放的文本
     */
    public void playTTS(String text) {
        if (ttsEngine == null) {
            Log.e(TAG, "TTS 引擎未初始化");
            return;
        }
        
        if (text == null || text.isEmpty()) {
            Log.w(TAG, "TTS 文本为空");
            return;
        }
        
        Log.d(TAG, "播放 TTS: " + text);
        
        // 停止当前播放
        stopTTS();
        
        // 开始播放
        String utteranceId = "voice_response_" + System.currentTimeMillis();
        ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }
    
    /**
     * 停止 TTS 播放
     */
    public void stopTTS() {
        if (ttsEngine != null && isTTSPlaying) {
            ttsEngine.stop();
            isTTSPlaying = false;
            Log.d(TAG, "TTS 已停止");
        }
    }
    
    /**
     * 处理识别结果
     * 
     * @param text 识别的文本
     */
    private void handleRecognitionResult(String text) {
        Log.i(TAG, "处理识别结果：" + text);
        
        // 通知监听器
        postTextRecognized(text);
        
        // 发送到 AI 引擎（异步）
        if (aiEngine != null && aiEngine.isModelLoaded()) {
            processWithAI(text);
        }
    }
    
    /**
     * 使用 AI 处理识别结果
     */
    private void processWithAI(String text) {
        aiExecutor.submit(() -> {
            try {
                Log.d(TAG, "开始 AI 推理...");
                
                // 构建提示词
                String prompt = buildPrompt(text);
                
                // 生成回答
                String answer = aiEngine.generate(prompt, 256, 0.7f);
                
                Log.i(TAG, "AI 回答：" + answer);
                
                // 通知监听器
                postAIResponse(answer);
                
                // 播放回答（TTS）
                playTTS(answer);
                
            } catch (Exception e) {
                Log.e(TAG, "AI 处理失败", e);
                postError(VoiceRecognitionListener.ERROR_SERVER, "AI 处理失败：" + e.getMessage());
            }
        });
    }
    
    /**
     * 构建 AI 提示词
     */
    private String buildPrompt(String userText) {
        // 简化实现：直接返回用户文本
        // 可以添加系统提示词、上下文等
        return userText;
    }
    
    // ==================== RecognitionListener 实现 ====================
    
    @Override
    public void onBeginOfSpeech() {
        Log.d(TAG, "开始说话");
        // 可以在这里更新 UI 显示录音状态
    }
    
    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "说话结束");
        isRecognizing = false;
        postRecognitionEnd();
    }
    
    @Override
    public void onPartialResult(String text) {
        Log.d(TAG, "实时识别：" + text);
        // 可以更新 UI 显示实时识别结果
    }
    
    @Override
    public void onResult(String text) {
        Log.i(TAG, "最终识别结果：" + text);
        handleRecognitionResult(text);
    }
    
    @Override
    public void onError(int errorCode, String message) {
        Log.e(TAG, "识别错误：" + errorCode + " - " + message);
        isRecognizing = false;
        postError(errorCode, message);
    }
    
    @Override
    public void onVolumeChanged(int rmsdB) {
        // 音量变化，可以更新 UI 波形
    }
    
    @Override
    public void onReadyForSpeech() {
        Log.d(TAG, "准备就绪");
        postRecognitionStart();
    }
    
    // ==================== 回调方法 ====================
    
    private void postRecognitionStart() {
        if (voiceListener != null) {
            mainHandler.post(() -> {
                try {
                    voiceListener.onRecognitionStart();
                } catch (Exception e) {
                    Log.e(TAG, "onRecognitionStart 回调失败", e);
                }
            });
        }
    }
    
    private void postRecognitionEnd() {
        if (voiceListener != null) {
            mainHandler.post(() -> {
                try {
                    voiceListener.onRecognitionEnd();
                } catch (Exception e) {
                    Log.e(TAG, "onRecognitionEnd 回调失败", e);
                }
            });
        }
    }
    
    private void postTextRecognized(String text) {
        if (voiceListener != null) {
            mainHandler.post(() -> {
                try {
                    voiceListener.onTextRecognized(text);
                } catch (Exception e) {
                    Log.e(TAG, "onTextRecognized 回调失败", e);
                }
            });
        }
    }
    
    private void postAIResponse(String answer) {
        if (voiceListener != null) {
            mainHandler.post(() -> {
                try {
                    voiceListener.onAIResponse(answer);
                } catch (Exception e) {
                    Log.e(TAG, "onAIResponse 回调失败", e);
                }
            });
        }
    }
    
    private void postTTSStart() {
        if (voiceListener != null) {
            mainHandler.post(() -> {
                try {
                    voiceListener.onTTSStart();
                } catch (Exception e) {
                    Log.e(TAG, "onTTSStart 回调失败", e);
                }
            });
        }
    }
    
    private void postTTSEnd() {
        if (voiceListener != null) {
            mainHandler.post(() -> {
                try {
                    voiceListener.onTTSEnd();
                } catch (Exception e) {
                    Log.e(TAG, "onTTSEnd 回调失败", e);
                }
            });
        }
    }
    
    private void postError(int errorCode, String message) {
        if (voiceListener != null) {
            mainHandler.post(() -> {
                try {
                    voiceListener.onError(errorCode, message);
                } catch (Exception e) {
                    Log.e(TAG, "onError 回调失败", e);
                }
            });
        }
    }
    
    // ==================== 公开方法 ====================
    
    /**
     * 检查是否正在识别
     */
    public boolean isRecognizing() {
        return isRecognizing;
    }
    
    /**
     * 检查 TTS 是否正在播放
     */
    public boolean isTTSPlaying() {
        return isTTSPlaying;
    }
    
    /**
     * 获取设备 ID
     */
    public String getDeviceId() {
        return deviceId;
    }
    
    /**
     * 释放所有资源
     */
    public void release() {
        Log.i(TAG, "释放所有资源");
        
        // 停止识别
        stopVoiceInteraction();
        
        // 停止 TTS
        stopTTS();
        
        // 销毁识别器
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        
        // 关闭 TTS
        if (ttsEngine != null) {
            ttsEngine.shutdown();
            ttsEngine = null;
        }
        
        // 关闭线程池
        if (aiExecutor != null) {
            aiExecutor.shutdownNow();
        }
        
        voiceListener = null;
        mqttClient = null;
        aiEngine = null;
        
        Log.i(TAG, "资源释放完成");
    }
}
