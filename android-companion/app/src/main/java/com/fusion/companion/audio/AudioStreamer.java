package com.fusion.companion.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.fusion.companion.FusionWebSocketServer;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音频流传输器 — 手机麦克风 → PC (非 root 方案)
 *
 * 原理:
 *   AudioRecord 录音 → PCM 分片 → Base64 编码 → WebSocket 发送 → PC 端解码播放
 *
 * 参数:
 *   - 采样率: 16000 Hz (语音级, 低带宽)
 *   - 通道: 单声道
 *   - 位深: 16-bit PCM
 *   - 分片大小: ~200ms (3200 bytes = 16000 * 0.2 * 2)
 *
 * 使用方式:
 *   1. PC 端发送 WS 消息: {"type": "mic_control", "action": "start"}
 *   2. 手机开始录音并通过 WS 发送 PCM 分片
 *   3. PC 端发送: {"type": "mic_control", "action": "stop"}
 *   4. 手机停止录音
 *
 * 也可通过 MQTT 命令触发:
 *   fusion/audio/{deviceId}/command → {"action": "start_mic"} / {"action": "stop_mic"}
 *
 * @author Fusion
 * @version 1.0
 */
public class AudioStreamer {

    private static final String TAG = "AudioStreamer";

    // 音频参数
    private static final int SAMPLE_RATE = 16000;          // 16kHz
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // 分片参数: 每次发送 ~200ms 的数据
    private static final int FRAME_SIZE_MS = 200;
    private static final int BYTES_PER_FRAME = SAMPLE_RATE * FRAME_SIZE_MS / 1000 * 2; // 6400 bytes

    // 状态
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private Thread streamThread;
    private Handler mainHandler;
    private FusionWebSocketServer wsServer;
    private android.content.Context appContext;

    // 录音权限检查
    private boolean permissionGranted = false;

    // PCM 数据监听器列表 (声纹识别、ASR 等)
    private final CopyOnWriteArrayList<PcmDataListener> pcmListeners = new CopyOnWriteArrayList<>();

