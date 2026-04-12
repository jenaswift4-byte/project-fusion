package com.fusion.companion.voice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Android 原生语音识别器
 * 
 * 基于 Android SpeechRecognizer API 实现语音识别
 * 支持在线和离线识别（取决于设备支持）
 * 
 * 特性：
 * - 使用 Android 原生 API，稳定可靠
 * - 支持中文识别
 * - 实时识别（边说边识别）
 * - 自动权限检查
 * - 与 MQTT 和 AI 引擎集成
 * 
 * 使用示例：
 * <pre>
 * {@code
 * // 1. 创建识别器
 * AndroidSpeechRecognizer recognizer = new AndroidSpeechRecognizer(context);
 * 
 * // 2. 设置监听器
 * recognizer.setRecognitionListener(new RecognitionListener() {
 *     @Override
 *     public void onResult(String text) {
 *         Log.i("Voice", "识别结果：" + text);
 *         
 *         // 发送到 MQTT
 *         mqttClient.publishTextMessage("fusion/voice/" + deviceId, text, 1);
 *         
 *         // 发送到 AI 引擎
 *         aiEngine.generate(text, new AICallback() {...});
 *     }
 * });
 * 
 * // 3. 检查权限
 * if (!recognizer.hasRecordPermission()) {
 *     recognizer.requestRecordPermission(activity);
 *     return;
 * }
 * 
 * // 4. 开始识别
 * recognizer.startRecognition();
 * 
 * // 5. 停止识别
 * recognizer.stopRecognition();
 * }
 * </pre>
 */
public class AndroidSpeechRecognizer implements RecognitionListener {
    
    private static final String TAG = "AndroidSpeechRecognizer";
    
    // ==================== 成员变量 ====================
    
    // 上下文
    private Context context;
    
    // Android SpeechRecognizer
    private SpeechRecognizer speechRecognizer;
    
    // 识别监听器
    private RecognitionListener listener;
    
    // 主线程 Handler
    private Handler mainHandler;
    
    // 是否正在识别
    private boolean isRecognizing;
    
    // 识别结果缓存
    private StringBuilder resultCache;
    
    // 设备 ID（用于 MQTT 发布）
    private String deviceId;
    
    // MQTT 客户端引用（用于发送识别结果）
    private Object mqttClient;
    
