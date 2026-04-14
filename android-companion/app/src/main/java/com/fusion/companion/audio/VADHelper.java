package com.fusion.companion.audio;

import android.util.Log;

/**
 * 语音活动检测 (VAD) 辅助类
 *
 * 基于 RMS 能量阈值判断 PCM 数据中是否包含人声。
 * 轻量级纯 Java 实现，不依赖第三方库，适合实时流处理。
 *
 * 参数:
 *   - 采样率: 16000 Hz
 *   - 位深: 16-bit signed LE
 *   - 通道: 单声道
 *   - 默认 RMS 阈值: 500 (可通过 setThreshold() 调整)
 *
 * @author Fusion
 * @version 1.0
 */
public class VADHelper {

    private static final String TAG = "VADHelper";

    // 默认 RMS 能量阈值 (16-bit PCM, 实测: 环境噪声 ~50-150, 正常说话 ~500-3000)
    private static final int DEFAULT_RMS_THRESHOLD = 500;

    // 最小语音持续时间 (ms)，短于此时间的语音片段视为噪声
    private static final int MIN_SPEECH_DURATION_MS = 100;

    // 最小静音持续时间 (ms)，用于判断语音结束
    private static final int MIN_SILENCE_DURATION_MS = 300;

    private int rmsThreshold;
    private long speechStartMs = 0;
    private long lastSpeechMs = 0;
    private boolean isInSpeech = false;

    public VADHelper() {
        this.rmsThreshold = DEFAULT_RMS_THRESHOLD;
    }

    public VADHelper(int rmsThreshold) {
        this.rmsThreshold = rmsThreshold;
    }

    /**
     * 判断一帧 PCM 数据是否包含人声
     *
     * @param pcmData    PCM 字节数组 (16-bit signed LE, mono)
     * @param sampleRate 采样率
     * @return true 如果检测到人声
     */
    public boolean isSpeech(byte[] pcmData, int sampleRate) {
        double rms = calculateRMS(pcmData);
        boolean isSpeech = rms > rmsThreshold;

        long now = System.currentTimeMillis();

        if (isSpeech) {
            lastSpeechMs = now;
            if (!isInSpeech) {
                speechStartMs = now;
                isInSpeech = true;
            }
        } else {
            // 静音持续时间超过阈值，标记语音结束
            if (isInSpeech && (now - lastSpeechMs) > MIN_SILENCE_DURATION_MS) {
                isInSpeech = false;
            }
        }

        return isSpeech;
    }

    /**
     * 判断当前是否处于持续语音中 (用于声纹/ASR 累积判断)
     */
    public boolean isInSpeech() {
        return isInSpeech;
    }

    /**
     * 获取当前语音持续时间 (ms)
     */
    public long getSpeechDurationMs() {
        if (!isInSpeech) return 0;
        return System.currentTimeMillis() - speechStartMs;
    }

    /**
     * 计算 PCM 数据的 RMS 能量
     *
     * @param pcmData PCM 字节数组
     * @return RMS 值
     */
    public static double calculateRMS(byte[] pcmData) {
        if (pcmData == null || pcmData.length < 2) return 0;

        long sumSquares = 0;
        int sampleCount = pcmData.length / 2; // 16-bit = 2 bytes per sample

        for (int i = 0; i < sampleCount; i++) {
            // 小端序: 低位在前, 高位在后
            short sample = (short) ((pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8));
            sumSquares += (long) sample * sample;
        }

        return Math.sqrt((double) sumSquares / sampleCount);
    }

    /**
     * 将 PCM 字节数据拼接成累积缓冲区
     *
     * @param existing 已有缓冲区 (可能为 null)
     * @param newData  新数据
     * @return 拼接后的缓冲区
     */
    public static byte[] appendPcm(byte[] existing, byte[] newData) {
        if (existing == null || existing.length == 0) {
            byte[] copy = new byte[newData.length];
            System.arraycopy(newData, 0, copy, 0, newData.length);
            return copy;
        }
        byte[] merged = new byte[existing.length + newData.length];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        System.arraycopy(newData, 0, merged, existing.length, newData.length);
        return merged;
    }

    /**
     * 从累积缓冲区中截取指定时长的 PCM 数据
     *
     * @param buffer     累积缓冲区
     * @param durationMs 时长 (ms)
     * @param sampleRate 采样率
     * @return 截取的 PCM 数据 (从末尾截取)
     */
    public static byte[] extractLastNMs(byte[] buffer, int durationMs, int sampleRate) {
        int bytesPerMs = sampleRate * 2 / 1000; // 16-bit mono
        int neededBytes = durationMs * bytesPerMs;
        if (buffer.length <= neededBytes) return buffer;

        byte[] result = new byte[neededBytes];
        System.arraycopy(buffer, buffer.length - neededBytes, result, 0, neededBytes);
        return result;
    }

    public void setThreshold(int threshold) {
        this.rmsThreshold = threshold;
    }

    public int getThreshold() {
        return rmsThreshold;
    }

    public void reset() {
        isInSpeech = false;
        speechStartMs = 0;
        lastSpeechMs = 0;
    }
}
