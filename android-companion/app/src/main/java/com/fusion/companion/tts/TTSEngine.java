package com.fusion.companion.tts;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TTS 引擎类 - 文本转语音
 * 
 * 功能：
 * 1. 使用 Android 原生 TTS 进行语音合成
 * 2. 支持中文语音合成
 * 3. 支持语速/音调调节
 * 4. 支持播放状态回调
 * 5. 支持 TTS 队列（排队播放）
 * 
 * @author Fusion
 * @version 1.0
 */
public class TTSEngine implements TextToSpeech.OnInitListener {
    
    private static final String TAG = "TTSEngine";
    
    // 上下文
    private Context context;
    
    // TTS 实例
    private TextToSpeech tts;
    
    // TTS 是否初始化完成
    private AtomicBoolean isInitialized = new AtomicBoolean(false);
    
    // TTS 是否可用
    private boolean isAvailable = false;
    
    // 当前语速 (0.5 - 2.0)
    private float speechRate = 1.0f;
    
    // 当前音调 (0.5 - 2.0)
    private float pitch = 1.0f;
    
    // TTS 播放队列
    private ConcurrentLinkedQueue<String> ttsQueue = new ConcurrentLinkedQueue<>();
    
    // 是否正在播放
    private AtomicBoolean isSpeaking = new AtomicBoolean(false);
    
    // 播放状态回调
    private TTSStateCallback callback;
    
    /**
     * TTS 状态回调接口
     */
    public interface TTSStateCallback {
        /**
         * TTS 初始化完成
         * @param success 是否成功
         */
        void onInit(boolean success);
        
        /**
         * 开始播放
         * @param text 播放的文本
         */
        void onStart(String text);
        
        /**
         * 播放完成
         * @param text 播放的文本
         */
        void onDone(String text);
        
        /**
         * 播放失败
         * @param text 播放的文本
         * @param error 错误信息
         */
        void onError(String text, String error);
        
        /**
         * 播放被中断
         * @param text 播放的文本
         */
        void onInterrupt(String text);
    }
    
    /**
     * 构造函数
     * 
     * @param context 上下文
     */
    public TTSEngine(Context context) {
        this.context = context;
        init();
    }
    
    /**
     * 初始化 TTS 引擎
     */
    private void init() {
        try {
            // 创建 TTS 实例
            tts = new TextToSpeech(context, this);
            Log.i(TAG, "TTS 引擎初始化中...");
        } catch (Exception e) {
            Log.e(TAG, "TTS 引擎初始化失败", e);
            isAvailable = false;
            if (callback != null) {
                callback.onInit(false);
            }
        }
    }
    
    /**
     * TTS 初始化完成回调
     * 
     * @param status 初始化状态
     */
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // 设置语言为中文
            int result = tts.setLanguage(Locale.CHINESE);
            
