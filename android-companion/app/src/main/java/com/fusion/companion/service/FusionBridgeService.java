package com.fusion.companion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.SurfaceControl;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;

import com.fusion.companion.FusionWebSocketServer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.util.Base64;

/**
 * Fusion Bridge 前台服务
 * 运行 WebSocket Server，实时转发手机事件到 PC
 *
 * 支持事件: 通知 / 剪贴板 / 通话 / 电池 / 短信 / 传感器
 * 支持命令: clipboard_set / open_url / screenshot / send_sms / ping / ring
 */
public class FusionBridgeService extends Service {

    private static final String TAG = "FusionBridge";
    private static final String CHANNEL_ID = "fusion_bridge";
    private static final int NOTIFICATION_ID = 1;
    private static final int DEFAULT_PORT = 17532;

    private static boolean running = false;
    private static FusionWebSocketServer staticWsServer;

    private FusionWebSocketServer wsServer;
    private ClipboardManager clipboardManager;
    private TelephonyManager telephonyManager;
    private SharedPreferences prefs;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    // MQTT Broker 服务
    private MQTTBrokerService mqttBrokerService;
    private static boolean mqttBrokerRunning = false;

    // 传感器采集器
    private SensorCollector sensorCollector;
    private static boolean sensorCollectionRunning = false;

    // 电池状态
    private int lastBatteryLevel = -1;
    private String lastBatteryStatus = "";
    private Handler batteryHandler;
    private Runnable batteryPollTask;

    // 短信接收器
    private FusionSmsReceiver smsReceiver;

    // 上次剪贴板内容 (去重)
    private String lastClipboardText = "";

    public static boolean isRunning() {
        return running;
    }

    public static FusionWebSocketServer getWebSocketServer() {
        return staticWsServer;
    }

    /**
     * 获取 MQTT Broker 运行状态
     * @return true 如果 MQTT Broker 正在运行
     */
    public static boolean isMQTTBrokerRunning() {
        return mqttBrokerRunning;
    }

    /**
     * 获取传感器采集运行状态
     * @return true 如果传感器采集正在运行
     */
    public static boolean isSensorCollectionRunning() {
        return sensorCollectionRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = getSharedPreferences("fusion", MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());

        // 获取 WakeLock 防止 MIUI 冻结进程
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "fusion:bridge_wakelock"
        );
        wakeLock.acquire();

