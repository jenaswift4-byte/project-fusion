package com.fusion.companion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fusion.companion.llm.LLMEngine;
import com.fusion.companion.llm.LLMEngineSimple;
import com.fusion.companion.llm.NexaEngine;
import com.fusion.companion.log.LogDBHelper;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LLM 推理服务
 * 负责：
 * 1. 文本总结（ASR 转录内容）
 * 2. 图像/视频分析
 * 3. 自动定时总结
 */
public class LLMService extends Service {
    private static final String TAG = "LLMService";
    private static final String CHANNEL_ID = "llm_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    private LLMEngine llmEngine;  // 使用接口类型
    private ExecutorService executor;
    private Handler mainHandler;
    private boolean isRunning = false;

    // 定时总结间隔（毫秒）
    private static final long SUMMARY_INTERVAL = 10 * 60 * 1000; // 10 分钟

    // 默认 GGUF 模型路径（手机内部存储）
    private static final String DEFAULT_MODEL_DIR = "/models/qwen/";
    private static final String DEFAULT_MODEL_FILE = "qwen3-0.6b-q4_k_m.gguf";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "LLM 服务创建");

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 立即启动前台服务（Android 8.0+ 要求 5 秒内）
        startForegroundService();
    }

    /**
     * 启动前台服务
     */
    private void startForegroundService() {
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("LLM 服务")
                .setContentText("智能助手运行中")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        Log.i(TAG, "✓ 前台服务已启动");
    }

    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "LLM 服务",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("智能助手后台服务");
            channel.setSound(null, null); // 静音

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
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
                
            case "infer":
                String inferText = intent.getStringExtra("text");
                inferText(inferText);
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
     *
     * 优先级：
     * 1. Nexa SDK (真实 LLM 推理) — 需要模型文件存在
     * 2. LLMEngineSimple (关键词提取) — 无需模型，始终可用
     */
    private void initLLM() {
        executor.execute(() -> {
            try {
                // Step 1: 检查是否有可用的 GGUF 模型文件
                String modelPath = findModelPath();
                boolean modelExists = modelPath != null;

                if (modelExists) {
                    Log.i(TAG, "发现模型文件: " + modelPath);

                    // Step 2: 尝试初始化 Nexa SDK
                    NexaEngine nexaEngine = new NexaEngine(this);
                    boolean nexaSuccess = nexaEngine.initialize(modelPath);

                    if (nexaSuccess) {
                        this.llmEngine = nexaEngine;
                        Log.i(TAG, "✓ Nexa SDK 初始化成功 (" + nexaEngine.getEngineInfo() + ")");
                        notifyEngineReady(nexaEngine.getEngineInfo());
                        return;
                    } else {
                        Log.w(TAG, "Nexa SDK 初始化失败，回退到简化版引擎");
                    }
                } else {
                    Log.i(TAG, "未发现模型文件，使用简化版引擎");
                    Log.i(TAG, "  提示: 将 GGUF 模型放到 " + getFilesDir() + DEFAULT_MODEL_DIR + DEFAULT_MODEL_FILE);
                    Log.i(TAG, "  推荐模型: Qwen3-0.6B-Q4_K_M (~400MB)");
                    Log.i(TAG, "  下载: https://huggingface.co/Qwen/Qwen3-0.6B-GGUF");
                }

                // Step 3: 回退到简化版引擎
                this.llmEngine = new LLMEngineSimple();
                ((LLMEngineSimple) this.llmEngine).loadModel(null);
                Log.i(TAG, "✓ 简化版引擎初始化成功（关键词提取模式）");
                notifyEngineReady("LLMEngineSimple (关键词提取)");

            } catch (Exception e) {
                Log.e(TAG, "LLM 初始化异常", e);
                // 最终回退
                this.llmEngine = new LLMEngineSimple();
                ((LLMEngineSimple) this.llmEngine).loadModel(null);
            }
        });
    }

    /**
     * 同步初始化 LLM（阻塞当前线程直到完成）
     * 用于推理前确保引擎已就绪
     */
    private void initLLMSync() {
        try {
            String modelPath = findModelPath();
            boolean modelExists = modelPath != null;

            if (modelExists) {
                Log.i(TAG, "同步初始化: 发现模型文件: " + modelPath);
                NexaEngine nexaEngine = new NexaEngine(this);
                boolean nexaSuccess = nexaEngine.initialize(modelPath);

                if (nexaSuccess) {
                    this.llmEngine = nexaEngine;
                    Log.i(TAG, "✓ Nexa SDK 同步初始化成功");
                    notifyEngineReady(nexaEngine.getEngineInfo());
                    return;
                } else {
                    Log.w(TAG, "Nexa SDK 同步初始化失败，回退到简化版引擎");
                }
            } else {
                Log.i(TAG, "同步初始化: 未发现模型文件");
            }

            // 回退到简化版引擎
            this.llmEngine = new LLMEngineSimple();
            ((LLMEngineSimple) this.llmEngine).loadModel(null);
            Log.i(TAG, "✓ 简化版引擎初始化成功（关键词提取模式）");
            notifyEngineReady("LLMEngineSimple (关键词提取)");

        } catch (Exception e) {
            Log.e(TAG, "LLM 同步初始化异常", e);
            this.llmEngine = new LLMEngineSimple();
            ((LLMEngineSimple) this.llmEngine).loadModel(null);
        }
    }

    /**
     * 查找可用的 GGUF 模型文件路径
     *
     * 搜索顺序：
     * 1. 内部存储 /models/qwen/*.gguf
     * 2. 外部存储 /sdcard/models/qwen/*.gguf
     * 3. 内部存储其他位置
     */
    private String findModelPath() {
        // 位置 1: 内部存储默认路径
        String internalPath = getFilesDir() + DEFAULT_MODEL_DIR + DEFAULT_MODEL_FILE;
        if (new File(internalPath).exists()) {
            return internalPath;
        }

        // 位置 2: 搜索内部存储 /models/ 下的所有 .gguf 文件
        File modelsDir = new File(getFilesDir(), "models");
        if (modelsDir.exists() && modelsDir.isDirectory()) {
            String ggufPath = findGgufInDirectory(modelsDir);
            if (ggufPath != null) return ggufPath;
        }

        // 位置 3: 外部存储
        File externalDir = new File("/sdcard/models");
        if (externalDir.exists() && externalDir.isDirectory()) {
            String ggufPath = findGgufInDirectory(externalDir);
            if (ggufPath != null) return ggufPath;
        }

        return null;
    }

    /**
     * 在目录中递归查找 .gguf 文件
     */
    private String findGgufInDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".gguf")) {
                return file.getAbsolutePath();
            }
            if (file.isDirectory()) {
                String found = findGgufInDirectory(file);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 通知引擎就绪（通过 MQTT 广播）
     */
    private void notifyEngineReady(String engineInfo) {
        mainHandler.post(() -> {
            isRunning = true;
        });
        // TODO: 通过 MQTT 发布 llm/engine_ready 事件
    }
    
    /**
     * 总结文本
     */
    private void inferText(String text) {
        if (text == null || text.isEmpty()) {
            Log.w(TAG, "推理文本为空，跳过");
            return;
        }
        
        if (llmEngine == null || !llmEngine.isInitialized()) {
            Log.w(TAG, "LLM 引擎未初始化，同步初始化...");
            initLLMSync();
        }
        
        if (llmEngine == null || !llmEngine.isInitialized()) {
            Log.e(TAG, "LLM 引擎初始化失败，无法推理");
            publishLLMResult("error", "LLM engine not initialized");
            return;
        }
        
        String finalText = text;
        executor.execute(() -> {
            try {
                Log.i(TAG, "开始推理: " + finalText.substring(0, Math.min(50, finalText.length())));
                long start = System.currentTimeMillis();
                String result = llmEngine.inferText(finalText, 256);
                long elapsed = System.currentTimeMillis() - start;
                
                if (result != null && !result.isEmpty()) {
                    Log.i(TAG, "✓ 推理完成 (" + elapsed + "ms): " + result.substring(0, Math.min(100, result.length())));
                    publishLLMResult("infer", result);
                } else {
                    Log.w(TAG, "推理结果为空 (" + elapsed + "ms)");
                    publishLLMResult("infer", "");
                }
            } catch (Exception e) {
                Log.e(TAG, "推理异常: " + e.getMessage());
                publishLLMResult("error", e.getMessage());
            }
        });
    }
    
    private void publishLLMResult(String type, String result) {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("type", type);
            json.put("result", result);
            json.put("timestamp", System.currentTimeMillis());
            
            // 通过本地 MQTT Broker 发布结果
            try {
                String brokerUrl = "tcp://127.0.0.1:1883";
                String clientId = "llm_service_" + System.currentTimeMillis();
                MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setConnectionTimeout(5);
                client.connect(opts);
                client.publish("llm/response", json.toString().getBytes(), 0, false);
                client.disconnect();
                Log.d(TAG, "LLM 结果已发布到 llm/response");
            } catch (Exception e) {
                Log.w(TAG, "MQTT 发布失败: " + e.getMessage() + " — 结果: " + result.substring(0, Math.min(80, result.length())));
            }
        } catch (Exception e) {
            Log.e(TAG, "发布 LLM 结果失败: " + e.getMessage());
        }
    }

    private void summarizeText(String text) {
        if (llmEngine == null || !llmEngine.isInitialized()) {
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
        if (llmEngine == null || !llmEngine.isInitialized()) {
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
        return "<|im_start|>system\n你是一个智能助手，负责总结和整理文本内容。请用简洁的中文总结。\n<|im_end|>\n" +
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
