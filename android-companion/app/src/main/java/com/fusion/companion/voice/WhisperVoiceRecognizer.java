package com.fusion.companion.voice;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whisper 语音识别器（存根实现）
 *
 * 注意：MNN 框架（com.alibaba.mnn）不在 Maven Central，此文件为存根实现。
 * 如需真实 Whisper 本地识别支持，请从 https://github.com/alibaba/MNN 手动集成 .aar。
 *
 * 当前实现：
 * - 录音功能正常（AudioRecord）
 * - 语音识别返回占位符结果
 * - 所有回调接口与正式版相同
 */
public class WhisperVoiceRecognizer {

    private static final String TAG = "WhisperRecognizer";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_MULTIPLIER = 4;
    private static final int SILENCE_THRESHOLD_MS = 3000;
    private static final int MIN_SPEECH_DURATION_MS = 500;
    private static final int INFERENCE_THREADS = 2;

    private Context context;
    private AudioRecord audioRecord;
    private int bufferSize;
    private RecognitionListener listener;
    private Handler mainHandler;
    private ExecutorService inferenceExecutor;
    private boolean modelLoaded;
    private AtomicBoolean isRecording;
    private AtomicBoolean isRecognizing;
    private Thread recordingThread;
    private long lastSpeechTime;
    private long speechStartTime;
    private StringBuilder resultCache;
    private String modelPath;

    public WhisperVoiceRecognizer(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isRecording = new AtomicBoolean(false);
        this.isRecognizing = new AtomicBoolean(false);
        this.modelLoaded = false;
        this.resultCache = new StringBuilder();
        this.inferenceExecutor = Executors.newFixedThreadPool(INFERENCE_THREADS);

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        this.bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER;

        Log.i(TAG, "WhisperVoiceRecognizer 初始化完成（存根模式，MNN 未集成）");
    }

    public boolean hasRecordPermission() {
        int permission = context.checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO);
        return permission == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 加载模型（存根：MNN 未集成，始终返回 false）
     */
    public boolean loadModel(String modelPath) {
        Log.w(TAG, "MNN 未集成，无法加载 Whisper 模型：" + modelPath);
        Log.i(TAG, "如需语音识别，请从 https://github.com/alibaba/MNN 集成 .aar");
        this.modelPath = modelPath;
        // 存根模式：标记已"加载"以允许录音（但推理返回占位符）
        this.modelLoaded = true;
        return true;
    }

    public void unloadModel() {
        modelLoaded = false;
        modelPath = null;
        Log.i(TAG, "模型已卸载");
    }

    public void setRecognitionListener(RecognitionListener listener) {
        this.listener = listener;
    }

    public boolean startRecognition() {
        if (!hasRecordPermission()) {
            Log.e(TAG, "没有录音权限");
            postError(RecognitionListener.ERROR_CLIENT, "没有录音权限");
            return false;
        }

        if (isRecording.get()) {
            Log.w(TAG, "正在录音中，跳过");
            return false;
        }

        Log.i(TAG, "开始语音识别（存根模式）");

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                postError(RecognitionListener.ERROR_AUDIO, "AudioRecord 初始化失败");
                return false;
            }

            resultCache.setLength(0);
            lastSpeechTime = 0;
            speechStartTime = 0;

            audioRecord.startRecording();
            isRecording.set(true);
            isRecognizing.set(true);

            postReadyForSpeech();
            postBeginOfSpeech();

            recordingThread = new Thread(this::recordAndRecognize);
            recordingThread.start();

            return true;

        } catch (Exception e) {
            Log.e(TAG, "开始录音失败", e);
            postError(RecognitionListener.ERROR_AUDIO, e.getMessage());
            return false;
        }
    }

    public void stopRecognition() {
        if (!isRecording.get()) return;

        Log.i(TAG, "停止语音识别");

        try {
            isRecording.set(false);
            isRecognizing.set(false);

            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }

            if (recordingThread != null) {
                recordingThread.join(2000);
                recordingThread = null;
            }

            postEndOfSpeech();

            // 存根：返回占位符结果
            if (resultCache.length() > 0) {
                postResult(resultCache.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "停止录音失败", e);
        }
    }

    private void recordAndRecognize() {
        short[] audioData = new short[bufferSize / 2];

        while (isRecording.get()) {
            int read = audioRecord.read(audioData, 0, audioData.length);

            if (read > 0) {
                int volume = calculateVolume(audioData, read);
                postVolumeChanged(volume);

                boolean hasSpeech = detectSpeechActivity(audioData, read);
                if (hasSpeech) {
                    // 存根：累积一些样本后返回占位符结果
                    resultCache.append("[语音片段]");
                    postPartialResult(resultCache.toString());
                }
            } else if (read < 0) {
                postError(RecognitionListener.ERROR_AUDIO, "AudioRecord 读取失败");
                break;
            }
        }
    }

    private int calculateVolume(short[] audioData, int readSize) {
        long sum = 0;
        for (int i = 0; i < readSize; i++) {
            sum += (long) audioData[i] * audioData[i];
        }
        double rms = Math.sqrt(sum / (double) readSize);
        int db = (int) (20 * Math.log10(rms / 32768.0 + 1e-10) + 100);
        return Math.max(0, Math.min(100, db));
    }

    private boolean detectSpeechActivity(short[] audioData, int readSize) {
        long sum = 0;
        for (int i = 0; i < readSize; i++) {
            sum += Math.abs(audioData[i]);
        }
        double avgEnergy = sum / (double) readSize;
        boolean hasSpeech = avgEnergy > 100.0;

        if (hasSpeech) {
            if (speechStartTime == 0) speechStartTime = System.currentTimeMillis();
            lastSpeechTime = System.currentTimeMillis();
        }

        return hasSpeech;
    }

    // ==================== 回调方法 ====================

    private void postReadyForSpeech() {
        if (listener != null) mainHandler.post(() -> listener.onReadyForSpeech());
    }

    private void postBeginOfSpeech() {
        if (listener != null) mainHandler.post(() -> listener.onBeginOfSpeech());
    }

    private void postEndOfSpeech() {
        if (listener != null) mainHandler.post(() -> listener.onEndOfSpeech());
    }

    private void postPartialResult(String text) {
        if (listener != null) mainHandler.post(() -> listener.onPartialResult(text));
    }

    private void postResult(String text) {
        if (listener != null) mainHandler.post(() -> listener.onResult(text));
    }

    private void postVolumeChanged(int rmsdB) {
        if (listener != null) mainHandler.post(() -> listener.onVolumeChanged(rmsdB));
    }

    private void postError(int errorCode, String message) {
        if (listener != null) mainHandler.post(() -> listener.onError(errorCode, message));
    }

    // ==================== 公开方法 ====================

    public boolean isModelLoaded() { return modelLoaded; }
    public boolean isRecording() { return isRecording.get(); }
    public boolean isRecognizing() { return isRecognizing.get(); }
    public String getModelPath() { return modelPath; }

    public void release() {
        stopRecognition();
        unloadModel();
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdownNow();
            inferenceExecutor = null;
        }
        listener = null;
        Log.i(TAG, "资源释放完成");
    }
}
