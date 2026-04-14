package com.fusion.companion.asr;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.fusion.companion.audio.PcmDataListener;
import com.fusion.companion.audio.VADHelper;
import com.fusion.companion.log.LogDBHelper;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;

/**
 * 流式语音转文字 — Sherpa-onnx Real Implementation
 *
 * 模型: sherpa-onnx-streaming-zipformer-zh-int8 (encoder/decoder/joiner)
 * 配置: 16kHz, 16-bit, mono PCM
 *
 * @author Fusion
 * @version 2.0 (Sherpa-onnx)
 */
public class StreamingASRService implements PcmDataListener {

    private static final String TAG = "StreamingASR";

    // 静音超时: 400ms 无语音认为一段话结束
    private static final int SPEECH_END_TIMEOUT_MS = 400;

    // 最大累积时长: 30 秒
    private static final int MAX_SPEECH_DURATION_MS = 30000;

    // 模型文件 (assets/models/)
    private static final String MODEL_DIR = "models/";
    private static final String MODEL_ENCODER = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/encoder.int8.onnx";
    private static final String MODEL_DECODER = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/decoder.onnx";
    private static final String MODEL_JOINER   = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/joiner.int8.onnx";
    private static final String MODEL_TOKENS   = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/tokens.txt";

    // Sherpa-onnx 组件
    private OnlineRecognizer recognizer = null;
    private OnlineStream stream = null;

    // 其他组件
    private final VADHelper vadHelper;
    private final LogDBHelper logHelper;
    private final Context appContext;

    // PCM 累积缓冲区
    private byte[] speechBuffer = null;

    // 语音段时间戳
    private long speechStartTs = 0;
    private long lastVoiceActiveTs = 0;

    // 当前说话人 (由 SpeakerIdentifier 设置)
    private volatile String currentSpeaker = null;

    // 是否启用
    private volatile boolean enabled = false;

    // 是否初始化
    private volatile boolean initialized = false;

    // 说话人本地缓存 (注册时设为当前说话人)
    private volatile String lastSpeaker = null;

    public StreamingASRService(Context context) {
        this.appContext = context.getApplicationContext();
        this.vadHelper = new VADHelper(400);
        this.logHelper = LogDBHelper.getInstance(context);
    }

    /**
     * 初始化 Sherpa-onnx ASR 引擎
     */
    public boolean init() {
        if (initialized) return true;

        try {
            File modelDir = getModelDir();
            ensureModelsExtracted(modelDir);

            File encoderFile = new File(modelDir, "encoder.int8.onnx");
            File decoderFile = new File(modelDir, "decoder.onnx");
            File joinerFile  = new File(modelDir, "joiner.int8.onnx");
            File tokensFile  = new File(modelDir, "tokens.txt");

            if (!encoderFile.exists() || !decoderFile.exists() ||
                !joinerFile.exists()  || !tokensFile.exists()) {
                Log.e(TAG, "ASR 模型文件缺失，请检查 assets/models/ 目录");
                return false;
            }

            Log.i(TAG, "加载 ASR 模型: " + encoderFile.getName() + " ("
                    + (encoderFile.length() / 1024 / 1024) + " MB)");

            OnlineTransducerModelConfig modelConfig =
                OnlineTransducerModelConfig.builder()
                    .setEncoder(encoderFile.getAbsolutePath())
                    .setDecoder(decoderFile.getAbsolutePath())
                    .setJoiner(joinerFile.getAbsolutePath())
                    .build();

            OnlineRecognizerConfig config =
                OnlineRecognizerConfig.builder()
                    .setModel(modelConfig)
                    .setTokens(tokensFile.getAbsolutePath())
                    .setNumThreads(2)
                    .setDebug(false)
                    .build();

            recognizer = new OnlineRecognizer(config);
            stream = recognizer.createStream();

            Log.i(TAG, "ASR 引擎初始化成功! tokens: " + tokensFile.getAbsolutePath());
            initialized = true;
            return true;

        } catch (Exception e) {
            Log.e(TAG, "ASR 引擎初始化失败: " + e + "\n" + e.getStackTrace()[0]);
            return false;
        }
    }

