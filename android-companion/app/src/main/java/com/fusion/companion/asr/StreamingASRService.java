package com.fusion.companion.asr;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.fusion.companion.audio.PcmDataListener;
import com.fusion.companion.audio.VADHelper;
import com.fusion.companion.log.LogDBHelper;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamingASRService implements PcmDataListener {

    private static final String TAG = "StreamingASR";

    private static final int SPEECH_END_TIMEOUT_MS = 400;
    private static final int MAX_SPEECH_DURATION_MS = 30000;

    // assets/models/ 中的文件名 (与 HuggingFace 仓库名一致)
    private static final String[] MODEL_FILES = {
        "encoder.int8.onnx",
        "decoder.onnx",
        "joiner.int8.onnx",
        "tokens.txt"
    };
    private static final String ASSET_DIR = "models";
    private static final String LOCAL_DIR = "sherpa_models";

    private OnlineRecognizer recognizer = null;
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
            Log.i(TAG, "初始化 sherpa-onnx ASR...");

            // 步骤1: 确保 assets 中的模型已复制到内部存储
            File modelDir = new File(appContext.getFilesDir(), LOCAL_DIR);
            if (!modelDir.exists()) modelDir.mkdirs();

            boolean needCopy = false;
            for (String name : MODEL_FILES) {
                File f = new File(modelDir, name);
                if (!f.exists() || f.length() == 0) {
                    needCopy = true;
                    break;
                }
            }

            if (needCopy) {
                Log.i(TAG, "首次运行，复制模型到: " + modelDir.getAbsolutePath());
                for (String name : MODEL_FILES) {
                    File dest = new File(modelDir, name);
                    if (!dest.exists() || dest.length() == 0) {
                        copyAsset(ASSET_DIR + "/" + name, dest);
                    }
                }
                Log.i(TAG, "模型复制完成");
            } else {
                Log.i(TAG, "模型已就绪，跳过复制");
            }

            // 步骤2: 用绝对路径创建 sherpa-onnx 识别器
            String encoderPath = new File(modelDir, MODEL_FILES[0]).getAbsolutePath();
            String decoderPath = new File(modelDir, MODEL_FILES[1]).getAbsolutePath();
            String joinerPath = new File(modelDir, MODEL_FILES[2]).getAbsolutePath();
            String tokensPath = new File(modelDir, MODEL_FILES[3]).getAbsolutePath();

            Log.i(TAG, "Encoder: " + encoderPath + " (" + new File(encoderPath).length() + " bytes)");
            Log.i(TAG, "Decoder: " + decoderPath + " (" + new File(decoderPath).length() + " bytes)");

            try {
                OnlineTransducerModelConfig transducerConfig =
                    new OnlineTransducerModelConfig(encoderPath, decoderPath, joinerPath);

                OnlineModelConfig modelConfig = new OnlineModelConfig();
                modelConfig.setTransducer(transducerConfig);
                modelConfig.setTokens(tokensPath);
                modelConfig.setNumThreads(2);
                modelConfig.setDebug(false);
                modelConfig.setProvider("cpu");
                modelConfig.setModelType("zipformer");

                FeatureConfig featureConfig = new FeatureConfig(16000, 80, 1.0f);

                OnlineRecognizerConfig config = new OnlineRecognizerConfig();
                config.setFeatConfig(featureConfig);
                config.setModelConfig(modelConfig);
                config.setEnableEndpoint(false);
                config.setDecodingMethod("greedy_search");
                config.setMaxActivePaths(1);

                // null AssetManager = 从文件系统绝对路径加载
                recognizer = new OnlineRecognizer(null, config);
                Log.i(TAG, "✓ OnlineRecognizer 创建成功！");

            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "❌ JNI 库加载失败: " + e.getMessage());
                throw e;
            } catch (Exception e) {
                Log.e(TAG, "❌ 创建失败: " + e.getClass().getName() + ": " + e.getMessage());
                throw e;
            }

            Log.i(TAG, "sherpa-onnx ASR 初始化成功!");
            initialized = true;
            return true;

        } catch (Exception e) {
            Log.e(TAG, "ASR 引擎初始化失败: " + e);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从 assets 复制单个文件到内部存储
     */
    private void copyAsset(String assetPath, File destFile) throws Exception {
        Log.i(TAG, "复制: " + assetPath + " -> " + destFile.getName());
        try (InputStream is = appContext.getAssets().open(assetPath);
             OutputStream os = new FileOutputStream(destFile)) {
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = is.read(buf)) != -1) {
                os.write(buf, 0, n);
                total += n;
            }
            Log.i(TAG, "  已复制 " + total + " bytes");
        }
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

        new Thread(() -> processSpeech(audioData, sampleRate, speaker), "asr_process").start();
    }

    private void resetBuffer() {
        speechBuffer = null;
        speechStartTs = 0;
        lastVoiceActiveTs = 0;
    }

    private void processSpeech(byte[] pcmData, int sampleRate, String speaker) {
        try {
            short[] shorts = new short[pcmData.length / 2];
            for (int i = 0; i < shorts.length; i++) {
                shorts[i] = (short) ((pcmData[i * 2 + 1] << 8) | (pcmData[i * 2] & 0xFF));
            }
            float[] floats = new float[shorts.length];
            for (int i = 0; i < floats.length; i++) {
                floats[i] = shorts[i] / 32768.0f;
            }

            OnlineStream stream = recognizer.createStream("");
            stream.acceptWaveform(floats, sampleRate);
            stream.inputFinished();

            StringBuilder text = new StringBuilder();
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }

            while (!recognizer.getResult(stream).getText().isEmpty()) {
                String partial = recognizer.getResult(stream).getText();
                text.append(partial);
                recognizer.decode(stream);
            }

            String result = text.toString().trim();
            if (!result.isEmpty()) {
                Log.i(TAG, "ASR 结果: " + result);
                if (logHelper != null) {
                    logHelper.insertLog("asr_result", speaker, result);
                }
            }

            stream.release();

        } catch (Exception e) {
            Log.e(TAG, "ASR 处理失败: " + e);
            e.printStackTrace();
        }
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
