package com.fusion.companion.speaker;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Base64;
import android.util.Log;

import com.fusion.companion.audio.PcmDataListener;
import com.fusion.companion.audio.VADHelper;
import com.fusion.companion.log.LogDBHelper;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声纹识别 — Sherpa-onnx Real Implementation
 *
 * 工作流程:
 *   1. init() 从 assets 加载 SpeakerEmbeddingExtractor 模型
 *   2. enroll() 接收 PC 下发的声纹特征向量，存入 SpeakerEmbeddingManager
 *   3. onPcmData() 接收 AudioStreamer 的 PCM，VAD 检测人声后提取嵌入向量
 *   4. speakerSearch() 与已注册的说话人比对，余弦相似度 > 阈值则识别成功
 *
 * 模型: 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
 * 输出维度: 192 维嵌入向量
 *
 * @author Fusion
 * @version 2.0 (Sherpa-onnx)
 */
public class SpeakerIdentifier implements PcmDataListener {

    private static final String TAG = "SpeakerId";

    // 声纹匹配阈值
    private static final double MATCH_THRESHOLD = 0.5;

    // 推理间隔: 每 1.5 秒取 1.5 秒音频进行比对
    private static final int INFERENCE_INTERVAL_MS = 1500;

    // 推理窗口: 1.5 秒 = 16000 * 2 * 1.5 = 48000 bytes
    private static final int INFERENCE_WINDOW_MS = 1500;

    // 声纹存储目录
    private static final String SPEAKER_DIR = "speaker_profiles";

    // 模型文件名 (assets/models/)
    private static final String MODEL_FILE = "models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx";

    // Sherpa-onnx 组件 (null 直到 init() 成功)
    private SpeakerEmbeddingExtractor extractor = null;
    private SpeakerEmbeddingManager manager = null;
    private int embeddingDim = 0;

    // Fallback: 内存存储 (用于 enroll 时无法访问 assets 的情况)
    private final ConcurrentHashMap<String, float[]> enrolledProfiles = new ConcurrentHashMap<>();

    // VAD
    private final VADHelper vadHelper;

    // PCM 累积缓冲区
    private byte[] pcmBuffer = null;

    // 上次推理时间
    private long lastInferenceTime = 0;

    // 日志
    private LogDBHelper logHelper;

    // 应用上下文
    private Context appContext;

    // 是否启用
    private volatile boolean enabled = false;

    // 是否初始化完成
    private volatile boolean initialized = false;

    public SpeakerIdentifier(Context context) {
        this.appContext = context.getApplicationContext();
        this.vadHelper = new VADHelper(400);
        this.logHelper = LogDBHelper.getInstance(context);
    }

    /**
     * 初始化 Sherpa-onnx 声纹引擎
     * 从 assets/models/ 目录加载
     *
     * @return true 如果初始化成功
     */
    public boolean init() {
        if (initialized) return true;

        try {
            File modelFile = getModelFile();
            if (!modelFile.exists()) {
                Log.w(TAG, "声纹模型不存在，先复制到: " + modelFile.getAbsolutePath());
                copyModelFromAssets();
            }

            if (!modelFile.exists()) {
                Log.e(TAG, "声纹模型文件缺失: " + modelFile.getAbsolutePath());
                return false;
            }

            Log.i(TAG, "加载声纹模型: " + modelFile.getAbsolutePath() + " ("
                    + (modelFile.length() / 1024 / 1024) + " MB)");

            SpeakerEmbeddingExtractorConfig config =
                SpeakerEmbeddingExtractorConfig.builder()
                    .setModel(modelFile.getAbsolutePath())
                    .setNumThreads(2)
                    .setDebug(false)
                    .build();

            extractor = new SpeakerEmbeddingExtractor(config);
            embeddingDim = extractor.getDim();
            manager = new SpeakerEmbeddingManager(embeddingDim);

            Log.i(TAG, "声纹引擎初始化成功! 嵌入维度: " + embeddingDim);

            // 从文件加载已注册的声纹
            loadProfiles();

            // 用内存中的 profile 补充注册到 manager
            for (String label : enrolledProfiles.keySet()) {
                float[] emb = enrolledProfiles.get(label);
                manager.add(label, new float[][]{emb});
                Log.i(TAG, "已注册说话人: " + label);
            }

            initialized = true;
            return true;

        } catch (Exception e) {
            Log.e(TAG, "声纹引擎初始化失败: " + e + "\n" + e.getStackTrace()[0]);
            return false;
        }
    }