    private File getModelDir() {
        return new File(appContext.getFilesDir(), MODEL_DIR + "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30");
    }

    private void ensureModelsExtracted(File modelDir) {
        if (modelDir.exists() && new File(modelDir, "encoder.int8.onnx").exists()) {
            Log.i(TAG, "ASR 模型已存在: " + modelDir.getAbsolutePath());
            return;
        }

        Log.i(TAG, "从 assets 提取 ASR 模型到: " + modelDir.getAbsolutePath());
        modelDir.getParentFile().mkdirs();

        String[] modelFiles = {
            "models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/encoder.int8.onnx",
            "models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/decoder.onnx",
            "models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/joiner.int8.onnx",
            "models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/tokens.txt"
        };

        for (String assetPath : modelFiles) {
            try {
                File dest = new File(appContext.getFilesDir(), assetPath);
                dest.getParentFile().mkdirs();

                AssetManager am = appContext.getAssets();
                try (InputStream is = am.open(assetPath);
                     FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int read;
                    long copied = 0;
                    while ((read = is.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        copied += read;
                    }
                    Log.i(TAG, "已提取: " + assetPath + " (" + (copied/1024/1024) + " MB)");
                }
            } catch (Exception e) {
                Log.e(TAG, "提取模型失败: " + assetPath + ": " + e);
            }
        }
    }

    // ==================== PcmDataListener 回调 ====================

    @Override
    public void onPcmData(byte[] pcmData, int sampleRate, long timestamp) {
        if (!enabled || !initialized) return;

        boolean isSpeech = vadHelper.isSpeech(pcmData, sampleRate);

        if (isSpeech) {
            speechBuffer = VADHelper.appendPcm(speechBuffer, pcmData);
            lastVoiceActiveTs = timestamp;

            if (speechStartTs == 0) {
                speechStartTs = timestamp;
                Log.d(TAG, "语音段开始");
            }

            // 最大累积 30s
            int maxBytes = MAX_SPEECH_DURATION_MS * sampleRate * 2 / 1000;
            if (speechBuffer != null && speechBuffer.length >= maxBytes) {
                Log.w(TAG, "语音段超过 30s，强制结束");
                finalizeSpeechSegment(sampleRate);
            }

        } else {
            // 无语音
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

    // ==================== 语音段处理 ====================

    private void finalizeSpeechSegment(int sampleRate) {
        if (speechBuffer == null || speechBuffer.length < 3200) {
            resetBuffer();
            return;
        }

        byte[] audioData = speechBuffer;
        String speaker = lastSpeaker; // 捕获当前说话人
        resetBuffer();

        new Thread(() -> processSpeech(audioData, sampleRate, speaker), "asr_process").start();
    }

    private void resetBuffer() {
        speechBuffer = null;
        speechStartTs = 0;
        lastVoiceActiveTs = 0;
    }

    private void processSpeech(byte[] pcmData, int sampleRate, String speaker) {
        if (recognizer == null || stream == null) return;

        try {
            // 重置 stream
            stream.flush();

            // PCM → float[] (16-bit LE → normalize to [-1, 1])
            float[] audio = pcmToFloats(pcmData);

            // 送入 ASR 流
            stream.acceptWaveform(audio, sampleRate);

            // 解码
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }

            // 获取结果
            String text = recognizer.getResult(stream).getText();

            if (text != null && !text.trim().isEmpty()) {
                Log.i(TAG, ">> ASR: [" + (speaker != null ? speaker : "?") + "] " + text);

                if (logHelper != null) {
                    logHelper.insertLog("transcript", speaker != null ? speaker : "unknown", text);
                }
            }

            // 重置 stream 准备下一段
            stream.flush();

        } catch (Exception e) {
            Log.e(TAG, "ASR 处理异常: " + e + "\n" + e.getStackTrace()[0]);
        }
    }

    // ==================== 工具 ====================

    private float[] pcmToFloats(byte[] pcm) {
        float[] out = new float[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            out[i] = s / 32768.0f;
        }
        return out;
    }

    // ==================== 控制 ====================

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