    /**
     * 构造函数
     * 
     * @param context 应用上下文
     */
    public AndroidSpeechRecognizer(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isRecognizing = false;
        this.resultCache = new StringBuilder();
        
        // 检查是否支持语音识别
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "设备不支持语音识别");
            return;
        }
        
        // 创建 SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(this);
        
        Log.i(TAG, "AndroidSpeechRecognizer 初始化完成");
    }
    
    /**
     * 检查是否有录音权限
     * 
     * @return true 如果有录音权限
     */
    public boolean hasRecordPermission() {
        int permission = context.checkCallingOrSelfPermission(
                android.Manifest.permission.RECORD_AUDIO);
        return permission == PackageManager.PERMISSION_GRANTED;
    }
    
    /**
     * 设置识别监听器
     * 
     * @param listener 识别监听器
     */
    public void setRecognitionListener(RecognitionListener listener) {
        this.listener = listener;
        Log.d(TAG, "识别监听器已设置");
    }
    
    /**
     * 设置设备 ID（用于 MQTT 发布）
     * 
     * @param deviceId 设备 ID
     */
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
        Log.d(TAG, "设备 ID 已设置：" + deviceId);
    }
    
    /**
     * 设置 MQTT 客户端（用于发送识别结果）
     * 
     * @param mqttClient MQTTClientService 实例
     */
    public void setMqttClient(Object mqttClient) {
        this.mqttClient = mqttClient;
        Log.d(TAG, "MQTT 客户端已设置");
    }
    
    /**
     * 开始语音识别
     * 
     * @return true 如果开始成功
     */
    public boolean startRecognition() {
        // 检查权限
        if (!hasRecordPermission()) {
            Log.e(TAG, "没有录音权限，请先申请权限");
            postError(RecognitionListener.ERROR_CLIENT, "没有录音权限");
            return false;
        }
        
        // 检查是否支持
        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer 不可用");
            postError(RecognitionListener.ERROR_CLIENT, "SpeechRecognizer 不可用");
            return false;
        }
        
        // 检查是否已经在识别
        if (isRecognizing) {
            Log.w(TAG, "正在识别中，跳过");
            return false;
        }
        
        Log.i(TAG, "开始语音识别");
        
        try {
            // 1. 清空缓存
            resultCache.setLength(0);
            
            // 2. 创建识别意图
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString());  // 中文
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);  // 实时结果
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);  // 只返回最佳结果
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);  // 3 秒静音结束
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L);  // 最少 5 秒
            
            // 3. 开始识别
            speechRecognizer.startListening(intent);
            isRecognizing = true;
            
            // 通知监听器
            postReadyForSpeech();
            
            Log.i(TAG, "语音识别已开始");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "开始识别失败", e);
            postError(RecognitionListener.ERROR_CLIENT, e.getMessage());
            return false;
        }
    }
    
    /**
     * 停止语音识别
     */
    public void stopRecognition() {
        if (!isRecognizing) {
            Log.w(TAG, "未在识别，跳过");
            return;
        }
        
        Log.i(TAG, "停止语音识别");
        
        try {
            // 1. 停止识别
            speechRecognizer.stopListening();
            isRecognizing = false;
            
            // 2. 处理最后的结果
            if (resultCache.length() > 0) {
                postResult(resultCache.toString());
            }
            
            // 通知监听器
            postEndOfSpeech();
            
            Log.i(TAG, "语音识别已停止");
            
        } catch (Exception e) {
            Log.e(TAG, "停止识别失败", e);
        }
    }
    
    /**
     * 取消识别
     */
    public void cancelRecognition() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            isRecognizing = false;
            Log.d(TAG, "识别已取消");
        }
    }
    
    /**
     * 销毁识别器（释放资源）
     */
    public void destroy() {
        Log.i(TAG, "销毁语音识别器");
        
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        
        listener = null;
        
        Log.i(TAG, "语音识别器已销毁");
    }
    
    // ==================== RecognitionListener 实现 ====================
    
    @Override
    public void onReadyForSpeech(Bundle params) {
        Log.d(TAG, "准备就绪，可以开始说话");
        postReadyForSpeech();
    }
    
    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "检测到说话开始");
        postBeginOfSpeech();
    }
    
    @Override
    public void onRmsChanged(float rmsdB) {
        // 音量变化
        int volume = (int) rmsdB;
        postVolumeChanged(volume);
    }
    
    @Override
    public void onBufferReceived(byte[] buffer) {
        // 接收到音频数据（可选处理）
    }
    
    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "说话结束");
        isRecognizing = false;
        postEndOfSpeech();
    }
    
    @Override
    public void onError(int error) {
        String errorMessage = getErrorMessage(error);
        Log.e(TAG, "识别错误：" + error + " - " + errorMessage);
        
        isRecognizing = false;
        postError(error, errorMessage);
    }
    
    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        
        if (matches != null && !matches.isEmpty()) {
            String recognizedText = matches.get(0);
            Log.i(TAG, "识别结果：" + recognizedText);
            
            // 更新缓存
            resultCache.append(recognizedText);
            
            // 发布最终结果
            postResult(recognizedText);
            
            // 发送到 MQTT（如果已配置）
            sendToMqtt(recognizedText);
        }
    }
    
    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        
        if (matches != null && !matches.isEmpty()) {
            String partialText = matches.get(0);
            Log.d(TAG, "实时识别结果：" + partialText);
            
            // 发布实时结果
            postPartialResult(partialText);
        }
    }
    
    @Override
    public void onEvent(int eventType, Bundle params) {
        // 保留用于未来扩展
        Log.d(TAG, "事件：" + eventType);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取错误消息
     */
    private String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "音频录制错误";
            case SpeechRecognizer.ERROR_CLIENT:
                return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "权限不足";
            case SpeechRecognizer.ERROR_NETWORK:
                return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "无法识别";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "识别服务忙";
            case SpeechRecognizer.ERROR_SERVER:
                return "服务器错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "说话超时";
            default:
                return "未知错误：" + error;
        }
    }
    
    /**
     * 发送识别结果到 MQTT
     */
    private void sendToMqtt(String text) {
        if (mqttClient == null || deviceId == null) {
            return;  // 未配置 MQTT
        }
        
        try {
            // 构建 MQTT 主题
            String topic = "fusion/voice/" + deviceId;
            
            // 构建 JSON 消息
            String jsonMessage = buildVoiceMessageJson(text);
            
            // 发布消息
            if (mqttClient instanceof com.fusion.companion.service.MQTTClientService) {
                com.fusion.companion.service.MQTTClientService mqttService = 
                        (com.fusion.companion.service.MQTTClientService) mqttClient;
                
                if (mqttService.isConnected()) {
                    mqttService.publishTextMessage(topic, jsonMessage, 1);
                    Log.d(TAG, "识别结果已发送到 MQTT: " + topic);
                } else {
                    Log.w(TAG, "MQTT 未连接，跳过发送");
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "发送 MQTT 消息失败", e);
        }
    }
    
    /**
     * 构建语音消息 JSON
     */
    private String buildVoiceMessageJson(String text) {
        long timestamp = System.currentTimeMillis();
        
        return String.format(
                "{\"device_id\":\"%s\",\"text\":\"%s\",\"timestamp\":%d}",
                deviceId,
                text,
                timestamp
        );
    }
    
    // ==================== 回调方法 ====================
    
    private void postReadyForSpeech() {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    listener.onReadyForSpeech();
                } catch (Exception e) {
                    Log.e(TAG, "onReadyForSpeech 回调失败", e);
                }
            });
        }
    }
    
    private void postBeginOfSpeech() {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    listener.onBeginOfSpeech();
                } catch (Exception e) {
                    Log.e(TAG, "onBeginOfSpeech 回调失败", e);
                }
            });
        }
    }
    
    private void postEndOfSpeech() {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    listener.onEndOfSpeech();
                } catch (Exception e) {
                    Log.e(TAG, "onEndOfSpeech 回调失败", e);
                }
            });
        }
    }
    
    private void postPartialResult(String text) {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    listener.onPartialResult(text);
                } catch (Exception e) {
                    Log.e(TAG, "onPartialResult 回调失败", e);
                }
            });
        }
    }
    
    private void postResult(String text) {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    listener.onResult(text);
                } catch (Exception e) {
                    Log.e(TAG, "onResult 回调失败", e);
                }
            });
        }
    }
    
    private void postVolumeChanged(int rmsdB) {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    listener.onVolumeChanged(rmsdB);
                } catch (Exception e) {
                    Log.e(TAG, "onVolumeChanged 回调失败", e);
                }
            });
        }
    }
    
    private void postError(int errorCode, String message) {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    listener.onError(errorCode, message);
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
     * 检查设备是否支持语音识别
     */
    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }
}
