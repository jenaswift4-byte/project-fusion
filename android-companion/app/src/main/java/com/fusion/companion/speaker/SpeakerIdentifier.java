package com.fusion.companion.speaker;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.fusion.companion.audio.PcmDataListener;
import com.fusion.companion.audio.VADHelper;
import com.fusion.companion.log.LogDBHelper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声纹识别服务 — 手机端实时推理匹配
 *
 * 工作流程:
 *   1. PC 端录制声纹样本 → 提取特征 → 通过 MQTT speaker/enroll 下发
 *   2. 手机端接收特征文件 → 存储到私有目录 → 注册到内存
 *   3. 监听 AudioStreamer 的 PCM 回调 (PcmDataListener)
 *   4. VAD 检测到人声 → 每 1 秒取 1 秒音频 → 比对声纹 → 输出标签
 *   5. 检测结果写入日志 (type=speech_detected)
 *
 * 当前实现: 基于 RMS 能量特征的轻量级匹配
 *   - 预留 Vosk/Sherpa-onnx 声纹嵌入向量接口
 *   - 模型加载后自动切换到高精度模式
 *
 * @author Fusion
 * @version 1.0
 */
public class SpeakerIdentifier implements PcmDataListener {

    private static final String TAG = "SpeakerId";

    // 声纹匹配阈值 (0.0 ~ 1.0)
    private static final double MATCH_THRESHOLD = 0.7;

    // 推理间隔: 每 1 秒取 1 秒音频进行比对
    private static final int INFERENCE_INTERVAL_MS = 1000;

    // 推理音频窗口: 1 秒 = 16000 * 2 = 32000 bytes
    private static final int INFERENCE_WINDOW_BYTES = 16000 * 2;

    // 声纹存储目录
    private static final String SPEAKER_DIR = "speaker_profiles";

    // 已注册的声纹: label → 特征向量
    private final ConcurrentHashMap<String, float[]> enrolledProfiles = new ConcurrentHashMap<>();

    // VAD 辅助
    private final VADHelper vadHelper;

    // PCM 累积缓冲区
    private byte[] pcmBuffer = null;

    // 上次推理时间
    private long lastInferenceTime = 0;

    // 日志助手
    private LogDBHelper logHelper;

    // 应用上下文
    private Context appContext;

    // 是否启用
    private volatile boolean enabled = false;

    // 高精度模式 (Vosk/Sherpa-onnx)
    private volatile boolean highAccuracyMode = false;

    public SpeakerIdentifier(Context context) {
        this.appContext = context.getApplicationContext();
        this.vadHelper = new VADHelper(400); // 稍低于默认阈值，提高召回率
        this.logHelper = LogDBHelper.getInstance(context);
    }

    // ==================== 声纹注册 (由 MQTT speaker/enroll 触发) ====================