        // 请求电池优化白名单
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                );
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "请求电池优化白名单失败: " + e.getMessage());
            }
        }

        // 创建前台通知
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Fusion Bridge 运行中")
                .setContentText("WebSocket 服务已启动 · 通知/剪贴板/通话/电池/短信实时同步")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        // 启动各监听模块
        startWebSocketServer();
        staticWsServer = wsServer;
        startClipboardMonitor();
        startTelephonyMonitor();
        startBatteryMonitor();
        startSmsMonitor();
        startMQTTBroker();  // 启动 MQTT Broker
        startSensorCollection();  // 启动传感器采集

        Log.i(TAG, "Fusion Bridge 服务已启动 (含电池 + 短信+MQTT Broker+ 传感器采集)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;

        // 停止电池轮询
        if (batteryHandler != null && batteryPollTask != null) {
            batteryHandler.removeCallbacks(batteryPollTask);
        }

        // 注销短信接收器
        if (smsReceiver != null) {
            try {
                unregisterReceiver(smsReceiver);
            } catch (Exception e) {
                Log.w(TAG, "注销短信接收器失败", e);
            }
        }

        if (wsServer != null) {
            try {
                wsServer.stopAll();
            } catch (Exception e) {
                Log.e(TAG, "停止 WebSocket 失败", e);
            }
        }
        staticWsServer = null;

        // 停止 MQTT Broker
        stopMQTTBroker();

        // 停止传感器采集
        stopSensorCollection();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }

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
            handleClientCommand(type, data);
        });

        new Thread(() -> {
            try {
                int termuxPort = port + 1;
                java.net.Socket testSocket = new java.net.Socket();
                testSocket.connect(
                    new java.net.InetSocketAddress("127.0.0.1", termuxPort),
                    2000
                );
                testSocket.close();

                Log.i(TAG, "检测到 Termux Bridge (端口 " + termuxPort + ")，进入混合模式");
                wsServer.setHybridMode(true);
                wsServer.connectToTermux(termuxPort);
            } catch (Exception e) {
                Log.i(TAG, "未检测到 Termux Bridge，独立模式");
                try {
                    wsServer.start();
                    Log.i(TAG, "WebSocket Server 已启动: 端口 " + port);
                } catch (Exception ex) {
                    Log.e(TAG, "WebSocket Server 启动失败", ex);
                }
            }

            handler.post(() -> updateNotification());
        }).start();
    }

    private void updateNotification() {
        String mode = wsServer.isHybridMode() ? "混合模式 (Termux)" : "独立模式";
        String batteryInfo = lastBatteryLevel >= 0 ? (" · 🔋" + lastBatteryLevel + "%") : "";
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Fusion Bridge 运行中")
                .setContentText(mode + batteryInfo)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification);
    }

    // === 剪贴板监听 ===

    private void startClipboardMonitor() {
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

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
            telephonyManager.registerTelephonyCallback(getMainExecutor(),
                    new FusionCallStateCallback());
        } else {
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

    // === 电池监听 (主动轮询 + 充电变化广播) ===

    private void startBatteryMonitor() {
        batteryHandler = new Handler(Looper.getMainLooper());

        // 首次立即获取
        pollBattery();

        // 每 30 秒轮询
        batteryPollTask = new Runnable() {
            @Override
            public void run() {
                pollBattery();
                batteryHandler.postDelayed(this, 30000);
            }
        };
        batteryHandler.postDelayed(batteryPollTask, 30000);

        // 注册充电状态变化广播 (即时响应插拔充电器)
        IntentFilter batteryFilter = new IntentFilter();
        batteryFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        batteryFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        batteryFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                pollBattery();
            }
        }, batteryFilter);

        Log.i(TAG, "电池监听已启动 (30s 轮询 + 充电变化广播)");
    }

    private void pollBattery() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

            // 充电状态
            Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int status = batteryIntent != null ? batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) : -1;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                               status == BatteryManager.BATTERY_STATUS_FULL;
            String statusStr = charging ? "charging" : "discharging";

            // 温度
            int temp = batteryIntent != null ? batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
            float tempC = temp / 10.0f;

            // 只在状态变化时推送 (或首次)
            if (level != lastBatteryLevel || !statusStr.equals(lastBatteryStatus)) {
                lastBatteryLevel = level;
                lastBatteryStatus = statusStr;

                JSONObject msg = new JSONObject();
                msg.put("type", "battery");
                msg.put("level", level);
                msg.put("status", statusStr);
                msg.put("temperature", tempC);
                msg.put("timestamp", System.currentTimeMillis());
                wsServer.broadcast(msg.toString());

                Log.d(TAG, "电池: " + level + "% " + statusStr + " " + tempC + "°C");
                handler.post(() -> updateNotification());
            }
        } catch (Exception e) {
            Log.e(TAG, "电池轮询错误", e);
        }
    }

    // === 短信监听 (BroadcastReceiver) ===

    private void startSmsMonitor() {
        smsReceiver = new FusionSmsReceiver();
        IntentFilter smsFilter = new IntentFilter();
        smsFilter.addAction("android.provider.Telephony.SMS_RECEIVED");
        smsFilter.setPriority(999);  // 高优先级
        registerReceiver(smsReceiver, smsFilter);

        Log.i(TAG, "短信监听已启动");
    }

    /**
     * 短信广播接收器
     * 收到短信后通过 WebSocket 推送到 PC
     */
    public class FusionSmsReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

            try {
                android.telephony.SmsMessage[] msgs = android.telephony.SmsMessage.Intents.getMessagesFromIntent(intent);
                if (msgs == null || msgs.length == 0) return;

                StringBuilder body = new StringBuilder();
                String address = msgs[0].getOriginatingAddress();
                long timestamp = msgs[0].getTimestampMillis();

                for (android.telephony.SmsMessage msg : msgs) {
                    body.append(msg.getMessageBody());
                }

                JSONObject msg = new JSONObject();
                msg.put("type", "sms");
                msg.put("address", address != null ? address : "未知");
                msg.put("body", body.toString());
                msg.put("timestamp", timestamp);
                wsServer.broadcast(msg.toString());

                Log.d(TAG, "短信: [" + address + "] " + body.toString().substring(0, Math.min(50, body.length())));
            } catch (Exception e) {
                Log.e(TAG, "短信接收错误", e);
            }
        }
    }

    // === 处理 PC 端发来的命令 ===

    private void handleClientCommand(String type, String data) {
        handler.post(() -> {
            try {
                switch (type) {
                    case "clipboard_set":
                        JSONObject clipObj = new JSONObject(data);
                        String text = clipObj.getString("content");
                        ClipData clip = ClipData.newPlainText("text", text);
                        clipboardManager.setPrimaryClip(clip);
                        lastClipboardText = text;
                        Log.d(TAG, "已设置手机剪贴板");
                        break;

                    case "open_url":
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
                        // 手机响铃 (静默模式也响)
                        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        int maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING);
                        am.setStreamVolume(android.media.AudioManager.STREAM_RING, maxVol, 0);
                        android.media.Ringtone ringtone = android.media.RingtoneManager.getRingtone(this,
                                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE));
                        ringtone.play();
                        handler.postDelayed(() -> ringtone.stop(), 5000);
                        break;

                    case "screenshot":
                        // 手机截图 → 保存文件 → 通知 PC 拉取
                        takeScreenshot();
                        break;

                    case "send_sms":
                        // PC → 手机发送短信
                        JSONObject smsObj = new JSONObject(data);
                        String smsAddr = smsObj.getString("address");
                        String smsBody = smsObj.getString("body");
                        sendSms(smsAddr, smsBody);
                        break;

                    case "battery_query":
                        // PC 主动查询电池状态
                        pollBattery();
                        break;

                    case "open_app":
                        // PC → 手机打开指定应用
                        JSONObject appObj = new JSONObject(data);
                        String pkg = appObj.getString("package");
                        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(launchIntent);
                            Log.d(TAG, "已打开应用: " + pkg);
                        } else {
                            Log.w(TAG, "应用未安装: " + pkg);
                        }
                        break;

                    case "keyevent":
                        // PC → 手机发送按键事件
                        JSONObject keyObj = new JSONObject(data);
                        int keycode = keyObj.getInt("keycode");
                        Runtime.getRuntime().exec("input keyevent " + keycode);
                        Log.d(TAG, "按键事件: " + keycode);
                        break;

                    case "volume":
                        // PC → 手机调整音量
                        JSONObject volObj = new JSONObject(data);
                        String direction = volObj.optString("direction", "up");
                        android.media.AudioManager audioMgr = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        int stream = android.media.AudioManager.STREAM_MUSIC;
                        int adjust = "up".equals(direction)
                            ? android.media.AudioManager.ADJUST_RAISE
                            : android.media.AudioManager.ADJUST_LOWER;
                        audioMgr.adjustVolume(adjust, 0);
                        Log.d(TAG, "音量: " + direction);
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "处理客户端命令失败 [" + type + "]", e);
            }
        });
    }

    // === 截图 (通过 ADB 的 screencap) ===

    private void takeScreenshot() {
        new Thread(() -> {
            try {
                String filename = "fusion_screenshot_" + System.currentTimeMillis() + ".png";
                String path = "/sdcard/Download/" + filename;

                Process p = Runtime.getRuntime().exec(new String[]{"screencap", "-p", path});
                p.waitFor();

                // 通知 PC 端截图已就绪，可以 ADB pull
                JSONObject msg = new JSONObject();
                msg.put("type", "screenshot");
                msg.put("path", path);
                msg.put("filename", filename);
                msg.put("timestamp", System.currentTimeMillis());
                wsServer.broadcast(msg.toString());

                Log.d(TAG, "截图已保存: " + path);
            } catch (Exception e) {
                Log.e(TAG, "截图失败", e);
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "screenshot_error");
                    msg.put("error", e.getMessage());
                    wsServer.broadcast(msg.toString());
                } catch (Exception ex) { /* ignore */ }
            }
        }).start();
    }

    // === 发送短信 ===

    private void sendSms(String address, String body) {
        try {
            android.telephony.SmsManager smsManager = android.telephony.SmsManager.getDefault();
            smsManager.sendTextMessage(address, null, body, null, null);

            JSONObject msg = new JSONObject();
            msg.put("type", "sms_sent");
            msg.put("address", address);
            msg.put("body", body);
            msg.put("timestamp", System.currentTimeMillis());
            wsServer.broadcast(msg.toString());

            Log.d(TAG, "短信已发送: [" + address + "] " + body.substring(0, Math.min(30, body.length())));
        } catch (Exception e) {
            Log.e(TAG, "发送短信失败", e);
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "sms_error");
                msg.put("error", e.getMessage());
                wsServer.broadcast(msg.toString());
            } catch (Exception ex) { /* ignore */ }
        }
    }

    // === 通知渠道 ===

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Fusion Bridge 服务",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("保持 Fusion Bridge 服务运行，实时同步通知/剪贴板/通话/电池/短信");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    // === MQTT Broker 管理 ===

    /**
     * 启动 MQTT Broker 服务
     * 在后台运行，监听 1883 端口
     */
    private void startMQTTBroker() {
        if (mqttBrokerRunning) {
            Log.w(TAG, "MQTT Broker 已在运行，跳过启动");
            return;
        }

        try {
            // 启动 MQTT Broker 服务
            Intent brokerIntent = new Intent(this, MQTTBrokerService.class);
            startForegroundService(brokerIntent);
            
            // 延迟检查是否启动成功
            handler.postDelayed(() -> {
                if (MQTTBrokerService.isRunning()) {
                    mqttBrokerRunning = true;
                    Log.i(TAG, "MQTT Broker 服务已成功启动 (端口 1883)");
                } else {
                    Log.w(TAG, "MQTT Broker 服务启动失败");
                }
            }, 2000);
            
        } catch (Exception e) {
            Log.e(TAG, "启动 MQTT Broker 失败", e);
        }
    }

    /**
     * 停止 MQTT Broker 服务
     * 优雅关闭所有客户端连接
     */
    private void stopMQTTBroker() {
        if (!mqttBrokerRunning) {
            Log.w(TAG, "MQTT Broker 未运行，跳过停止");
            return;
        }

        try {
            Intent brokerIntent = new Intent(this, MQTTBrokerService.class);
            stopService(brokerIntent);
            mqttBrokerRunning = false;
            Log.i(TAG, "MQTT Broker 服务已停止");
            
        } catch (Exception e) {
            Log.e(TAG, "停止 MQTT Broker 失败", e);
        }
    }

    /**
     * 获取 MQTT Broker 服务实例
     * 可用于发布/订阅操作
     * @return MQTTBrokerService 实例或 null
     */
    public MQTTBrokerService getMQTTBrokerService() {
        // 注意：由于是独立服务，需要通过绑定或广播获取实例
        // 这里返回 null，实际使用需要通过 bindService
        return null;
    }

    // === 传感器采集管理 ===

    /**
     * 启动传感器采集服务
     * 使用事件驱动，低功耗设计
     */
    private void startSensorCollection() {
        if (sensorCollectionRunning) {
            Log.w(TAG, "传感器采集已在运行，跳过启动");
            return;
        }

        try {
            // 获取设备 ID (使用设备型号 + 序列号)
            String deviceId = getDeviceId();
            
            // 创建传感器采集器
            sensorCollector = new SensorCollector(this, deviceId);
            
            // 设置采集间隔为 5 秒
            sensorCollector.setCollectionInterval(5000);
            
            // 启动采集
            boolean success = sensorCollector.startCollection();
            
            if (success) {
                sensorCollectionRunning = true;
                int sensorCount = sensorCollector.getRegisteredSensorCount();
                Log.i(TAG, "传感器采集已启动 - 设备 ID: " + deviceId + ", 传感器数量：" + sensorCount);
            } else {
                Log.w(TAG, "传感器采集启动失败");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "启动传感器采集失败", e);
        }
    }

    /**
     * 停止传感器采集服务
     * 优雅关闭所有传感器监听
     */
    private void stopSensorCollection() {
        if (!sensorCollectionRunning) {
            Log.w(TAG, "传感器采集未运行，跳过停止");
            return;
        }

        try {
            if (sensorCollector != null) {
                sensorCollector.stopCollection();
                sensorCollector = null;
            }
            
            sensorCollectionRunning = false;
            Log.i(TAG, "传感器采集已停止");
            
        } catch (Exception e) {
            Log.e(TAG, "停止传感器采集失败", e);
        }
    }

    /**
     * 获取设备唯一标识符
     * 使用设备型号 + 序列号组合
     * @return 设备 ID 字符串
     */
    private String getDeviceId() {
        // 使用设备型号 + 序列号作为设备 ID
        String model = android.os.Build.MODEL;
        String serial = getSerialNumber();
        
        // 格式化为友好的设备名称
        String deviceId = "phone-" + model.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase() + "-" + serial;
        
        Log.d(TAG, "生成设备 ID: " + deviceId);
        return deviceId;
    }

    /**
     * 获取设备序列号
     * @return 设备序列号
     */
    private String getSerialNumber() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                return android.os.Build.getSerial();
            } else {
                return android.os.Build.SERIAL;
            }
        } catch (Exception e) {
            // 如果无法获取序列号，使用时间戳后 6 位
            return String.valueOf(System.currentTimeMillis() % 1000000);
        }
    }
}
