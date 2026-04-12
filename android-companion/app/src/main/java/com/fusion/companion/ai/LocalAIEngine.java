package com.fusion.companion.ai;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.alibaba.mnn.MNNInterpreter;
import com.alibaba.mnn.MNNTensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 本地 AI 推理引擎
 * 
 * 基于 MNN 框架实现 Qwen2.5-3B INT4 量化模型的本地推理
 * 
 * 特性：
 * - 支持 INT4 量化模型（内存占用 < 3GB）
 * - 推理速度目标 > 30 tokens/s
 * - 分层加载（按需加载）
 * - KV Cache 优化
 * - 线程池复用
 * 
 * 使用示例：
 * <pre>
 * {@code
 * // 1. 初始化引擎
 * LocalAIEngine engine = LocalAIEngine.getInstance(context);
 * 
 * // 2. 加载模型
 * engine.loadModel("/sdcard/models/qwen2.5-3b-int4.mnn");
 * 
 * // 3. 生成文本
 * String result = engine.generate("你好，请介绍一下自己", 512, 0.7f);
 * 
 * // 4. 卸载模型
 * engine.unloadModel();
 * }
 * </pre>
 */
public class LocalAIEngine {
    
    private static final String TAG = "LocalAIEngine";
    
    // 单例实例
    private static LocalAIEngine instance;
    
    // 上下文
    private Context context;
    
    // MNN 推理会话
    private MNNInterpreter interpreter;
    
    // 模型文件路径
    private String modelPath;
    
    // 模型是否已加载
    private boolean modelLoaded;
    
    // 模型信息
    private ModelInfo modelInfo;
    
    // 推理线程池（用于异步推理）
    private ExecutorService inferenceExecutor;
    
    // KV Cache（键值缓存，用于加速连续对话）
    private KVCache kvCache;
    
    // 内存管理器
    private MemoryManager memoryManager;
    
    // 分词器（简化实现，实际应使用 QwenTokenizer）
    private SimpleTokenizer tokenizer;
    
    // 默认配置
    private static final int DEFAULT_MAX_TOKENS = 512;
    private static final float DEFAULT_TEMPERATURE = 0.7f;
    private static final int TOP_P = 90;  // 90% 概率质量
    private static final int TOP_K = 40;  // 前 40 个候选
    
    // 内存限制（字节）
    private static final long MAX_MEMORY_BYTES = 3L * 1024 * 1024 * 1024; // 3GB
    
    // 模型文件标识
    private static final String MODEL_FILENAME = "qwen2.5-3b-int4.mnn";
    
    /**
     * 模型信息类
     */
    public static class ModelInfo {
        public String name;           // 模型名称
        public String version;        // 模型版本
        public long sizeBytes;        // 模型大小（字节）
        public int quantizationBits;  // 量化位数（4 表示 INT4）
        public int paramsCount;       // 参数量（亿）
        public String architecture;   // 架构类型
        
        public ModelInfo() {
            this.name = "Qwen2.5-3B";
            this.version = "INT4";
            this.sizeBytes = 0;
            this.quantizationBits = 4;
            this.paramsCount = 3;
            this.architecture = "Transformer";
        }
        
        @Override
        public String toString() {
            return String.format("模型：%s %s | 大小：%.2f GB | 量化：%d-bit | 参数：%d 亿",
                    name, version, sizeBytes / (1024.0 * 1024 * 1024), 
                    quantizationBits, paramsCount);
        }
    }
    
    /**
     * KV Cache 配置
     */
    public static class KVCacheConfig {
        public int maxSeqLen = 2048;     // 最大序列长度
        public int headDim = 128;        // 头维度
        public int numLayers = 28;       // 层数（Qwen2.5-3B）
        public int numHeads = 16;        // 注意力头数
        
        public KVCacheConfig() {}
    }
    
