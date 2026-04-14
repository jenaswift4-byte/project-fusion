package com.fusion.companion.asr;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.fusion.companion.audio.PcmDataListener;
import com.fusion.companion.audio.VADHelper;
import com.fusion.companion.log.LogDBHelper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 流式语音转文字 — Sherpa-onnx Stub Implementation
 *
 * 注意: sherpa-onnx 代码已暂时替换为 stub，CI 编译通过后需要适配真实 API
 * .so 和模型文件需要从 PC 推送到手机
 *
 * @author Fusion
 * @version 2.1-stub
 */
public class StreamingASRService implements PcmDataListener {

    private static final String TAG = "StreamingASR";

    private static final int SPEECH_END_TIMEOUT_MS = 400;
    private static final int MAX_SPEECH_DURATION_MS = 30000;

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

    /**
     * 初始化 Sherpa-onnx ASR 引擎 (stub 版本)
     */
    public boolean init() {
        if (initialized) return true;

        try {
            Log.i(TAG, "ASR 引擎初始化 (stub 版本，sherpa-onnx 将从 files/lib/ 加载)");
            initialized = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "ASR 引擎初始化失败: " + e);
            return false;
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
        // Stub: 不进行实际 ASR 处理
        // TODO: 适配真实 sherpa-onnx API
        Log.d(TAG, "收到语音数据 " + pcmData.length + " bytes (speaker: " + speaker + ")");
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
