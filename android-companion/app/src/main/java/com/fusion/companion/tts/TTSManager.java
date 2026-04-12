package com.fusion.companion.tts;

import android.content.Context;
import android.util.Log;

import com.fusion.companion.ai.LocalAIEngine;
import com.fusion.companion.ai.LocalAIEngine.StreamGenerationCallback;

/**
 * TTS 管理器 - 集成 AI 引擎和 TTS
 * 
 * 功能：
 * 1. AI 回答自动播放 TTS
 * 2. 支持 TTS 队列管理
 * 3. 支持流式播放（边生成边播放）
 * 4. 支持打断和恢复
 * 
 * @author Fusion
 * @version 1.0
 */
public class TTSManager {
    
    private static final String TAG = "TTSManager";
    
    // 单例实例
    private static TTSManager instance;
    
    // 上下文
    private Context context;
    
    // TTS 引擎
    private TTSEngine ttsEngine;
    
    // AI 引擎
    private LocalAIEngine aiEngine;
    
    // 是否启用 TTS
    private boolean ttsEnabled = true;
    
    // 是否正在播放 AI 回答
    private boolean isPlayingAIResponse = false;
    
    // 当前播放的文本构建器
    private StringBuilder currentTextBuilder;
    
    // 播放缓冲阈值（字符数，达到该值开始播放）
    private static final int PLAY_BUFFER_THRESHOLD = 50;
    
    // 最后一次播放时间（用于防抖）
    private long lastPlayTime = 0;
    
    // 播放间隔（毫秒）
    private static final long PLAY_INTERVAL_MS = 500;
    
