package com.fusion.companion.audio;

/**
 * PCM 数据监听器接口
 *
 * AudioStreamer 录音时的回调接口，允许其他模块（声纹识别、ASR 等）
 * 在不修改 AudioStreamer 核心逻辑的前提下，订阅 PCM 数据流。
 *
 * 使用方式:
 *   audioStreamer.addPcmDataListener(speakerIdentifier);
 *   audioStreamer.addPcmDataListener(streamingASRService);
 *
 * @author Fusion
 * @version 1.0
 */
public interface PcmDataListener {

    /**
     * 收到一帧 PCM 数据
     *
     * @param pcmData    PCM 原始字节 (16-bit signed LE, mono, 16kHz)
     * @param sampleRate 采样率
     * @param timestamp  时间戳 (System.currentTimeMillis())
     */
    void onPcmData(byte[] pcmData, int sampleRate, long timestamp);

    /**
     * 录音开始通知
     */
    default void onRecordingStarted() {}

    /**
     * 录音结束通知
     */
    default void onRecordingStopped() {}
}
