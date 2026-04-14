package com.fusion.companion.asr;

import android.content.Context;
import android.util.Log;

import com.fusion.companion.audio.PcmDataListener;
import com.fusion.companion.audio.VADHelper;
import com.fusion.companion.log.LogDBHelper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 流式语音转文字服务 — 手机端实时 ASR
 *
 * 方案:
 *   - 首选: Sherpa-onnx (流式, 纯内存, 低延迟)
 *   - 备选: Whisper.cpp + 临时文件 (getCacheDir(), 用完即删)
 *
 * 工作流程:
 *   1. 监听 AudioStreamer 的 PCM 回调 (PcmDataListener)
 *   2. VAD 检测到语音段 → 累积 PCM
 *   3. 语音段结束 (静音 > 300ms) → 送入 ASR 引擎
 *   4. 输出完整文本 → 写入日志 (type=transcript)
 *
 * @author Fusion
 * @version 1.0
 */
public class StreamingASRService implements PcmDataListener {

    private static final String TAG = "StreamingASR";

    // 静音超时: 300ms 无语音认为一段话结束
    private static final int SPEECH_END_TIMEOUT_MS = 300;

    // 最大累积时长: 30 秒 (防止缓冲区无限增长)
    private static final int MAX_SPEECH_DURATION_MS = 30000;

    // ASR 引擎类型
    public enum EngineType {
        SHERPA_ONNX,    // 首选: Sherpa-onnx 流式
        WHISPER_CPP,    // 备选: Whisper.cpp 非流式
        STUB            // 存根: 仅日志输出
    }

    private final VADHelper vadHelper;
    private final LogDBHelper logHelper;
    private final Context appContext;

    // 当前 ASR 引擎
    private EngineType currentEngine = EngineType.STUB;

    // PCM 累积缓冲区 (当前语音段)
    private byte[] speechBuffer = null;

    // 语音段开始时间
    private long speechStartTs = 0;

    // 上次检测到语音的时间
    private long lastVoiceActiveTs = 0;

    // 当前说话人 (由 SpeakerIdentifier 设置)
    private volatile String currentSpeaker = null;

    // 是否启用
    private volatile boolean enabled = false;

    // Sherpa-onnx 流式识别器 (TODO)
    private Object sherpaRecognizer = null;

    public StreamingASRService(Context context) {
        this.appContext = context.getApplicationContext();
        this.vadHelper = new VADHelper(400);
        this.logHelper = LogDBHelper.getInstance(context);
    }

    // ==================== PcmDataListener 回调 ====================

    @Override
    public void onPcmData(byte[] pcmData, int sampleRate, long timestamp) {
        if (!enabled) return;

        boolean isSpeech = vadHelper.isSpeech(pcmData, sampleRate);

        if (isSpeech) {
            // 有语音: 累积数据
            speechBuffer = VADHelper.appendPcm(speechBuffer, pcmData);
            lastVoiceActiveTs = timestamp;

            if (speechStartTs == 0) {
                speechStartTs = timestamp;
                Log.d(TAG, "语音段开始");
            }

            // 检查是否超过最大时长
            if (speechBuffer != null && speechBuffer.length > MAX_SPEECH_DURATION_MS * sampleRate * 2 / 1000) {
                Log.w(TAG, "语音段超过 30s, 强制结束");
                finalizeSpeechSegment(sampleRate);
            }

        } else {
            // 无语音: 检查语音段是否结束
            if (speechBuffer != null && speechStartTs > 0) {
                long silenceDuration = timestamp - lastVoiceActiveTs;
                if (silenceDuration >= SPEECH_END_TIMEOUT_MS) {
                    Log.d(TAG, "语音段结束, 静音: " + silenceDuration + "ms");
                    finalizeSpeechSegment(sampleRate);
                }
            }
        }
    }

    @Override
    public void onRecordingStarted() {
        Log.i(TAG, "录音开始, ASR 监听已激活");
        vadHelper.reset();
        speechBuffer = null;
        speechStartTs = 0;
        lastVoiceActiveTs = 0;
    }

    @Override
    public void onRecordingStopped() {
        Log.i(TAG, "录音结束, ASR 监听已停止");
        // 如果还有未完成的语音段，立即处理
        if (speechBuffer != null && speechBuffer.length > 3200) { // 至少 100ms
            finalizeSpeechSegment(16000);
        }
        speechBuffer = null;
        vadHelper.reset();
    }

    // ==================== 语音段处理 ====================

    /**
     * 语音段结束 → 送入 ASR 引擎
     */
    private void finalizeSpeechSegment(int sampleRate) {
        if (speechBuffer == null || speechBuffer.length < 3200) {
            speechBuffer = null;
            speechStartTs = 0;
            return;
        }

        byte[] audioData = speechBuffer;
        long startTs = speechStartTs;

        // 重置缓冲区
        speechBuffer = null;
        speechStartTs = 0;
        lastVoiceActiveTs = 0;

        // 异步处理 (不阻塞录音线程)
        new Thread(() -> processSpeechSegment(audioData, sampleRate, startTs), "asr_process").start();
    }