    private File getModelFile() {
        return new File(appContext.getFilesDir(), MODEL_FILE);
    }

    private void copyModelFromAssets() {
        try {
            File dest = getModelFile();
            dest.getParentFile().mkdirs();

            AssetManager am = appContext.getAssets();
            try (InputStream is = am.open(MODEL_FILE);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int read;
                long copied = 0;
                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                    copied += read;
                }
                Log.i(TAG, "模型已从 assets 复制到: " + dest.getAbsolutePath() + " ("
                        + (copied / 1024 / 1024) + " MB)");
            }
        } catch (Exception e) {
            Log.e(TAG, "从 assets 复制模型失败: " + e);
        }
    }

    // ==================== 声纹注册 ====================

    /**
     * 注册声纹特征 (由 PC 端 MQTT speaker/enroll 下发)
     *
     * @param label 说话人标签 ("user", "huimei")
     * @param data  Base64 编码的二进制特征数据 (float32 LE)
     * @param format "binary" = float32 二进制
     */
    public void enroll(String label, String data, String format) {
        try {
            Log.i(TAG, "注册声纹: " + label + ", 格式: " + format);

            byte[] rawBytes = Base64.decode(data, Base64.NO_WRAP);
            float[] embedding = bytesToFloats(rawBytes);

            if (embedding.length != embeddingDim) {
                Log.w(TAG, "声纹维度不匹配: 收到 " + embedding.length + ", 期望 " + embeddingDim
                        + ". 使用内存存储 (不注册到 manager)");
                enrolledProfiles.put(label, embedding);
                saveProfileToFile(label, embedding);
                return;
            }

            // 注册到 Sherpa-onnx Manager
            if (manager != null) {
                manager.add(label, new float[][]{embedding});
                Log.i(TAG, "声纹注册到 Sherpa-onnx Manager: " + label);
            }

            // 同时保存到内存和文件
            enrolledProfiles.put(label, embedding);
            saveProfileToFile(label, embedding);

            if (logHelper != null) {
                logHelper.insertLog("speaker_enroll", label,
                        "声纹注册成功, 维度=" + embedding.length);
            }

        } catch (Exception e) {
            Log.e(TAG, "声纹注册失败: " + e + "\n" + e.getStackTrace()[0]);
        }
    }

    /**
     * 从文件加载已注册的声纹 (启动时调用)
     */
    public void loadProfiles() {
        File dir = new File(appContext.getFilesDir(), SPEAKER_DIR);
        if (!dir.exists()) return;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".bin"));
        if (files == null) return;

        for (File file : files) {
            try {
                String label = file.getName().replace(".bin", "");
                byte[] rawBytes = readFile(file);
                float[] embedding = bytesToFloats(rawBytes);
                enrolledProfiles.put(label, embedding);
                Log.i(TAG, "从文件加载声纹: " + label + ", 维度: " + embedding.length);
            } catch (Exception e) {
                Log.e(TAG, "加载声纹失败: " + file.getName() + ": " + e);
            }
        }
    }

    // ==================== PcmDataListener 回调 ====================

    @Override
    public void onPcmData(byte[] pcmData, int sampleRate, long timestamp) {
        if (!enabled || !initialized) return;

        boolean isSpeech = vadHelper.isSpeech(pcmData, sampleRate);
        if (!isSpeech) {
            pcmBuffer = null;
            return;
        }

        pcmBuffer = VADHelper.appendPcm(pcmBuffer, pcmData);

        long now = System.currentTimeMillis();
        if (now - lastInferenceTime < INFERENCE_INTERVAL_MS) return;

        int windowBytes = INFERENCE_WINDOW_MS * sampleRate * 2 / 1000;
        if (pcmBuffer == null || pcmBuffer.length < windowBytes) return;

        byte[] inferenceData = VADHelper.extractLastNMs(pcmBuffer, INFERENCE_WINDOW_MS, sampleRate);
        lastInferenceTime = now;

        String speaker = identifySpeaker(inferenceData, sampleRate);
        if (speaker != null) {
            Log.i(TAG, ">> 识别说话人: " + speaker);
            if (logHelper != null) {
                logHelper.insertLog("speech_detected", speaker,
                        "检测到 " + speaker + " 的语音");
            }
        }
    }

    @Override
    public void onRecordingStarted() {
        Log.i(TAG, "录音开始, 声纹识别已激活");
        vadHelper.reset();
        pcmBuffer = null;
        lastInferenceTime = 0;
    }

    @Override
    public void onRecordingStopped() {
        Log.i(TAG, "录音结束");
        pcmBuffer = null;
        vadHelper.reset();
    }

    // ==================== 声纹识别核心 ====================

    /**
     * 用 Sherpa-onnx 提取嵌入向量并比对
     */
    private String identifySpeaker(byte[] pcmData, int sampleRate) {
        if (enrolledProfiles.isEmpty()) return null;

        try {
            // PCM (16-bit LE) → float[] 音频数据
            float[] audio = pcmToFloats(pcmData);

            // 提取嵌入向量
            float[] embedding = extractor.computeEmbedding(audio, (int) sampleRate);

            // Sherpa-onnx Manager 搜索
            if (manager != null) {
                String name = manager.search(embedding, MATCH_THRESHOLD);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }

            // Fallback: 余弦相似度
            return cosineSearch(embedding);

        } catch (Exception e) {
            Log.e(TAG, "声纹识别异常: " + e + "\n" + e.getStackTrace()[0]);
            return null;
        }
    }

    /**
     * 余弦相似度搜索 (fallback)
     */
    private String cosineSearch(float[] embedding) {
        String best = null;
        double bestScore = 0;

        for (ConcurrentHashMap.Entry<String, float[]> entry : enrolledProfiles.entrySet()) {
            double score = cosineSimilarity(embedding, entry.getValue());
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }

        if (bestScore >= MATCH_THRESHOLD) {
            Log.d(TAG, "余弦匹配: " + best + " (" + String.format("%.3f", bestScore) + ")");
            return best;
        }
        return null;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ==================== 文件 I/O ====================

    private void saveProfileToFile(String label, float[] embedding) {
        try {
            File dir = new File(appContext.getFilesDir(), SPEAKER_DIR);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, label + ".bin");
            byte[] raw = floatsToBytes(embedding);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(raw);
            }
            Log.d(TAG, "声纹已保存: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "保存声纹文件失败: " + e);
        }
    }

    private byte[] readFile(File file) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        fis.read(data);
        fis.close();
        return data;
    }

    // ==================== 类型转换 ====================

    private float[] bytesToFloats(byte[] raw) {
        float[] out = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    private byte[] floatsToBytes(float[] f) {
        byte[] out = new byte[f.length * 4];
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(f);
        return out;
    }

    private float[] pcmToFloats(byte[] pcm) {
        float[] out = new float[pcm.length / 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(
                java.nio.ShortBuffer.wrap(
                        java.nio.ShortBuffer.wrap(new short[out.length]).position(0).array(),
                        0, out.length));
        // 直接循环转换
        for (int i = 0; i < out.length; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            out[i] = s / 32768.0f;
        }
        return out;
    }

    // ==================== 控制 ====================

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.i(TAG, "声纹识别: " + (enabled ? "已启用" : "已禁用"));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String[] getEnrolledSpeakers() {
        return enrolledProfiles.keySet().toArray(new String[0]);
    }
}
