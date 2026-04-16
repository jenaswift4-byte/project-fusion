package com.fusion.companion.voice;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.fusion.companion.ai.LocalAIEngine;
import com.fusion.companion.asr.StreamingASRService;
import com.fusion.companion.service.MQTTClientService;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音活动管理器
 *
 * 统一管理 Vosk 离线语音识别、AI 回答、TTS 播放和 MQTT 通信
 */
public class VoiceActivityManager {

    private static final String TAG = "VoiceActivityManager";

    private Context context;
    private StreamingASRService streamingASR;
    private TextToSpeech ttsEngine;
    private MQTTClientService mqttClient;
    private LocalAIEngine aiEngine;
    private String deviceId;
    private boolean isTTSPlaying;
    private VoiceInteractionListener voiceListener;
    private android.os.Handler mainHandler;
    private ExecutorService aiExecutor;

    public interface VoiceInteractionListener {
        void onRecognitionStart();
        void onRecognitionEnd();
        void onTextRecognized(String text);
        void onAIResponse(String answer);
        void onTTSStart();
        void onTTSEnd();
        void onError(int errorCode, String message);
    }

    public VoiceActivityManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        this.isTTSPlaying = false;
        this.aiExecutor = Executors.newFixedThreadPool(2);
        Log.i(TAG, "VoiceActivityManager 初始化完成");
    }

    public void initialize(MQTTClientService mqttClient, LocalAIEngine aiEngine) {
        this.mqttClient = mqttClient;
        this.aiEngine = aiEngine;

        if (mqttClient != null) {
            this.deviceId = mqttClient.getMqttDeviceId();
        }

        // 初始化 Vosk ASR（异步，避免 ANR）
        streamingASR = new StreamingASRService(context);
        new Thread(() -> {
            boolean ok = streamingASR.init();
            Log.i(TAG, "VoiceActivityManager: Vosk ASR 初始化: " + (ok ? "✓" : "✗"));
        }, "vosk-init-vam").start();

        initTTSEngine();
        Log.i(TAG, "VoiceActivityManager 初始化完成 - 设备 ID: " + deviceId);
    }

    private void initTTSEngine() {
        ttsEngine = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = ttsEngine.setLanguage(Locale.CHINA);
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS 不支持中文，使用默认语言");
                }
                ttsEngine.setSpeechRate(1.0f);
                ttsEngine.setPitch(1.0f);
                ttsEngine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        isTTSPlaying = true;
                        if (voiceListener != null) mainHandler.post(() -> voiceListener.onTTSStart());
                    }
                    @Override public void onDone(String id) {
                        isTTSPlaying = false;
                        if (voiceListener != null) mainHandler.post(() -> voiceListener.onTTSEnd());
                    }
                    @Override public void onError(String id) {
                        isTTSPlaying = false;
                    }
                });
                Log.i(TAG, "TTS 引擎初始化完成");
            }
        });
    }

    public void setVoiceInteractionListener(VoiceInteractionListener listener) {
        this.voiceListener = listener;
    }

    public boolean hasRecordPermission() {
        int perm = context.checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO);
        return perm == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public boolean startVoiceInteraction() {
        if (streamingASR == null) {
            Log.e(TAG, "ASR 未初始化");
            return false;
        }
        stopTTS();
        streamingASR.setEnabled(true);
        if (voiceListener != null) mainHandler.post(() -> voiceListener.onRecognitionStart());
        return true;
    }

    public void stopVoiceInteraction() {
        if (streamingASR != null) streamingASR.setEnabled(false);
        if (voiceListener != null) mainHandler.post(() -> voiceListener.onRecognitionEnd());
    }

    public void playTTS(String text) {
        if (ttsEngine == null || text == null || text.isEmpty()) return;
        stopTTS();
        ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vam_" + System.currentTimeMillis());
    }

    public void stopTTS() {
        if (ttsEngine != null && isTTSPlaying) {
            ttsEngine.stop();
            isTTSPlaying = false;
        }
    }

    public boolean isRecognizing() {
        return streamingASR != null && streamingASR.isEnabled();
    }

    public boolean isTTSPlaying() {
        return isTTSPlaying;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void release() {
        stopVoiceInteraction();
        stopTTS();
        if (streamingASR != null) {
            streamingASR.setEnabled(false);
            streamingASR = null;
        }
        if (ttsEngine != null) {
            ttsEngine.shutdown();
            ttsEngine = null;
        }
        if (aiExecutor != null) aiExecutor.shutdownNow();
        voiceListener = null;
        mqttClient = null;
        aiEngine = null;
        Log.i(TAG, "VoiceActivityManager 资源已释放");
    }
}