    /**
     * 处理一段语音
     */
    private void processSpeechSegment(byte[] pcmData, int sampleRate, long startTs) {
        String transcript = null;

        switch (currentEngine) {
            case SHERPA_ONNX:
                transcript = recognizeWithSherpa(pcmData, sampleRate);
                break;
            case WHISPER_CPP:
                transcript = recognizeWithWhisper(pcmData, sampleRate);
                break;
            case STUB:
            default:
                transcript = recognizeStub(pcmData, sampleRate);
                break;
        }

        if (transcript != null && !transcript.trim().isEmpty()) {
            Log.i(TAG, "识别结果: [" + currentSpeaker + "] " + transcript);

            // 写入日志
            if (logHelper != null) {
                logHelper.insertLog("transcript", currentSpeaker, transcript);
            }
        }
    }

    // ==================== ASR 引擎实现 ====================

    /**
     * Sherpa-onnx 流式识别 (首选)
     * TODO: 集成 sherpa-onnx AAR 后实现
     */
    private String recognizeWithSherpa(byte[] pcmData, int sampleRate) {
        // TODO: 使用 sherpa-onnx StreamingRecognizer
        // 1. 创建流: recognizer.createStream()
        // 2. 送入 PCM: stream.acceptWaveform(pcmData, sampleRate)
        // 3. 获取结果: recognizer.getResult(stream)
        // 4. 释放流: stream.close()

        // Fallback to stub
        return recognizeStub(pcmData, sampleRate);
    }

    /**
     * Whisper.cpp 识别 (备选, 需临时文件)
     */
    private String recognizeWithWhisper(byte[] pcmData, int sampleRate) {
        File tempFile = null;
        try {
            // 写入临时 WAV 文件
            tempFile = new File(appContext.getCacheDir(), "asr_temp_" + System.currentTimeMillis() + ".wav");
            writeWavFile(tempFile, pcmData, sampleRate);

            // TODO: 调用 whisper.cpp JNI
            // String result = WhisperCpp.transcribe(tempFile.getAbsolutePath());

            // Fallback to stub
            return recognizeStub(pcmData, sampleRate);

        } catch (Exception e) {
            Log.e(TAG, "Whisper 识别失败: " + e.getMessage());
            return null;
        } finally {
            // 立即删除临时文件
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
                Log.d(TAG, "临时文件已删除: " + tempFile.getName());
            }
        }
    }

    /**
     * 存根识别: 仅记录语音段的统计信息
     * (在模型集成前的占位实现)
     */
    private String recognizeStub(byte[] pcmData, int sampleRate) {
        double durationSec = (double) pcmData.length / (sampleRate * 2);
        double rms = VADHelper.calculateRMS(pcmData);

        Log.d(TAG, String.format("[STUB] 语音段: %.1fs, RMS=%.0f (模型未集成, 返回空)",
            durationSec, rms));

        // 模型未集成时不返回结果
        return null;
    }

    // ==================== WAV 文件写入 ====================

    /**
     * 将 PCM 数据写入 WAV 文件
     */
    private void writeWavFile(File file, byte[] pcmData, int sampleRate) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);

        // WAV 文件头
        int dataSize = pcmData.length;
        int fileSize = 36 + dataSize;

        byte[] header = new byte[44];
        // RIFF
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeInt32LE(header, 4, fileSize);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        // fmt
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeInt32LE(header, 16, 16);       // chunk size
        writeInt16LE(header, 20, (short) 1); // PCM
        writeInt16LE(header, 22, (short) 1); // mono
        writeInt32LE(header, 24, sampleRate);
        writeInt32LE(header, 28, sampleRate * 2); // byte rate
        writeInt16LE(header, 32, (short) 2);      // block align
        writeInt16LE(header, 34, (short) 16);     // bits per sample
        // data
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeInt32LE(header, 40, dataSize);

        fos.write(header);
        fos.write(pcmData);
        fos.close();
    }

    private void writeInt32LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private void writeInt16LE(byte[] buf, int offset, short value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    // ==================== 控制 ====================

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.i(TAG, "ASR 服务: " + (enabled ? "已启用" : "已禁用"));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEngine(EngineType engine) {
        this.currentEngine = engine;
        Log.i(TAG, "ASR 引擎切换: " + engine);
    }

    public EngineType getEngine() {
        return currentEngine;
    }

    /**
     * 设置当前说话人 (由 SpeakerIdentifier 调用)
     */
    public void setCurrentSpeaker(String speaker) {
        this.currentSpeaker = speaker;
    }

    public String getCurrentSpeaker() {
        return currentSpeaker;
    }
}
