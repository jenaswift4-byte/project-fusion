package com.fusion.companion.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.fusion.companion.llm.LLMEngine;
import com.fusion.companion.llm.LLMEngineSimple;
import com.fusion.companion.llm.NexaEngine;
import com.fusion.companion.log.LogDBHelper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LLM 推理服务
 * 负责：
 * 1. 文本总结（ASR 转录内容）
 * 2. 图像/视频分析
 */
public class LLMService extends Service {
    private static final String TAG = "LLMService";

    private LLMEngine llmEngine;  // 使用接口类型
    private ExecutorService executor;
    private Handler mainHandler;
    private boolean isRunning = false;

    // 定时总结间隔（毫秒）
    private static final long SUMMARY_INTERVAL = 10 * 60 * 1000; // 10 分钟

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "LLM 服务创建");

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }
        
        String action = intent.getStringExtra("action");
        Log.d(TAG, "收到命令: " + action);
        
        switch (action) {
            case "init":
                initLLM();
                break;
                
            case "summarize":
                String text = intent.getStringExtra("text");
                summarizeText(text);
                break;
                
            case "analyze_image":
                String imagePath = intent.getStringExtra("image_path");
                analyzeImage(imagePath);
                break;
                
            case "start_auto_summary":
                startAutoSummary();
                break;
                
            case "stop_auto_summary":
                stopAutoSummary();
                break;
        }
        
        return START_STICKY;
    }
    
    /**
     * 初始化 LLM 模型
     */
    private void initLLM() {
        executor.execute(() -> {
            try {
                // 尝试加载 Nexa SDK
                NexaEngine nexaEngine = new NexaEngine(this);
                boolean nexaSuccess = nexaEngine.initialize(null);

                if (nexaSuccess) {
                    // 使用 Nexa SDK
                    this.llmEngine = nexaEngine;
                    Log.i(TAG, "✓ Nexa SDK 初始化成功 (Qwen-3-VL, NPU加速)");
                } else {
                    // 回退到简化版引擎
                    this.llmEngine = new LLMEngineSimple();
                    ((LLMEngineSimple) this.llmEngine).loadModel(null);
                    Log.i(TAG, "✓ 简化版引擎初始化成功（关键词提取模式）");
                }

                mainHandler.post(() -> {
                    isRunning = true;
                });

            } catch (Exception e) {
                Log.e(TAG, "LLM 初始化异常", e);
            }
        });
    }
    
    /**
     * 总结文本
     */
    private void summarizeText(String text) {
        if (!llmEngine.isInitialized()) {
            Log.e(TAG, "模型未加载");
            return;
        }
        
        executor.execute(() -> {
            String prompt = buildSummaryPrompt(text);
            String result = llmEngine.inferText(prompt, 256);
            
            if (result != null && !result.isEmpty()) {
                // 存储总结结果
                LogDBHelper.getInstance(this).insertLog("llm_summary", "qwen", result);
                Log.i(TAG, "✓ 文本总结完成: " + result.substring(0, Math.min(100, result.length())));
            } else {
                Log.w(TAG, "文本总结结果为空");
            }
        });
    }
    
    /**
     * 分析图像
     */
    private void analyzeImage(String imagePath) {
        if (!llmEngine.isInitialized()) {
            Log.e(TAG, "模型未加载");
            return;
        }
        
        executor.execute(() -> {
            String prompt = "请描述这张图片的内容";
            String result = llmEngine.inferImage(prompt, imagePath, 128);
            
            if (result != null && !result.isEmpty()) {
                Log.i(TAG, "✓ 图像分析完成: " + result);
            } else {
                Log.w(TAG, "图像分析结果为空");
            }
        });
    }
    
    /**
     * 开始自动总结（定时分析 ASR 转录内容）
     */
    private void startAutoSummary() {
        Log.i(TAG, "启动自动总结（间隔: " + (SUMMARY_INTERVAL / 1000 / 60) + " 分钟）");
        
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    return;
                }
                
                // 获取最近的 ASR 转录文本
                // TODO: 从 SQLite 查询最近 10 分钟的 ASR 记录
                
                // 生成总结
                // summarizeText(asrText);
                
                // 继续定时任务
                mainHandler.postDelayed(this, SUMMARY_INTERVAL);
            }
        }, SUMMARY_INTERVAL);
    }
    
    /**
     * 停止自动总结
     */
    private void stopAutoSummary() {
        isRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "停止自动总结");
    }
    
    /**
     * 构建总结提示词
     */
    private String buildSummaryPrompt(String text) {
        return "<|im_start|>system\n你是一个智能助手，负责总结和整理文本内容。\n<|im_end|>\n" +
               "<|im_start|>user\n请总结以下内容的关键信息（不超过100字）：\n" + text + "\n<|im_end|>\n" +
               "<|im_start|>assistant\n";
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        if (llmEngine != null) {
            llmEngine.release();
        }
        
        if (executor != null) {
            executor.shutdown();
        }
        
        Log.i(TAG, "LLM 服务销毁");
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