    public AudioStreamer(FusionWebSocketServer wsServer) {
        this.wsServer = wsServer;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置 Application Context (用于动态权限检查)
     */
    public void setContext(android.content.Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * 检查是否有录音权限
     */
    public boolean checkPermission(android.content.Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            permissionGranted = context.checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } else {
            permissionGranted = true;
        }

        if (!permissionGranted) {
            Log.w(TAG, "录音权限未授予");
        }
        return permissionGranted;
    }

    /**
     * 开始麦克风录音 + WebSocket 流式传输
     */
    public boolean startStreaming() {
        if (isStreaming.get()) {
            Log.w(TAG, "已在录音中");
            return false;
        }

        // 动态检查权限 (用户可能事后授权)
        if (!permissionGranted && appContext != null) {
            checkPermission(appContext);
        }

        if (!permissionGranted) {
            Log.e(TAG, "无录音权限，无法开始");
            notifyStatus("error", "无录音权限");
            return false;
        }

        // 初始化 AudioRecord
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord 缓冲区大小获取失败");
            notifyStatus("error", "AudioRecord 初始化失败");
            return false;
        }

        // 确保缓冲区足够大 (至少 2 倍最小值)
        int bufferSize = Math.max(minBufferSize * 2, BYTES_PER_FRAME * 2);

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            );
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 创建失败: " + e.getMessage());
            notifyStatus("error", "AudioRecord 创建失败: " + e.getMessage());
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败 (state != INITIALIZED)");
            audioRecord.release();
            audioRecord = null;
            notifyStatus("error", "AudioRecord 初始化失败");
            return false;
        }

        // 开始录音
        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 启动失败: " + e.getMessage());
            audioRecord.release();
            audioRecord = null;
            notifyStatus("error", "AudioRecord 启动失败: " + e.getMessage());
            return false;
        }

        isStreaming.set(true);
        Log.i(TAG, "麦克风录音开始 (16kHz, 16-bit, mono, ~200ms 分片)");

        // 通知 PC 端录音已开始
        notifyStatus("started", "麦克风录音已开始");

        // 通知 PCM 监听器
        for (PcmDataListener listener : pcmListeners) {
            listener.onRecordingStarted();
        }

        // 启动录音线程
        streamThread = new Thread(this::streamLoop, "audio_stream");
        streamThread.setDaemon(true);
        streamThread.start();

        return true;
    }

    /**
     * 停止麦克风录音
     */
    public void stopStreaming() {
        if (!isStreaming.get()) {
            return;
        }

        isStreaming.set(false);

        // 等待录音线程结束
        if (streamThread != null) {
            try {
                streamThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            streamThread = null;
        }

        // 释放 AudioRecord
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord 释放异常: " + e.getMessage());
            }
            audioRecord = null;
        }

        Log.i(TAG, "麦克风录音已停止");

        // 通知 PCM 监听器
        for (PcmDataListener listener : pcmListeners) {
            listener.onRecordingStopped();
        }

        // 通知 PC 端
        notifyStatus("stopped", "麦克风录音已停止");
    }

    /**
     * 是否正在录音
     */
    public boolean isStreaming() {
        return isStreaming.get();
    }

    /**
     * 录音循环 — 持续读取 PCM 数据并通过 WebSocket 发送
     */
    private void streamLoop() {
        byte[] buffer = new byte[BYTES_PER_FRAME];
        int seq = 0;

        while (isStreaming.get()) {
            try {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);

                if (bytesRead > 0) {
                    // 构建 PCM 分片消息
                    // 格式: {"type": "audio_pcm", "seq": N, "data": "base64...", "ts": timestamp}
                    byte[] pcmData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, pcmData, 0, bytesRead);

                    // 通知 PCM 监听器 (声纹识别、ASR 等消费 PCM 原始数据)
                    if (!pcmListeners.isEmpty()) {
                        long ts = System.currentTimeMillis();
                        for (PcmDataListener listener : pcmListeners) {
                            try {
                                listener.onPcmData(pcmData, SAMPLE_RATE, ts);
                            } catch (Exception e) {
                                Log.w(TAG, "PCM 监听器异常: " + e.getMessage());
                            }
                        }
                    }

                    String base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP);

                    JSONObject msg = new JSONObject();
                    msg.put("type", "audio_pcm");
                    msg.put("seq", seq++);
                    msg.put("sample_rate", SAMPLE_RATE);
                    msg.put("channels", 1);
                    msg.put("bits", 16);
                    msg.put("data", base64Data);
                    msg.put("ts", System.currentTimeMillis());

                    // 通过 WebSocket 发送
                    if (wsServer != null) {
                        wsServer.broadcast(msg.toString());
                    }

                    // 每 50 条打印一次日志 (避免日志刷屏)
                    if (seq % 50 == 0) {
                        Log.d(TAG, "音频流: 已发送 " + seq + " 个分片");
                    }

                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord 读取错误: INVALID_OPERATION");
                    break;
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord 读取错误: BAD_VALUE");
                    break;
                }

            } catch (Exception e) {
                Log.e(TAG, "音频流传输异常: " + e.getMessage());
                if (isStreaming.get()) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                }
            }
        }

        Log.i(TAG, "音频流线程结束, 共发送 " + seq + " 个分片");
    }

    /**
     * 通知 PC 端录音状态
     */
    private void notifyStatus(String status, String message) {
        if (wsServer != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "mic_status");
                msg.put("status", status);
                msg.put("message", message);
                msg.put("ts", System.currentTimeMillis());
                wsServer.broadcast(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "发送状态通知失败: " + e.getMessage());
            }
        }
    }

    /**
     * 更新 WebSocket Server 引用 (服务重启时调用)
     */
    public void updateWsServer(FusionWebSocketServer wsServer) {
        this.wsServer = wsServer;
    }

    /**
     * 添加 PCM 数据监听器 (声纹识别、ASR 等模块)
     */
    public void addPcmDataListener(PcmDataListener listener) {
        if (!pcmListeners.contains(listener)) {
            pcmListeners.add(listener);
            Log.i(TAG, "PCM 监听器已注册: " + listener.getClass().getSimpleName());
        }
    }

    /**
     * 移除 PCM 数据监听器
     */
    public void removePcmDataListener(PcmDataListener listener) {
        pcmListeners.remove(listener);
        Log.i(TAG, "PCM 监听器已移除: " + listener.getClass().getSimpleName());
    }

    /**
     * 释放资源
     */
    public void release() {
        stopStreaming();
        wsServer = null;
        Log.i(TAG, "AudioStreamer 资源已释放");
    }
}