    /**
     * 注册声纹特征 (由 PC 端下发)
     *
     * @param label  说话人标签 (如 "user", "huimei")
     * @param data   特征数据 (Base64 编码的二进制或 JSON)
     * @param format 格式: "vector" (浮点数组) 或 "raw" (原始二进制)
     */
    public void enroll(String label, String data, String format) {
        try {
            Log.i(TAG, "注册声纹: " + label + ", 格式: " + format);

            float[] features;
            if ("vector".equals(format)) {
                // JSON 数组格式: [0.1, 0.2, 0.3, ...]
                String cleaned = data.replaceAll("[\\[\\]]", "");
                String[] parts = cleaned.split(",");
                features = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    features[i] = Float.parseFloat(parts[i].trim());
                }
            } else {
                // 原始二进制 → Base64 解码 → float 数组
                byte[] rawBytes = Base64.decode(data, Base64.NO_WRAP);
                features = bytesToFloats(rawBytes);
            }

            // 存储到内存
            enrolledProfiles.put(label, features);
            Log.i(TAG, "声纹注册成功: " + label + ", 特征维度: " + features.length);

            // 持久化到文件
            saveProfileToFile(label, features);

            // 写日志
            if (logHelper != null) {
                logHelper.insertLog("speaker_enroll", label,
                    "声纹注册成功, 维度=" + features.length);
            }

        } catch (Exception e) {
            Log.e(TAG, "声纹注册失败: " + e.getMessage());
        }
    }

    /**
     * 从文件加载已注册的声纹 (服务启动时调用)
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
                float[] features = bytesToFloats(rawBytes);
                enrolledProfiles.put(label, features);
                Log.i(TAG, "加载声纹: " + label + ", 维度: " + features.length);
            } catch (Exception e) {
                Log.e(TAG, "加载声纹失败: " + file.getName());
            }
        }

        Log.i(TAG, "声纹加载完成, 已注册: " + enrolledProfiles.keySet());
    }

    // ==================== PcmDataListener 回调 ====================

    @Override
    public void onPcmData(byte[] pcmData, int sampleRate, long timestamp) {
        if (!enabled) return;

        // VAD 判断
        boolean isSpeech = vadHelper.isSpeech(pcmData, sampleRate);
        if (!isSpeech) {
            // 非语音，清空缓冲区
            pcmBuffer = null;
            return;
        }

        // 累积 PCM 数据
        pcmBuffer = VADHelper.appendPcm(pcmBuffer, pcmData);

        // 检查是否达到推理间隔
        long now = System.currentTimeMillis();
        if (now - lastInferenceTime < INFERENCE_INTERVAL_MS) return;

        // 检查缓冲区是否有足够数据 (至少 1 秒)
        if (pcmBuffer == null || pcmBuffer.length < INFERENCE_WINDOW_BYTES) return;

        // 取最后 1 秒的数据进行推理
        byte[] inferenceData = VADHelper.extractLastNMs(pcmBuffer, 1000, sampleRate);
        lastInferenceTime = now;

        // 执行声纹匹配
        String matchedSpeaker = matchSpeaker(inferenceData, sampleRate);

        if (matchedSpeaker != null) {
            Log.i(TAG, "识别说话人: " + matchedSpeaker);

            // 写入日志
            if (logHelper != null) {
                logHelper.insertLog("speech_detected", matchedSpeaker,
                    "检测到 " + matchedSpeaker + " 的语音");
            }
        }
    }

    @Override
    public void onRecordingStarted() {
        Log.i(TAG, "录音开始, 声纹识别监听已激活");
        vadHelper.reset();
        pcmBuffer = null;
        lastInferenceTime = 0;
    }

    @Override
    public void onRecordingStopped() {
        Log.i(TAG, "录音结束, 声纹识别监听已停止");
        pcmBuffer = null;
        vadHelper.reset();
    }

    // ==================== 声纹匹配核心 ====================

    /**
     * 匹配说话人
     *
     * 当前实现: 基于 RMS 能量分布的轻量级匹配
     * 高精度模式: Vosk/Sherpa-onnx 嵌入向量余弦相似度
     *
     * @param pcmData    1 秒 PCM 数据
     * @param sampleRate 采样率
     * @return 匹配的说话人标签, 或 null
     */
    private String matchSpeaker(byte[] pcmData, int sampleRate) {
        if (enrolledProfiles.isEmpty()) return null;

        if (highAccuracyMode) {
            return matchByEmbedding(pcmData, sampleRate);
        } else {
            return matchByEnergy(pcmData, sampleRate);
        }
    }

    /**
     * 轻量级: 基于能量特征匹配 (无需模型)
     */
    private String matchByEnergy(byte[] pcmData, int sampleRate) {
        // 提取 8 段能量特征 (每 125ms 一段)
        float[] segments = extractEnergyFeatures(pcmData, 8);

        String bestMatch = null;
        double bestScore = 0;

        for (Map.Entry<String, float[]> entry : enrolledProfiles.entrySet()) {
            double score = cosineSimilarity(segments, entry.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestMatch = entry.getKey();
            }
        }

        Log.d(TAG, "声纹匹配: best=" + bestMatch + ", score=" + String.format("%.3f", bestScore));
        return bestScore >= MATCH_THRESHOLD ? bestMatch : null;
    }

    /**
     * 高精度: 基于嵌入向量匹配 (需要 Vosk/Sherpa-onnx 模型)
     * TODO: 集成 Vosk 说话人识别模型后实现
     */
    private String matchByEmbedding(byte[] pcmData, int sampleRate) {
        // TODO: 使用 Vosk SpkModel 提取嵌入向量，计算余弦相似度
        // 目前 fallback 到能量匹配
        return matchByEnergy(pcmData, sampleRate);
    }

    /**
     * 提取能量分布特征 (将 PCM 分成 N 段，计算每段 RMS)
     */
    private float[] extractEnergyFeatures(byte[] pcmData, int numSegments) {
        float[] features = new float[numSegments];
        int segmentBytes = pcmData.length / numSegments;

        for (int i = 0; i < numSegments; i++) {
            int start = i * segmentBytes;
            int end = Math.min(start + segmentBytes, pcmData.length);
            byte[] segment = new byte[end - start];
            System.arraycopy(pcmData, start, segment, 0, segment.length);

            features[i] = (float) VADHelper.calculateRMS(segment);
        }

        // 归一化
        float maxVal = 0;
        for (float v : features) maxVal = Math.max(maxVal, Math.abs(v));
        if (maxVal > 0) {
            for (int i = 0; i < features.length; i++) features[i] /= maxVal;
        }

        return features;
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;

        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) return 0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ==================== 文件 I/O ====================

    private void saveProfileToFile(String label, float[] features) {
        try {
            File dir = new File(appContext.getFilesDir(), SPEAKER_DIR);
            if (!dir.exists()) dir.mkdirs();

            byte[] rawBytes = floatsToBytes(features);
            File file = new File(dir, label + ".bin");

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(rawBytes);
            fos.close();

            Log.d(TAG, "声纹文件已保存: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "保存声纹文件失败: " + e.getMessage());
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

    private float[] bytesToFloats(byte[] rawBytes) {
        float[] floats = new float[rawBytes.length / 4];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(rawBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < floats.length; i++) {
            floats[i] = bb.getFloat();
        }
        return floats;
    }

    private byte[] floatsToBytes(float[] floats) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(floats.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) bb.putFloat(f);
        return bb.array();
    }

    // ==================== 控制 ====================

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.i(TAG, "声纹识别: " + (enabled ? "已启用" : "已禁用"));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setHighAccuracyMode(boolean mode) {
        this.highAccuracyMode = mode;
        Log.i(TAG, "高精度模式: " + mode);
    }

    /**
     * 获取已注册的说话人列表
     */
    public String[] getEnrolledSpeakers() {
        return enrolledProfiles.keySet().toArray(new String[0]);
    }
}
