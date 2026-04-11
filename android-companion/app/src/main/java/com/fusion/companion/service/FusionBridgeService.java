package com.fusion.companion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.fusion.companion.FusionWebSocketServer;

import org.json.JSONObject;

import java.net.InetSocketAddress;

/**
 * Fusion Bridge 前台服务
 * 运行 WebSocket Server，实时转发手机事件到 PC
 *
 * PC 端通过 ADB forward 连接:
 *   adb forward tcp:17532 tcp:17532
 * 然后连接 ws://127.0.0.1:17532
 */
public class FusionBridgeService extends Service {

    private static final String TAG = "FusionBridge";
    private static final String CHANNEL_ID = "fusion_bridge";
    private static final int NOTIFICATION_ID = 1;
    private static final int DEFAULT_PORT = 17532;

    private static boolean running = false;
    private static FusionWebSocketServer staticWsServer;  // 静态引用，供 NotificationListener 访问

    private FusionWebSocketServer wsServer;
    private ClipboardManager clipboardManager;
    private TelephonyManager telephonyManager;
    private SharedPreferences prefs;
    private Handler handler;

    // 上次剪贴板内容 (去重)
    private String lastClipboardText = "";

    public static boolean isRunning() {
        return running;
    }

    /**
     * 获取 WebSocket Server 实例 (供 FusionNotificationListener 调用)
     */
    public static FusionWebSocketServer getWebSocketServer() {
        return staticWsServer;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = getSharedPreferences("fusion", MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());

        // 创建前台通知
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Fusion Bridge 运行中")
                .setContentText("WebSocket 服务已启动")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        // 启动 WebSocket Server
        startWebSocketServer();
        staticWsServer = wsServer;  // 暴露给 NotificationListener

        // 启动剪贴板监听
        startClipboardMonitor();

        // 启动通话监听
        startTelephonyMonitor();

        Log.i(TAG, "Fusion Bridge 服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;  // 被杀后自动重启
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;

        if (wsServer != null) {
            try {
                wsServer.stopAll();
            } catch (Exception e) {
                Log.e(TAG, "停止 WebSocket 失败", e);
            }
        }
        staticWsServer = null;  // 清除静态引用

        // 通话监听器在 Service 销毁时自动解绑，无需手动清理
        // (TelephonyCallback / PhoneStateListener 随 Service 生命周期结束)

        Log.i(TAG, "Fusion Bridge 服务已停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // === WebSocket Server (支持混合模式) ===

    private void startWebSocketServer() {
        int port = prefs.getInt("ws_port", DEFAULT_PORT);
        wsServer = new FusionWebSocketServer(new InetSocketAddress(port));

        wsServer.setEventListener((type, data) -> {
            // 转发客户端发来的命令 (如: 打开链接, 设置剪贴板)
            handleClientCommand(type, data);
        });

        // 先启动 Server (独立模式)
        try {
            wsServer.start();
            Log.i(TAG, "WebSocket Server 已启动: 端口 " + port);
        } catch (Exception e) {
            Log.e(TAG, "WebSocket Server 启动失败", e);
        }

        // 在后台线程检测 Termux Bridge (混合模式)
        new Thread(() -> {
            try {
                Thread.sleep(1000);  // 等 Server 完全启动
                wsServer.detectMode();
                // 更新前台通知
                handler.post(() -> updateNotification());
            } catch (Exception e) {
                Log.d(TAG, "模式检测失败: " + e.getMessage());
            }
        }).start();
    }

    private void updateNotification() {
        String mode = wsServer.isHybridMode() ? "混合模式 (Termux Bridge)" : "独立模式";
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Fusion Bridge 运行中")
                .setContentText("模式: " + mode)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification);
    }

    // === 剪贴板监听 ===

    private void startClipboardMonitor() {
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        // 方案 A: 使用 OnPrimaryClipChangedListener (推荐，实时回调)
        clipboardManager.addPrimaryClipChangedListener(() -> {
            try {
                ClipData clip = clipboardManager.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null && !text.toString().equals(lastClipboardText)) {
                        lastClipboardText = text.toString();

                        JSONObject msg = new JSONObject();
                        msg.put("type", "clipboard");
                        msg.put("source", "phone");
                        msg.put("content", text.toString());

                        // 检测是否是 URL
                        String trimmed = text.toString().trim();
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                            msg.put("contentType", "url");
                        } else {
                            msg.put("contentType", "text");
                        }

                        wsServer.broadcast(msg.toString());
                        Log.d(TAG, "剪贴板变化: " + text.toString().substring(0, Math.min(50, text.length())));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "剪贴板监听错误", e);
            }
        });

        Log.i(TAG, "剪贴板监听已启动");
    }