    /**
     * 私有构造函数（单例模式）
     */
    private TTSManager(Context context) {
        this.context = context.getApplicationContext();
        init();
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized TTSManager getInstance(Context context) {
        if (instance == null) {
            instance = new TTSManager(context);
        }
        return instance;
    }
    
    /**
     * 初始化
     */
    private void init() {
        Log.i(TAG, "初始化 TTS 管理器");
        
        // 初始化 TTS 引擎
        ttsEngine = new TTSEngine(context);
        
        // 获取 AI 引擎实例
        aiEngine = LocalAIEngine.getInstance(context);
        
        // 设置 TTS 回调
        ttsEngine.setCallback(new TTSEngine.TTSStateCallback() {
            @Override
            public void onInit(boolean success) {
                Log.i(TAG, "TTS 初始化：" + (success ? "成功" : "失败"));
            }
            
            @Override
            public void onStart(String text) {
                Log.d(TAG, "开始播放：" + text);
                isPlayingAIResponse = true;
            }
            
            @Override
            public void onDone(String text) {
                Log.d(TAG, "播放完成：" + text);
                // 检查是否还有内容在缓冲
                playBufferedText();
            }
            
            @Override
            public void onError(String text, String error) {
                Log.e(TAG, "播放错误：" + text + ", " + error);
                isPlayingAIResponse = false;
            }
            
            @Override
            public void onInterrupt(String text) {
                Log.d(TAG, "播放被中断：" + text);
                isPlayingAIResponse = false;
            }
        });
        
        currentTextBuilder = new StringBuilder();
        
        Log.i(TAG, "TTS 管理器初始化完成");
    }
    
    /**
     * 启用/禁用 TTS
     * 
     * @param enabled 是否启用
     */
    public void setTTSEnabled(boolean enabled) {
        this.ttsEnabled = enabled;
        Log.i(TAG, "TTS 已" + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * TTS 是否启用
     * 
     * @return 是否启用
     */
    public boolean isTTSEnabled() {
        return ttsEnabled;
    }
    
    /**
     * 播放 AI 回答（流式）
     * 
     * @param prompt 用户问题
     */
    public void playAIResponseStream(String prompt) {
        if (!ttsEnabled) {
            Log.w(TAG, "TTS 已禁用，跳过播放");
            return;
        }
        
        if (!aiEngine.isModelLoaded()) {
            Log.w(TAG, "AI 模型未加载，无法生成");
            speak("AI 模型未加载，请先下载模型");
            return;
        }
        
        Log.d(TAG, "开始流式播放 AI 回答：" + prompt);
        
        // 重置状态
        currentTextBuilder.setLength(0);
        isPlayingAIResponse = false;
        
        // 开始流式生成
        aiEngine.generateStream(prompt, new StreamGenerationCallback() {
            @Override
            public void onStart() {
                Log.d(TAG, "AI 生成开始");
            }
            
            @Override
            public void onToken(String token) {
                // 累加 token
                currentTextBuilder.append(token);
                
                // 检查是否达到播放阈值
                if (currentTextBuilder.length() >= PLAY_BUFFER_THRESHOLD) {
                    tryPlayBuffer();
                }
            }
            
            @Override
            public void onComplete(String result) {
                Log.i(TAG, "AI 生成完成：" + result.length() + " 字符");
                
                // 播放剩余内容
                if (currentTextBuilder.length() > 0) {
                    speak(currentTextBuilder.toString());
                    currentTextBuilder.setLength(0);
                }
                
                isPlayingAIResponse = false;
            }
            
            @Override
            public void onError(Exception e) {
                Log.e(TAG, "AI 生成失败", e);
                speak("AI 生成失败：" + e.getMessage());
                isPlayingAIResponse = false;
            }
        });
    }
    
    /**
     * 尝试播放缓冲文本
     */
    private void tryPlayBuffer() {
        long currentTime = System.currentTimeMillis();
        
        // 检查播放间隔
        if (currentTime - lastPlayTime < PLAY_INTERVAL_MS) {
            return;
        }
        
        // 如果正在播放，等待
        if (isPlayingAIResponse && ttsEngine.isSpeaking()) {
            return;
        }
        
        // 播放缓冲文本
        playBufferedText();
    }
    
    /**
     * 播放缓冲文本
     */
    private void playBufferedText() {
        if (currentTextBuilder.length() == 0) {
            return;
        }
        
        String textToPlay = currentTextBuilder.toString();
        currentTextBuilder.setLength(0);
        
        speak(textToPlay);
        lastPlayTime = System.currentTimeMillis();
    }
    
    /**
     * 播放 AI 回答（完整）
     * 
     * @param prompt 用户问题
     * @param response AI 回答
     */
    public void playAIResponse(String prompt, String response) {
        if (!ttsEnabled) {
            Log.w(TAG, "TTS 已禁用，跳过播放");
            return;
        }
        
        Log.d(TAG, "播放完整 AI 回答：" + response.length() + " 字符");
        
        // 分段播放（避免单次播放过长）
        String[] segments = splitTextIntoSegments(response);
        
        for (String segment : segments) {
            speak(segment);
        }
    }
    
    /**
     * 分割文本为合适的播放片段
     * 
     * @param text 完整文本
     * @return 分割后的文本数组
     */
    private String[] splitTextIntoSegments(String text) {
        // 按句子分割（句号、问号、感叹号）
        String[] sentences = text.split("(?<=[。！？.!?])\\s*");
        
        // 如果句子太长，进一步分割
        java.util.List<String> segments = new java.util.ArrayList<>();
        for (String sentence : sentences) {
            if (sentence.length() > 200) {
                // 按逗号分割
                String[] subSentences = sentence.split("(?<=[,])\\s*");
                for (String sub : subSentences) {
                    if (!sub.trim().isEmpty()) {
                        segments.add(sub.trim());
                    }
                }
            } else if (!sentence.trim().isEmpty()) {
                segments.add(sentence.trim());
            }
        }
        
        return segments.toArray(new String[0]);
    }
    
    /**
     * 播放文本
     * 
     * @param text 要播放的文本
     */
    public void speak(String text) {
        if (ttsEngine != null && ttsEngine.isAvailable()) {
            ttsEngine.speak(text);
        } else {
            Log.w(TAG, "TTS 不可用：" + text);
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        if (ttsEngine != null) {
            ttsEngine.stop();
            currentTextBuilder.setLength(0);
            isPlayingAIResponse = false;
            Log.d(TAG, "TTS 播放已停止");
        }
    }
    
    /**
     * 设置语速
     * 
     * @param rate 语速 (0.5 - 2.0)
     */
    public void setSpeechRate(float rate) {
        if (ttsEngine != null) {
            ttsEngine.setSpeechRate(rate);
        }
    }
    
    /**
     * 设置音调
     * 
     * @param pitch 音调 (0.5 - 2.0)
     */
    public void setPitch(float pitch) {
        if (ttsEngine != null) {
            ttsEngine.setPitch(pitch);
        }
    }
    
    /**
     * 获取语速
     * 
     * @return 语速值
     */
    public float getSpeechRate() {
        return ttsEngine != null ? ttsEngine.getSpeechRate() : 1.0f;
    }
    
    /**
     * 获取音调
     * 
     * @return 音调值
     */
    public float getPitch() {
        return ttsEngine != null ? ttsEngine.getPitch() : 1.0f;
    }
    
    /**
     * 检查 TTS 是否可用
     * 
     * @return TTS 是否可用
     */
    public boolean isTTSAvailable() {
        return ttsEngine != null && ttsEngine.isAvailable();
    }
    
    /**
     * 检查是否正在播放
     * 
     * @return 是否正在播放
     */
    public boolean isSpeaking() {
        return ttsEngine != null && ttsEngine.isSpeaking();
    }
    
    /**
     * 获取队列中的文本数量
     * 
     * @return 队列长度
     */
    public int getQueueSize() {
        return ttsEngine != null ? ttsEngine.getQueueSize() : 0;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        Log.i(TAG, "释放 TTS 管理器资源");
        
        if (ttsEngine != null) {
            ttsEngine.release();
            ttsEngine = null;
        }
        
        instance = null;
        
        Log.i(TAG, "TTS 管理器资源已释放");
    }
}
