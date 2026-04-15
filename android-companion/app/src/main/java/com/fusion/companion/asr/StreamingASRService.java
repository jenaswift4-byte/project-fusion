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

public class StreamingASRService implements PcmDataListener {

    private static final String TAG = "StreamingASR";

    private static final int SPEECH_END_TIMEOUT_MS = 400;
    private static final int MAX_SPEECH_DURATION_MS = 30000;

    // 模型文件在 assets/models/ 中的相对路径 (AssetManager 直接加载，无需复制)
    private static final String MODEL_DIR = "models";
    private static final String MODEL_ENCODER = MODEL_DIR + "/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30_encoder.int8.onnx";
    private static final String MODEL_DECODER = MODEL_DIR + "/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30_decoder.onnx";
    private static final String MODEL_JOINER   = MODEL_DIR + "/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30_joiner.int8.onnx";
    private static final String MODEL_TOKENS   = MODEL_DIR + "/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30_tokens.txt";

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
            Log.i(TAG, "初始化 sherpa-onnx ASR (AssetManager 直接加载模式)...");

            try {
                AssetManager assetManager = appContext.getAssets();

                Log.i(TAG, "开始创建 OnlineTransducerModelConfig...");
                OnlineTransducerModelConfig transducerConfig =
                    new OnlineTransducerModelConfig(MODEL_ENCODER, MODEL_DECODER, MODEL_JOINER);
                Log.i(TAG, "OnlineTransducerModelConfig 创建成功");

                Log.i(TAG, "开始创建 OnlineModelConfig...");
                OnlineModelConfig modelConfig = new OnlineModelConfig();
                modelConfig.setTransducer(transducerConfig);
                modelConfig.setTokens(MODEL_TOKENS);
                modelConfig.setNumThreads(1);
                modelConfig.setDebug(false);
                modelConfig.setProvider("cpu");
                modelConfig.setModelType("zipformer");

                Log.i(TAG, "开始创建 FeatureConfig...");
                FeatureConfig featureConfig = new FeatureConfig(16000, 80, 1.0f);

                Log.i(TAG, "开始创建 OnlineRecognizerConfig...");
                OnlineRecognizerConfig config = new OnlineRecognizerConfig();
                config.setFeatConfig(featureConfig);
                config.setModelConfig(modelConfig);
                config.setEnableEndpoint(false);
                config.setDecodingMethod("greedy_search");
                config.setMaxActivePaths(1);

                Log.i(TAG, "创建 OnlineRecognizer (AssetManager 直接加载)...");
                recognizer = new OnlineRecognizer(assetManager, config);
                Log.i(TAG, "✓ OnlineRecognizer 创建成功！");

            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "❌ JNI 库加载失败：" + e.getMessage());
                Log.e(TAG, "请检查 .so 文件是否正确打包到 APK 中");
                e.printStackTrace();
                throw e;
            } catch (Exception e) {
                Log.e(TAG, "❌ 创建失败：" + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
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