    // === 通话监听 ===

    private void startTelephonyMonitor() {
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // SDK 31+: TelephonyCallback + CallStateListener
            telephonyManager.registerTelephonyCallback(getMainExecutor(),
                    new FusionCallStateCallback());
        } else {
            // SDK < 31: 使用已废弃的 PhoneStateListener
            telephonyManager.listen(new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    String stateStr;
                    switch (state) {
                        case TelephonyManager.CALL_STATE_RINGING:
                            stateStr = "RINGING";
                            break;
                        case TelephonyManager.CALL_STATE_OFFHOOK:
                            stateStr = "OFFHOOK";
                            break;
                        default:
                            stateStr = "IDLE";
                            break;
                    }
                    try {
                        JSONObject msg = new JSONObject();
                        msg.put("type", "telephony");
                        msg.put("state", stateStr);
                        wsServer.broadcast(msg.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "发送通话状态失败", e);
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        }

        Log.i(TAG, "通话监听已启动");
    }

    /**
     * SDK 31+ 通话状态回调
     * 必须同时继承 TelephonyCallback 和实现 CallStateListener 接口
     */
    private class FusionCallStateCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        private String lastState = "IDLE";

        @Override
        public void onCallStateChanged(int state) {
            String stateStr;
            switch (state) {
                case TelephonyManager.CALL_STATE_RINGING:
                    stateStr = "RINGING";
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    stateStr = "OFFHOOK";
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                default:
                    stateStr = "IDLE";
                    break;
            }

            if (!stateStr.equals(lastState)) {
                lastState = stateStr;
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "telephony");
                    msg.put("state", stateStr);
                    wsServer.broadcast(msg.toString());
                    Log.d(TAG, "通话状态: " + stateStr);
                } catch (Exception e) {
                    Log.e(TAG, "发送通话状态失败", e);
                }
            }
        }
    }

    // === 处理 PC 端发来的命令 ===

    private void handleClientCommand(String type, String data) {
        handler.post(() -> {
            try {
                switch (type) {
                    case "clipboard_set":
                        // PC → Phone 设置剪贴板
                        JSONObject clipObj = new JSONObject(data);
                        String text = clipObj.getString("content");
                        ClipData clip = ClipData.newPlainText("text", text);
                        clipboardManager.setPrimaryClip(clip);
                        lastClipboardText = text;  // 防止回弹
                        Log.d(TAG, "已设置手机剪贴板");
                        break;

                    case "open_url":
                        // PC → Phone 打开链接
                        JSONObject urlObj = new JSONObject(data);
                        String url = urlObj.getString("url");
                        Intent urlIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        urlIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(urlIntent);
                        Log.d(TAG, "已打开链接: " + url);
                        break;

                    case "ping":
                        JSONObject pong = new JSONObject();
                        pong.put("type", "pong");
                        pong.put("timestamp", System.currentTimeMillis());
                        wsServer.broadcast(pong.toString());
                        break;

                    case "ring":
                        // 播放铃声音量最大
                        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        int maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING);
                        am.setStreamVolume(android.media.AudioManager.STREAM_RING, maxVol, 0);
                        android.media.Ringtone ringtone = android.media.RingtoneManager.getRingtone(this,
                                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE));
                        ringtone.play();
                        handler.postDelayed(() -> ringtone.stop(), 5000);
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "处理客户端命令失败", e);
            }
        });
    }

    // === 通知渠道 ===

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Fusion Bridge 服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持 Fusion Bridge 服务运行");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }
}
