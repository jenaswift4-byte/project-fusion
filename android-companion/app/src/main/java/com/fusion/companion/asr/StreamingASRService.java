package com.fusion.companion.asr;

import android.content.Context;
import android.util.Log;

import com.fusion.companion.audio.PcmDataListener;
import com.fusion.companion.audio.VADHelper;
import com.fusion.companion.log.LogDBHelper;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class StreamingASRService implements PcmDataListener {

    private static final String TAG = "StreamingASR";

    private static final int SPEECH_END_TIMEOUT_MS = 600;
    private static final int MAX_SPEECH_DURATION_MS = 30000;

    // Vosk 中文小模型 (42MB)
    private static final String MODEL_ZIP = "vosk-model-small-cn-0.22.zip";
    private static final String ASSET_MODEL_DIR = "models";
    private static final String LOCAL_MODEL_DIR = "vosk_model";

    private Model model;
    private Recognizer recognizer;
    private final VADHelper vadHelper;
    private final LogDBHelper logHelper;
    private final Context appContext;

    private byte[] speechBuffer = null;
    private long speechStartTs = 0;
    private long lastVoiceActiveTs = 0;
    private volatile String currentSpeaker = null;
    private volatile boolean enabled = false;
    private volatile boolean initialized = false;
    private volatile String lastSpeaker = null;

    public StreamingASRService(Context context) {
        this.appContext = context.getApplicationContext();
        this.vadHelper = new VADHelper(400);
        this.logHelper = LogDBHelper.getInstance(context);
    }

    public boolean init() {
        if (initialized) return true;

        try {
            Log.i(TAG, "初始化 Vosk 离线 ASR...");

            // 步骤1: 解压模型到内部存储
            File modelDir = new File(appContext.getFilesDir(), LOCAL_MODEL_DIR);
            if (!modelDir.exists()) modelDir.mkdirs();

            // 检查模型是否已解压
            File[] files = modelDir.listFiles();
            boolean modelReady = files != null && files.length > 0;

            if (!modelReady) {
                Log.i(TAG, "首次运行，解压模型到: " + modelDir.getAbsolutePath());
                unzipAssetModel(MODEL_ZIP, modelDir);
                Log.i(TAG, "模型解压完成");
            } else {
                Log.i(TAG, "模型已就绪，跳过解压");
            }

            // 步骤2: 加载模型
            // Vosk zip 解压后会有子目录，需要指向包含 am/conf/graph 的实际目录
            File modelPath = new File(modelDir, "vosk-model-small-cn-0.22");
            if (!modelPath.exists()) {
                // 如果没有子目录，可能模型直接解压到了 modelDir
                modelPath = modelDir;
            }
            try {
                model = new Model(modelPath.getAbsolutePath());
                Log.i(TAG, "✓ Model 加载成功!");
            } catch (IOException e) {
                Log.e(TAG, "❌ Model 加载失败: " + e.getMessage());
                throw e;
            }

            // 步骤3: 创建 Recognizer (16kHz 采样率)
            try {
                recognizer = new Recognizer(model, 16000.0f);
                Log.i(TAG, "✓ Recognizer 创建成功!");
            } catch (IOException e) {
                Log.e(TAG, "❌ Recognizer 创建失败: " + e.getMessage());
                throw e;
            }

            Log.i(TAG, "Vosk ASR 初始化成功!");
            initialized = true;
            return true;

        } catch (Exception e) {
            Log.e(TAG, "ASR 引擎初始化失败: " + e);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 解压 assets 中的模型 zip 到目标目录
     */
    private void unzipAssetModel(String assetZipPath, File destDir) throws IOException {
        Log.i(TAG, "解压: " + assetZipPath);
        InputStream is = appContext.getAssets().open(ASSET_MODEL_DIR + "/" + assetZipPath);
        ZipInputStream zis = new ZipInputStream(is);

        byte[] buf = new byte[65536];
        int count;
        long total = 0;

        while (true) {
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) break;

            File outFile = new File(destDir, entry.getName());

            // 安全检查：防止 zip 路径穿越
            String canonicalDest = destDir.getCanonicalPath();
            String canonicalOut = outFile.getCanonicalPath();
            if (!canonicalOut.startsWith(canonicalDest)) {
                throw new IOException("Zip 路径穿越: " + entry.getName());
            }

            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                OutputStream os = new FileOutputStream(outFile);
                while ((count = zis.read(buf)) != -1) {
                    os.write(buf, 0, count);
                    total += count;
                }
                os.close();
            }
            zis.closeEntry();
        }
        zis.close();
        Log.i(TAG, "  已解压 " + total + " bytes");
    }

    @Override
    public void onPcmData(byte[] pcmData, int sampleRate, long timestamp) {
        if (!enabled || !initialized || recognizer == null) return;

        boolean isSpeech = vadHelper.isSpeech(pcmData, sampleRate);

        if (isSpeech) {
            speechBuffer = VADHelper.appendPcm(speechBuffer, pcmData);
            lastVoiceActiveTs = timestamp;

            if (speechStartTs == 0) {
                speechStartTs = timestamp;
                Log.d(TAG, "语音段开始");
            }

            int maxBytes = MAX_SPEECH_DURATION_MS * sampleRate * 2 / 1000;
            if (speechBuffer != null && speechBuffer.length >= maxBytes) {
                Log.w(TAG, "语音段超过 30s，强制结束");
                finalizeSpeechSegment(sampleRate);
            }

        } else {
            if (speechBuffer != null && speechStartTs > 0) {
                long silenceMs = timestamp - lastVoiceActiveTs;
                if (silenceMs >= SPEECH_END_TIMEOUT_MS) {
                    Log.d(TAG, "语音段结束，静音: " + silenceMs + "ms");
                    finalizeSpeechSegment(sampleRate);
                }
            }
        }
    }

    @Override
    public void onRecordingStarted() {
        Log.i(TAG, "录音开始，ASR 监听已激活");
        vadHelper.reset();
        speechBuffer = null;
        speechStartTs = 0;
        lastVoiceActiveTs = 0;
        if (recognizer != null) {
            recognizer.reset();  // 重置识别器
        }
    }

    @Override
    public void onRecordingStopped() {
        Log.i(TAG, "录音结束");
        if (speechBuffer != null && speechBuffer.length > 3200) {
            finalizeSpeechSegment(16000);
        }
        speechBuffer = null;
        vadHelper.reset();
    }

    private void finalizeSpeechSegment(int sampleRate) {
        if (speechBuffer == null || speechBuffer.length < 3200) {
            resetBuffer();
            return;
        }

        byte[] audioData = speechBuffer;
        String speaker = lastSpeaker;
        resetBuffer();

        new Thread(() -> processSpeech(audioData, speaker), "vosk_asr").start();
    }

    private void resetBuffer() {
        speechBuffer = null;
        speechStartTs = 0;
        lastVoiceActiveTs = 0;
    }

    private void processSpeech(byte[] pcmData, String speaker) {
        try {
            // Vosk acceptWaveForm 接受 byte[] + len
            boolean hasResult = recognizer.acceptWaveForm(pcmData, pcmData.length);

            if (hasResult) {
                String result = recognizer.getFinalResult();
                String text = extractText(result);
                if (!text.isEmpty()) {
                    Log.i(TAG, "ASR 结果: " + text);
                    if (logHelper != null) {
                        logHelper.insertLog("asr_result", speaker, text);
                    }
                }
            } else {
                String partial = recognizer.getPartialResult();
                String text = extractText(partial);
                if (!text.isEmpty()) {
                    Log.d(TAG, "ASR 部分: " + text);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "ASR 处理失败: " + e);
            e.printStackTrace();
        }
    }

    private String extractText(String json) {
        if (json == null || json.isEmpty()) return "";
        // final result: {"text": "xxx"}, partial result: {"partial": "xxx"}
        int idx = json.indexOf("\"text\"");
        if (idx < 0) idx = json.indexOf("\"partial\"");
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx);
        if (colon < 0) return "";
        int start = json.indexOf("\"", colon + 1);
        if (start < 0) return "";
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return "";
        return json.substring(start + 1, end).trim();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.i(TAG, "ASR: " + (enabled ? "已启用" : "已禁用"));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setCurrentSpeaker(String speaker) {
        this.lastSpeaker = speaker;
    }
}