            if (result == TextToSpeech.LANG_MISSING_DATA || 
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "中文语音不支持，尝试英文");
                // 如果不支持中文，尝试英文
                tts.setLanguage(Locale.ENGLISH);
            }
            
            // 应用语速和音调设置
            applySpeechSettings();
            
            // 设置播放进度监听器
            setupProgressListener();
            
            isInitialized.set(true);
            isAvailable = true;
            
            Log.i(TAG, "TTS 引擎初始化完成");
            
            if (callback != null) {
                callback.onInit(true);
            }
            
            // 播放队列中的内容
            processQueue();
        } else {
            Log.e(TAG, "TTS 引擎初始化失败，状态码：" + status);
            isInitialized.set(false);
            isAvailable = false;
            
            if (callback != null) {
                callback.onInit(false);
            }
        }
    }
    
    /**
     * 设置播放进度监听器
     */
    private void setupProgressListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "开始播放：" + utteranceId);
                isSpeaking.set(true);
                if (callback != null) {
                    callback.onStart(utteranceId);
                }
            }
            
            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "播放完成：" + utteranceId);
                isSpeaking.set(false);
                if (callback != null) {
                    callback.onDone(utteranceId);
                }
                // 播放队列中的下一个
                processQueue();
            }
            
            @Override
            public void onError(String utteranceId, int errorCode) {
                Log.e(TAG, "播放错误：" + utteranceId + ", 错误码：" + errorCode);
                isSpeaking.set(false);
                if (callback != null) {
                    callback.onError(utteranceId, "错误码：" + errorCode);
                }
                // 播放队列中的下一个
                processQueue();
            }
            
            @Override
            public void onError(String utteranceId) {
                // 兼容旧版本
                onError(utteranceId, -1);
            }
            
            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                Log.d(TAG, "播放停止：" + utteranceId + ", 是否被中断：" + interrupted);
                isSpeaking.set(false);
                if (callback != null) {
                    if (interrupted) {
                        callback.onInterrupt(utteranceId);
                    } else {
                        callback.onDone(utteranceId);
                    }
                }
            }
        });
    }
    
    /**
     * 应用语速和音调设置
     */
    private void applySpeechSettings() {
        if (tts != null && isInitialized.get()) {
            tts.setSpeechRate(speechRate);
            tts.setPitch(pitch);
            Log.d(TAG, "应用语速设置：" + speechRate + ", 音调设置：" + pitch);
        }
    }
    
    /**
     * 播放文本
     * 
     * @param text 要播放的文本
     */
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "播放空文本，忽略");
            return;
        }
        
        // 添加到播放队列
        ttsQueue.offer(text);
        Log.d(TAG, "文本已加入队列：" + text + ", 队列长度：" + ttsQueue.size());
        
        // 如果当前没有在播放，开始播放队列
        if (!isSpeaking.get() && isInitialized.get()) {
            processQueue();
        }
    }
    
    /**
     * 处理播放队列
     */
    private void processQueue() {
        if (ttsQueue.isEmpty()) {
            Log.d(TAG, "播放队列为空");
            return;
        }
        
        if (isSpeaking.get()) {
            Log.d(TAG, "正在播放，等待完成");
            return;
        }
        
        // 从队列取出文本
        String text = ttsQueue.poll();
        if (text != null) {
            Log.d(TAG, "开始播放队列中的文本：" + text);
            playText(text);
        }
    }
    
    /**
     * 播放文本（内部方法）
     * 
     * @param text 要播放的文本
     */
    private void playText(String text) {
        if (!isAvailable || !isInitialized.get()) {
            Log.w(TAG, "TTS 未初始化，无法播放");
            if (callback != null) {
                callback.onError(text, "TTS 未初始化");
            }
            return;
        }
        
        try {
            // 使用唯一的 utteranceId
            String utteranceId = "tts_" + System.currentTimeMillis();
            
            // 播放文本
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            
            if (result == TextToSpeech.SUCCESS) {
                Log.d(TAG, "播放成功：" + text);
            } else {
                Log.e(TAG, "播放失败，结果码：" + result);
                if (callback != null) {
                    callback.onError(text, "播放失败：" + result);
                }
                // 继续播放队列中的下一个
                processQueue();
            }
        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            if (callback != null) {
                callback.onError(text, e.getMessage());
            }
            // 继续播放队列中的下一个
            processQueue();
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        if (tts != null && isInitialized.get()) {
            tts.stop();
            isSpeaking.set(false);
            ttsQueue.clear();
            Log.d(TAG, "TTS 播放已停止");
        }
    }
    
    /**
     * 设置语速
     * 
     * @param rate 语速 (0.5 - 2.0)
     */
    public void setSpeechRate(float rate) {
        if (rate < 0.5f) {
            rate = 0.5f;
        } else if (rate > 2.0f) {
            rate = 2.0f;
        }
        
        this.speechRate = rate;
        applySpeechSettings();
        Log.d(TAG, "语速已设置：" + rate);
    }
    
    /**
     * 设置音调
     * 
     * @param pitch 音调 (0.5 - 2.0)
     */
    public void setPitch(float pitch) {
        if (pitch < 0.5f) {
            pitch = 0.5f;
        } else if (pitch > 2.0f) {
            pitch = 2.0f;
        }
        
        this.pitch = pitch;
        applySpeechSettings();
        Log.d(TAG, "音调已设置：" + pitch);
    }
    
    /**
     * 获取当前语速
     * 
     * @return 语速值
     */
    public float getSpeechRate() {
        return speechRate;
    }
    
    /**
     * 获取当前音调
     * 
     * @return 音调值
     */
    public float getPitch() {
        return pitch;
    }
    
    /**
     * 检查 TTS 是否可用
     * 
     * @return TTS 是否可用
     */
    public boolean isAvailable() {
        return isAvailable && isInitialized.get();
    }
    
    /**
     * 检查是否正在播放
     * 
     * @return 是否正在播放
     */
    public boolean isSpeaking() {
        return isSpeaking.get();
    }
    
    /**
     * 获取队列中的文本数量
     * 
     * @return 队列长度
     */
    public int getQueueSize() {
        return ttsQueue.size();
    }
    
    /**
     * 设置播放状态回调
     * 
     * @param callback 回调接口
     */
    public void setCallback(TTSStateCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        Log.i(TAG, "释放 TTS 资源");
        
        try {
            // 停止播放
            stop();
            
            // 关闭 TTS
            if (tts != null) {
                tts.shutdown();
                tts = null;
            }
            
            isInitialized.set(false);
            isAvailable = false;
            
            Log.i(TAG, "TTS 资源已释放");
        } catch (Exception e) {
            Log.e(TAG, "释放 TTS 资源失败", e);
        }
    }
}
