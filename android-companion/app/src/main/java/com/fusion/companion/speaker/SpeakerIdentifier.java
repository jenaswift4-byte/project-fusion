package com.fusion.companion.speaker;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.fusion.companion.audio.PcmDataListener;
import com.fusion.companion.audio.VADHelper;
import com.fusion.companion.log.LogDBHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声纹识别 — 能量特征匹配 (stub 实现)
 *
 * 当前使用能量特征匹配，Vosk 说话人识别模型预留。
 *
 * @author Fusion
 * @version 2.2
 */
public class SpeakerIdentifier implements PcmDataListener {

    private static final String TAG = "SpeakerId";

    private static final double MATCH_THRESHOLD = 0.5;
    private static final int INFERENCE_INTERVAL_MS = 1500;
    private static final int INFERENCE_WINDOW_MS = 1500;

    private final ConcurrentHashMap<String, float[]> enrolledProfiles = new ConcurrentHashMap<>();
    private final VADHelper vadHelper;

    private byte[] pcmBuffer = null;
    private long lastInferenceTime = 0;
    private LogDBHelper logHelper;
    private Context appContext;
    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    public SpeakerIdentifier(Context context) {
        this.appContext = context.getApplicationContext();
        this.vadHelper = new VADHelper(400);
        this.logHelper = LogDBHelper.getInstance(context);
    }

    public boolean init() {
        if (initialized) return true;

        try {
            Log.i(TAG, "声纹引擎初始化 (能量特征匹配模式)");
            loadProfiles();
            initialized = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "声纹引擎初始化失败: " + e);
            return false;
        }
    }

    public void enroll(String label, String data, String format) {
        try {
            Log.i(TAG, "注册声纹 (stub): " + label);

            byte[] rawBytes = Base64.decode(data, Base64.NO_WRAP);
            float[] embedding = bytesToFloats(rawBytes);

            enrolledProfiles.put(label, embedding);
            saveProfileToFile(label, embedding);

            if (logHelper != null) {
                logHelper.insertLog("speaker_enroll", label,
                        "声纹注册成功, 维度=" + embedding.length);
            }

        } catch (Exception e) {
            Log.e(TAG, "声纹注册失败: " + e);
        }
    }

    public void loadProfiles() {
        File dir = new File(appContext.getFilesDir(), "speaker_profiles");
        if (!dir.exists()) return;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".bin"));
        if (files == null) return;

        for (File file : files) {
            try {
                String label = file.getName().replace(".bin", "");
                byte[] rawBytes = readFile(file);
                float[] embedding = bytesToFloats(rawBytes);
                enrolledProfiles.put(label, embedding);
                Log.i(TAG, "从文件加载声纹: " + label);
            } catch (Exception e) {
                Log.e(TAG, "加载声纹失败: " + file.getName() + ": " + e);
            }
        }
    }

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

        lastInferenceTime = now;
        Log.d(TAG, "检测到语音 (stub 模式，不进行实际识别)");
    }

    @Override
    public void onRecordingStarted() {
        Log.i(TAG, "录音开始, 声纹识别已激活 (stub)");
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

    private void saveProfileToFile(String label, float[] embedding) {
        try {
            File dir = new File(appContext.getFilesDir(), "speaker_profiles");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, label + ".bin");
            byte[] raw = floatsToBytes(embedding);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(raw);
            }
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