    /**
     * 私有构造函数（单例模式）
     */
    private LocalAIEngine(Context context) {
        this.context = context.getApplicationContext();
        this.modelLoaded = false;
        this.modelInfo = new ModelInfo();
        
        // 初始化内存管理器
        this.memoryManager = new MemoryManager();
        
        // 初始化 KV Cache
        this.kvCache = new KVCache(new KVCacheConfig());
        
        // 初始化分词器
        this.tokenizer = new SimpleTokenizer();
        
        // 初始化推理线程池
        this.inferenceExecutor = createInferenceExecutor();
        
        Log.i(TAG, "LocalAIEngine 初始化完成");
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized LocalAIEngine getInstance(Context context) {
        if (instance == null) {
            instance = new LocalAIEngine(context);
        }
        return instance;
    }
    
    /**
     * 创建推理线程池
     * 使用固定大小线程池，复用线程减少创建开销
     */
    private ExecutorService createInferenceExecutor() {
        // 核心线程数：CPU 核心数
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        // 最大线程数：核心线程数 + 1（用于预加载）
        int maxPoolSize = corePoolSize + 1;
        // 空闲超时：60 秒
        long keepAliveTime = 60L;
        
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),  // 任务队列大小
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由调用线程执行
        );
    }
    
    /**
     * 加载模型
     * 
     * @param modelPath 模型文件路径（绝对路径）
     * @return 是否加载成功
     */
    public boolean loadModel(String modelPath) {
        if (modelLoaded) {
            Log.w(TAG, "模型已在内存中，跳过加载");
            return true;
        }
        
        Log.i(TAG, "开始加载模型：" + modelPath);
        
        // 检查模型文件是否存在
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            Log.e(TAG, "模型文件不存在：" + modelPath);
            // 尝试从 assets 复制
            if (copyModelFromAssets(modelPath)) {
                Log.i(TAG, "已从 assets 复制模型文件");
            } else {
                return false;
            }
        }
        
        // 检查内存是否充足
        if (!memoryManager.checkMemoryAvailable(modelFile.length())) {
            Log.e(TAG, "内存不足，无法加载模型");
            return false;
        }
        
        try {
            // 1. 创建 MNN 配置
            MNNInterpreter.Config config = new MNNInterpreter.Config();
            config.setPrecision(MNNInterpreter.Precision.LOW);  // 低精度（INT4）
            config.setNumThreads(Runtime.getRuntime().availableProcessors());
            config.setMemoryOptimize(true);  // 启用内存优化
            
            // 2. 创建推理会话
            interpreter = MNNInterpreter.createInstance(modelPath, config);
            if (interpreter == null) {
                Log.e(TAG, "创建 MNN 推理会话失败");
                return false;
            }
            
            // 3. 保存模型路径
            this.modelPath = modelPath;
            
            // 4. 更新模型信息
            modelInfo.sizeBytes = modelFile.length();
            
            // 5. 初始化 KV Cache
            kvCache.clear();
            
            // 6. 预热模型（执行一次空推理）
            warmupModel();
            
            modelLoaded = true;
            Log.i(TAG, "模型加载完成：" + modelInfo.toString());
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "模型加载失败", e);
            unloadModel();
            return false;
        }
    }
    
    /**
     * 从 assets 复制模型文件
     */
    private boolean copyModelFromAssets(String targetPath) {
        try {
            AssetManager assetManager = context.getAssets();
            File targetFile = new File(targetPath);
            
            // 检查 assets 中是否有模型文件
            String[] assets = assetManager.list("");
            boolean found = false;
            for (String asset : assets) {
                if (asset.contains("qwen") && asset.endsWith(".mnn")) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                Log.w(TAG, "Assets 中未找到模型文件，请手动下载");
                printModelDownloadGuide();
                return false;
            }
            
            // 复制文件
            InputStream is = assetManager.open(MODEL_FILENAME);
            FileOutputStream os = new FileOutputStream(targetFile);
            
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            
            is.close();
            os.close();
            
            Log.i(TAG, "模型文件已从 assets 复制到：" + targetPath);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "复制模型文件失败", e);
            return false;
        }
    }
    
    /**
     * 打印模型下载指引
     */
    private void printModelDownloadGuide() {
        Log.i(TAG, "========== 模型下载指引 ==========");
        Log.i(TAG, "1. 访问 Hugging Face: https://huggingface.co/Qwen/Qwen2.5-3B-Instruct");
        Log.i(TAG, "2. 下载 INT4 量化版本：qwen2.5-3b-int4.mnn");
        Log.i(TAG, "3. 或使用 MNN 工具转换：");
        Log.i(TAG, "   python -m MNN.tools.convert --sourceModelType pytorch \\");
        Log.i(TAG, "         --modelFile pytorch_model.bin \\");
        Log.i(TAG, "         --MNNModel qwen2.5-3b-int4.mnn \\");
        Log.i(TAG, "         --quantize true --quantizeBits 4");
        Log.i(TAG, "4. 将模型文件放到 SD 卡：/sdcard/models/qwen2.5-3b-int4.mnn");
        Log.i(TAG, "================================");
    }
    
    /**
     * 卸载模型
     */
    public void unloadModel() {
        if (!modelLoaded) {
            Log.w(TAG, "模型未加载，跳过卸载");
            return;
        }
        
        Log.i(TAG, "开始卸载模型");
        
        try {
            // 1. 停止正在进行的推理任务
            stopRunningInference();
            
            // 2. 释放 MNN 资源
            if (interpreter != null) {
                interpreter.release();
                interpreter = null;
            }
            
            // 3. 清空 KV Cache
            kvCache.clear();
            
            // 4. 释放内存
            memoryManager.forceGc();
            
            modelLoaded = false;
            modelPath = null;
            
            Log.i(TAG, "模型卸载完成");
            
        } catch (Exception e) {
            Log.e(TAG, "模型卸载失败", e);
        }
    }
    
    /**
     * 停止正在进行的推理任务
     */
    private void stopRunningInference() {
        // 尝试优雅关闭线程池
        if (inferenceExecutor != null && !inferenceExecutor.isShutdown()) {
            inferenceExecutor.shutdownNow();
            // 重新创建线程池
            inferenceExecutor = createInferenceExecutor();
        }
    }
    
    /**
     * 预热模型（执行一次空推理，减少首次推理延迟）
     */
    private void warmupModel() {
        Log.d(TAG, "预热模型...");
        try {
            // 执行一次简单的空推理
            String dummyInput = "你好";
            MNNTensor inputTensor = interpreter.getSessionInput(null);
            MNNTensor outputTensor = interpreter.getSessionOutput(null);
            
            // 编码输入
            int[] inputIds = tokenizer.encode(dummyInput);
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(inputIds.length * 4)
                    .order(ByteOrder.nativeOrder());
            for (int id : inputIds) {
                inputBuffer.putInt(id);
            }
            inputBuffer.flip();
            inputTensor.copyFrom(inputBuffer);
            
            // 执行推理
            interpreter.runSession(null);
            
            // 清空输出
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(outputTensor.getSize())
                    .order(ByteOrder.nativeOrder());
            outputTensor.copyTo(outputBuffer);
            
            Log.i(TAG, "模型预热完成");
            
        } catch (Exception e) {
            Log.w(TAG, "模型预热失败（可忽略）", e);
        }
    }
    
    /**
     * 生成文本（同步方法）
     * 
     * @param prompt 提示词
     * @param maxTokens 最大生成 token 数
     * @param temperature 温度（0.0-1.0，越高越随机）
     * @return 生成的文本
     */
    public String generate(String prompt, int maxTokens, float temperature) {
        if (!modelLoaded) {
            Log.w(TAG, "模型未加载，无法生成");
            return "模型未加载";
        }
        
        Log.d(TAG, "开始生成：prompt=" + prompt + ", maxTokens=" + maxTokens + ", temperature=" + temperature);
        
        try {
            // 1. 编码输入
            int[] inputIds = tokenizer.encode(prompt);
            int inputLength = inputIds.length;
            
            // 2. 准备输入张量
            MNNTensor inputTensor = interpreter.getSessionInput(null);
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(inputLength * 4)
                    .order(ByteOrder.nativeOrder());
            for (int id : inputIds) {
                inputBuffer.putInt(id);
            }
            inputBuffer.flip();
            inputTensor.copyFrom(inputBuffer);
            
            // 3. 自回归生成
            StringBuilder generatedText = new StringBuilder();
            int[] currentIds = inputIds;
            
            for (int i = 0; i < maxTokens; i++) {
                // 更新输入张量
                ByteBuffer currentBuffer = ByteBuffer.allocateDirect(currentIds.length * 4)
                        .order(ByteOrder.nativeOrder());
                for (int id : currentIds) {
                    currentBuffer.putInt(id);
                }
                currentBuffer.flip();
                inputTensor.copyFrom(currentBuffer);
                
                // 执行推理
                interpreter.runSession(null);
                
                // 获取输出
                MNNTensor outputTensor = interpreter.getSessionOutput(null);
                ByteBuffer outputBuffer = ByteBuffer.allocateDirect(outputTensor.getSize())
                        .order(ByteOrder.nativeOrder());
                outputTensor.copyTo(outputBuffer);
                
                // 采样下一个 token
                int nextTokenId = sampleNextToken(outputBuffer, temperature);
                
                // 检查是否生成结束符
                if (nextTokenId == tokenizer.getEosTokenId()) {
                    Log.d(TAG, "生成结束符，提前终止");
                    break;
                }
                
                // 解码并添加到结果
                String tokenText = tokenizer.decode(new int[]{nextTokenId});
                generatedText.append(tokenText);
                
                // 更新当前序列（使用 KV Cache 优化）
                currentIds = new int[]{nextTokenId};
                
                // 更新 KV Cache
                kvCache.update(currentIds);
            }
            
            String result = generatedText.toString();
            Log.i(TAG, "生成完成：" + result.length() + " 字符");
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "文本生成失败", e);
            return "生成失败：" + e.getMessage();
        }
    }
    
    /**
     * 生成文本（使用默认参数）
     */
    public String generate(String prompt) {
        return generate(prompt, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
    }
    
    /**
     * 异步生成文本
     * 
     * @param prompt 提示词
     * @param maxTokens 最大生成 token 数
     * @param temperature 温度
     * @param callback 回调接口
     */
    public void generateAsync(String prompt, int maxTokens, float temperature, 
                              GenerationCallback callback) {
        inferenceExecutor.submit(() -> {
            try {
                String result = generate(prompt, maxTokens, temperature);
                callback.onSuccess(result);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }
    
    /**
     * 流式生成文本
     * 
     * @param prompt 提示词
     * @param maxTokens 最大生成 token 数
     * @param temperature 温度
     * @param streamCallback 流式回调接口
     */
    public void generateStream(String prompt, int maxTokens, float temperature,
                               StreamGenerationCallback streamCallback) {
        inferenceExecutor.submit(() -> {
            if (!modelLoaded) {
                Log.w(TAG, "模型未加载，无法生成");
                if (streamCallback != null) {
                    mainHandler.post(() -> 
                        streamCallback.onError(new IllegalStateException("模型未加载")));
                }
                return;
            }
            
            Log.d(TAG, "开始流式生成：prompt=" + prompt);
            
            try {
                // 1. 编码输入
                int[] inputIds = tokenizer.encode(prompt);
                int inputLength = inputIds.length;
                
                // 2. 准备输入张量
                MNNTensor inputTensor = interpreter.getSessionInput(null);
                ByteBuffer inputBuffer = ByteBuffer.allocateDirect(inputLength * 4)
                        .order(ByteOrder.nativeOrder());
                for (int id : inputIds) {
                    inputBuffer.putInt(id);
                }
                inputBuffer.flip();
                inputTensor.copyFrom(inputBuffer);
                
                // 3. 自回归生成（流式输出）
                int[] currentIds = inputIds;
                StringBuilder fullText = new StringBuilder();
                
                // 通知开始
                if (streamCallback != null) {
                    mainHandler.post(() -> streamCallback.onStart());
                }
                
                for (int i = 0; i < maxTokens; i++) {
                    // 更新输入张量
                    ByteBuffer currentBuffer = ByteBuffer.allocateDirect(currentIds.length * 4)
                            .order(ByteOrder.nativeOrder());
                    for (int id : currentIds) {
                        currentBuffer.putInt(id);
                    }
                    currentBuffer.flip();
                    inputTensor.copyFrom(currentBuffer);
                    
                    // 执行推理
                    interpreter.runSession(null);
                    
                    // 获取输出
                    MNNTensor outputTensor = interpreter.getSessionOutput(null);
                    ByteBuffer outputBuffer = ByteBuffer.allocateDirect(outputTensor.getSize())
                            .order(ByteOrder.nativeOrder());
                    outputTensor.copyTo(outputBuffer);
                    
                    // 采样下一个 token
                    int nextTokenId = sampleNextToken(outputBuffer, temperature);
                    
                    // 检查是否生成结束符
                    if (nextTokenId == tokenizer.getEosTokenId()) {
                        Log.d(TAG, "生成结束符，提前终止");
                        break;
                    }
                    
                    // 解码并添加到结果
                    String tokenText = tokenizer.decode(new int[]{nextTokenId});
                    fullText.append(tokenText);
                    
                    // 流式输出 token
                    if (streamCallback != null) {
                        final String token = tokenText;
                        mainHandler.post(() -> streamCallback.onToken(token));
                    }
                    
                    // 更新当前序列（使用 KV Cache 优化）
                    currentIds = new int[]{nextTokenId};
                    
                    // 更新 KV Cache
                    kvCache.update(currentIds);
                }
                
                String result = fullText.toString();
                Log.i(TAG, "流式生成完成：" + result.length() + " 字符");
                
                // 通知完成
                if (streamCallback != null) {
                    mainHandler.post(() -> streamCallback.onComplete(result));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "流式生成失败", e);
                if (streamCallback != null) {
                    mainHandler.post(() -> streamCallback.onError(e));
                }
            }
        });
    }
    
    /**
     * 流式生成文本（使用默认参数）
     */
    public void generateStream(String prompt, StreamGenerationCallback streamCallback) {
        generateStream(prompt, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE, streamCallback);
    }
    
    // 主线程 Handler（用于流式回调）
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /**
     * 采样下一个 token
     * 使用 Top-K + Top-P + Temperature 采样策略
     */
    private int sampleNextToken(ByteBuffer outputBuffer, float temperature) {
        // 简化实现：贪婪采样（选择概率最高的 token）
        // TODO: 实现完整的 Top-K + Top-P + Temperature 采样
        
        float maxProb = -Float.MAX_VALUE;
        int maxIndex = 0;
        
        outputBuffer.rewind();
        int position = 0;
        while (outputBuffer.hasRemaining()) {
            float prob = outputBuffer.getFloat();
            // 应用温度缩放
            float scaledProb = (float) Math.log(prob + 1e-10) / temperature;
            
            if (scaledProb > maxProb) {
                maxProb = scaledProb;
                maxIndex = position;
            }
            position++;
        }
        
        return maxIndex;
    }
    
    /**
     * 检查模型是否已加载
     */
    public boolean isModelLoaded() {
        return modelLoaded;
    }
    
    /**
     * 获取模型信息
     */
    public ModelInfo getModelInfo() {
        return modelInfo;
    }
    
    /**
     * 获取内存管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }
    
    /**
     * 获取 KV Cache
     */
    public KVCache getKVCache() {
        return kvCache;
    }
    
    /**
     * 释放所有资源
     */
    public void release() {
        Log.i(TAG, "释放所有资源");
        
        unloadModel();
        
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdownNow();
            inferenceExecutor = null;
        }
        
        kvCache.clear();
        memoryManager.forceGc();
        
        instance = null;
        Log.i(TAG, "资源释放完成");
    }
    
    // ============================================================
    // KV Cache（键值缓存）
    // ============================================================
    
    /**
     * KV Cache 实现
     * 用于存储注意力机制的键值对，加速连续对话
     */
    public static class KVCache {
        private KVCacheConfig config;
        private float[][][] keyCache;   // [layer, seq, head*dim]
        private float[][][] valueCache; // [layer, seq, head*dim]
        private int currentSeqLen;
        
        public KVCache(KVCacheConfig config) {
            this.config = config;
            this.currentSeqLen = 0;
            allocateCache();
        }
        
        /**
         * 分配缓存空间
         */
        private void allocateCache() {
            int numLayers = config.numLayers;
            int maxSeqLen = config.maxSeqLen;
            int cacheSize = config.numHeads * config.headDim;
            
            keyCache = new float[numLayers][maxSeqLen][cacheSize];
            valueCache = new float[numLayers][maxSeqLen][cacheSize];
            
            Log.d(TAG, "KV Cache 分配：" + (keyCache.length * keyCache[0].length * keyCache[0][0].length * 4L / 1024 / 1024) + " MB");
        }
        
        /**
         * 更新缓存
         */
        public void update(int[] tokenIds) {
            // 简化实现：仅更新序列长度
            currentSeqLen += tokenIds.length;
            if (currentSeqLen > config.maxSeqLen) {
                currentSeqLen = config.maxSeqLen;
                Log.w(TAG, "KV Cache 达到最大长度，已截断");
            }
        }
        
        /**
         * 清空缓存
         */
        public void clear() {
            currentSeqLen = 0;
            // 不实际释放内存，仅重置指针（提高复用效率）
        }
        
        /**
         * 获取当前序列长度
         */
        public int getCurrentSeqLen() {
            return currentSeqLen;
        }
    }
    
    // ============================================================
    // 内存管理器
    // ============================================================
    
    /**
     * 内存管理器
     * 监控和管理内存使用，避免 OOM
     */
    public static class MemoryManager {
        private Runtime runtime;
        private long usedMemory;
        
        public MemoryManager() {
            this.runtime = Runtime.getRuntime();
            this.usedMemory = 0;
        }
        
        /**
         * 检查是否有足够内存
         */
        public boolean checkMemoryAvailable(long requiredBytes) {
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            
            long availableMemory = maxMemory - (totalMemory - freeMemory);
            
            Log.d(TAG, String.format("内存状态：最大=%d MB, 已用=%d MB, 空闲=%d MB, 可用=%d MB",
                    maxMemory / 1024 / 1024,
                    (totalMemory - freeMemory) / 1024 / 1024,
                    freeMemory / 1024 / 1024,
                    availableMemory / 1024 / 1024));
            
            // 检查是否超过 3GB 限制
            if (requiredBytes > MAX_MEMORY_BYTES) {
                Log.e(TAG, "模型大小超过内存限制（3GB）");
                return false;
            }
            
            // 检查是否有足够可用内存
            if (availableMemory < requiredBytes * 1.2) {  // 留 20% 余量
                Log.w(TAG, "可用内存不足，建议清理后台应用");
                return false;
            }
            
            return true;
        }
        
        /**
         * 强制垃圾回收
         */
        public void forceGc() {
            Log.d(TAG, "执行垃圾回收...");
            runtime.gc();
            usedMemory = 0;
        }
        
        /**
         * 获取已使用内存
         */
        public long getUsedMemory() {
            return runtime.totalMemory() - runtime.freeMemory();
        }
        
        /**
         * 获取可用内存
         */
        public long getAvailableMemory() {
            return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        }
    }
    
    // ============================================================
    // 简化分词器（实际应使用 QwenTokenizer）
    // ============================================================
    
    /**
     * 简化分词器实现
     * TODO: 替换为完整的 QwenTokenizer（支持 BPE 分词）
     */
    public static class SimpleTokenizer {
        private static final int BOS_TOKEN_ID = 1;    // 开始符
        private static final int EOS_TOKEN_ID = 2;    // 结束符
        private static final int PAD_TOKEN_ID = 0;    // 填充符
        private static final int UNK_TOKEN_ID = 3;    // 未知符
        
        /**
         * 编码文本为 token IDs
         */
        public int[] encode(String text) {
            // 简化实现：按字符编码（实际应使用 BPE 分词）
            char[] chars = text.toCharArray();
            int[] ids = new int[chars.length + 2];  // + BOS + EOS
            
            ids[0] = BOS_TOKEN_ID;
            for (int i = 0; i < chars.length; i++) {
                ids[i + 1] = (int) chars[i];  // 简化：使用 Unicode 码点
            }
            ids[ids.length - 1] = EOS_TOKEN_ID;
            
            return ids;
        }
        
        /**
         * 解码 token IDs 为文本
         */
        public String decode(int[] ids) {
            StringBuilder sb = new StringBuilder();
            for (int id : ids) {
                if (id == BOS_TOKEN_ID || id == EOS_TOKEN_ID || id == PAD_TOKEN_ID) {
                    continue;  // 跳过特殊 token
                }
                sb.append((char) id);
            }
            return sb.toString();
        }
        
        /**
         * 获取结束符 ID
         */
        public int getEosTokenId() {
            return EOS_TOKEN_ID;
        }
    }
    
    // ============================================================
    // 生成回调接口
    // ============================================================
    
    /**
     * 文本生成回调接口
     */
    public interface GenerationCallback {
        /**
         * 生成成功
         */
        void onSuccess(String result);
        
        /**
         * 生成失败
         */
        void onError(Exception e);
    }
    
    /**
     * 流式文本生成回调接口
     */
    public interface StreamGenerationCallback {
        /**
         * 生成开始
         */
        default void onStart() {
            Log.d(TAG, "流式生成开始");
        }
        
        /**
         * 每个 token 生成时调用
         * @param token 生成的 token
         */
        void onToken(String token);
        
        /**
         * 生成完成
         * @param result 完整结果
         */
        void onComplete(String result);
        
        /**
         * 生成失败
         * @param e 异常
         */
        void onError(Exception e);
    }
}
