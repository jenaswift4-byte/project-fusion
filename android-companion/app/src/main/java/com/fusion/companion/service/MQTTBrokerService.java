package com.fusion.companion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MQTT Broker 服务
 * 轻量级内置 MQTT 代理实现，运行在 1883 端口
 * 支持发布/订阅模式，支持多客户端连接
 * 注意：使用内置 Java ServerSocket 实现轻量级 MQTT 中转，无需外部 Broker 库
 */
public class MQTTBrokerService extends Service {

    private static final String TAG = "MQTTBroker";
    private static final int DEFAULT_PORT = 1883;
    private static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "mqtt_broker_channel";

    // 服务运行状态
    private static AtomicBoolean running = new AtomicBoolean(false);

    // 本地 MQTT 客户端（用于内部消息转发，连接到外部 Broker）
    private MqttClient localClient;

    // 客户端连接跟踪
    private ConcurrentHashMap<String, Long> connectedClients;

    // 客户端输出流（用于消息转发）
    private final ConcurrentHashMap<String, DataOutputStream> clientOutputStreams = new ConcurrentHashMap<>();

    // 客户端订阅关系: clientId → 订阅的 topic 列表（支持通配符 # +）
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> clientSubscriptions = new ConcurrentHashMap<>();

    // 主线程 Handler
    private Handler handler;

    // 线程池
    private ExecutorService executorService;

    // 内置简单 TCP 服务器（用于本地消息转发）
    private ServerSocket serverSocket;

    /**
     * 获取 Broker 运行状态
     */
    public static boolean isRunning() {
        return running.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "MQTT Broker Service 创建");
        handler = new Handler(Looper.getMainLooper());
        connectedClients = new ConcurrentHashMap<>();
        executorService = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "MQTT Broker Service 启动请求");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        executorService.submit(this::startBroker);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "MQTT Broker Service 销毁");
        stopBroker();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 启动简单 TCP 监听（作为轻量级 MQTT 代理）
     */
    private void startBroker() {
        if (running.get()) {
            Log.w(TAG, "Broker 已在运行，跳过启动");
            return;
        }

        try {
            serverSocket = new ServerSocket(DEFAULT_PORT);
            running.set(true);
            Log.i(TAG, "MQTT Broker 已启动 - 端口：" + DEFAULT_PORT);

            handler.post(this::updateNotification);

            // 接受客户端连接
            while (running.get() && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    String clientId = client.getInetAddress().getHostAddress() + ":" + client.getPort();
                    connectedClients.put(clientId, System.currentTimeMillis());
                    executorService.submit(() -> handleClient(client, clientId));
                    Log.d(TAG, "新客户端连接：" + clientId);
                } catch (IOException e) {
                    if (running.get()) {
                        Log.w(TAG, "接受连接异常", e);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Broker 启动失败", e);
            running.set(false);
            stopSelf();
        }
    }

    /**
     * 处理客户端连接（MQTT v3.1.1 协议 stub - 支持 CONNECT/CONNACK/PUBLISH/SUBSCRIBE/PINGREQ/PINGRESP/DISCONNECT）
     */
    private void handleClient(Socket client, String clientId) {
        try {
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            // 注册输出流（用于消息转发）
            clientOutputStreams.put(clientId, out);
            clientSubscriptions.putIfAbsent(clientId, new CopyOnWriteArrayList<>());

            byte[] buffer = new byte[4096];
            int len;
            while (running.get() && !client.isClosed()) {
                len = in.read(buffer);
                if (len < 0) break;
                
                if (len < 2) continue;
                byte packetType = (byte) ((buffer[0] >> 4) & 0x0F);
                
                Log.d(TAG, "收到来自 " + clientId + " 的数据，长度：" + len + ", 类型：" + packetType);
                
                switch (packetType) {
                    case 1: // CONNECT
                        // 回复 CONNACK: session_present=0, return_code=0 (Connection Accepted)
                        out.write(new byte[]{(byte) 0x20, 0x02, 0x00, 0x00});
                        out.flush();
                        Log.d(TAG, "发送 CONNACK 给 " + clientId);
                        break;
                    case 3: // PUBLISH
                        // 解析 MQTT 变长 remaining length
                        int remainingLen = 0;
                        int multiplier = 1;
                        int rlOffset = 1;
                        while (rlOffset < len) {
                            byte encodedByte = buffer[rlOffset];
                            remainingLen += (encodedByte & 0x7F) * multiplier;
                            multiplier *= 128;
                            rlOffset++;
                            if ((encodedByte & 0x80) == 0) break;
                        }
                        int headerLen = rlOffset; // fixed header + remaining length 字节数
                        
                        // QoS 标志
                        int qosFlag = (buffer[0] >> 1) & 0x03;
                        
                        // QoS > 0 时回复 PUBACK (type 4)
                        if (qosFlag > 0) {
                            // PUBLISH QoS 1/2 包含 2 字节 msgId（topic 长度之后）
                            // 先找到 msgId 的位置
                            if (len > headerLen + 2) {
                                int topicLen = ((buffer[headerLen] & 0xFF) << 8) | (buffer[headerLen + 1] & 0xFF);
                                int msgIdOffset = headerLen + 2 + topicLen;
                                if (len > msgIdOffset + 1) {
                                    byte msgIdHi = buffer[msgIdOffset];
                                    byte msgIdLo = buffer[msgIdOffset + 1];
                                    // PUBACK: 0x40, 0x02, msgIdHi, msgIdLo
                                    out.write(new byte[]{0x40, 0x02, msgIdHi, msgIdLo});
                                    out.flush();
                                    int msgId = ((msgIdHi & 0xFF) << 8) | (msgIdLo & 0xFF);
                                    Log.d(TAG, "发送 PUBACK 给 " + clientId + " msgId=" + msgId);
                                }
                            }
                        }
                        
                        Log.d(TAG, "PUBLISH 原始数据: len=" + len + " headerLen=" + headerLen + " remainingLen=" + remainingLen);
                        
                        if (len > headerLen + 2) {
                            int topicLen = ((buffer[headerLen] & 0xFF) << 8) | (buffer[headerLen + 1] & 0xFF);
                            Log.d(TAG, "PUBLISH topicLen=" + topicLen + " 需要 len > " + (headerLen + 2 + topicLen) + " 实际 len=" + len);
                            if (topicLen > 0 && len > headerLen + 2 + topicLen) {
                                String topic = new String(buffer, headerLen + 2, topicLen);
                                
                                int payloadStart = headerLen + 2 + topicLen;
                                if (qosFlag > 0) payloadStart += 2; // skip msgId
                                int payloadLen = len - payloadStart;
                                String payload = payloadLen > 0 ? new String(buffer, payloadStart, payloadLen) : "";
                                Log.d(TAG, "PUBLISH topic=" + topic + " qos=" + qosFlag + " payload=" + payload);

                                // 转发原始 PUBLISH 包给其他订阅的客户端
                                broadcastToClients(topic, buffer, len, qosFlag, clientId);
                            } else {
                                Log.w(TAG, "PUBLISH 解析条件不满足: topicLen=" + topicLen + " len=" + len);
                            }
                        } else {
                            Log.w(TAG, "PUBLISH 数据太短: len=" + len + " headerLen=" + headerLen);
                        }
                        break;
                    case 8: // SUBSCRIBE
                        // 解析 MQTT 变长 remaining length
                        int subRemLen = 0;
                        int subMultiplier = 1;
                        int subRlOffset = 1;
                        while (subRlOffset < len) {
                            byte encByte = buffer[subRlOffset];
                            subRemLen += (encByte & 0x7F) * subMultiplier;
                            subMultiplier *= 128;
                            subRlOffset++;
                            if ((encByte & 0x80) == 0) break;
                        }
                        int subHeaderLen = subRlOffset;
                        
                        if (len > subHeaderLen + 2) {
                            int msgId = ((buffer[subHeaderLen] & 0xFF) << 8) | (buffer[subHeaderLen + 1] & 0xFF);
                            // 解析 topic filters: 每个是 2-byte length + topic + 1-byte QoS
                            int offset = subHeaderLen + 2;
                            int topicCount = 0;
                            while (offset + 2 < len) {
                                int tLen = ((buffer[offset] & 0xFF) << 8) | (buffer[offset + 1] & 0xFF);
                                offset += 2;
                                if (offset + tLen < len) {
                                    String topicFilter = new String(buffer, offset, tLen);
                                    CopyOnWriteArrayList<String> subs = clientSubscriptions.get(clientId);
                                    if (subs != null && !subs.contains(topicFilter)) {
                                        subs.add(topicFilter);
                                    }
                                    topicCount++;
                                    Log.d(TAG, "客户端 " + clientId + " 订阅: " + topicFilter);
                                }
                                offset += tLen + 1; // +1 for QoS byte
                            }
                            // 回复 SUBACK: packet_id + return_code for each topic
                            byte[] suback = new byte[4 + topicCount];
                            suback[0] = (byte) 0x90;
                            suback[1] = (byte) (2 + topicCount);
                            suback[2] = (byte) (msgId >> 8);
                            suback[3] = (byte) (msgId & 0xFF);
                            for (int i = 0; i < topicCount; i++) {
                                suback[4 + i] = 0x00; // QoS 0 accepted
                            }
                            out.write(suback);
                            out.flush();
                            Log.d(TAG, "发送 SUBACK 给 " + clientId + " (" + topicCount + " topics)");
                        }
                        break;
                    case 12: // PINGREQ
                        // 回复 PINGRESP
                        out.write(new byte[]{(byte) 0xD0, 0x00});
                        out.flush();
                        break;
                    case 14: // DISCONNECT
                        Log.d(TAG, "客户端 " + clientId + " 主动断开");
                        return;
                    default:
                        Log.d(TAG, "未处理的 MQTT 包类型: " + packetType);
                        break;
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "客户端断开：" + clientId);
        } finally {
            connectedClients.remove(clientId);
            clientOutputStreams.remove(clientId);
            clientSubscriptions.remove(clientId);
            try { client.close(); } catch (IOException ignored) {}
        }
    }
    
    /**
     * 广播 MQTT 消息给其他已连接的客户端（实现真正的 topic 匹配和消息转发）
     */
    private void broadcastToClients(String topic, byte[] rawData, int dataLen, int qosFlag, String senderId) {
        Log.d(TAG, "广播消息 topic=" + topic + " (from " + senderId + ")");

        for (ConcurrentHashMap.Entry<String, CopyOnWriteArrayList<String>> entry : clientSubscriptions.entrySet()) {
            String subClientId = entry.getKey();
            if (subClientId.equals(senderId)) continue; // 不转发给发送者

            CopyOnWriteArrayList<String> subscriptions = entry.getValue();
            if (subscriptions == null) continue;

            boolean matched = false;
            for (String filter : subscriptions) {
                if (topicMatches(topic, filter)) {
                    matched = true;
                    break;
                }
            }

            if (matched) {
                DataOutputStream clientOut = clientOutputStreams.get(subClientId);
                if (clientOut != null) {
                    try {
                        // 构造 PUBLISH 包转发（使用原始包数据）
                        clientOut.write(rawData, 0, dataLen);
                        clientOut.flush();
                        Log.d(TAG, "已转发给 " + subClientId);
                    } catch (IOException e) {
                        Log.w(TAG, "转发给 " + subClientId + " 失败: " + e.getMessage());
                        clientOutputStreams.remove(subClientId);
                    }
                }
            }
        }
    }

    /**
     * MQTT topic 通配符匹配
     * 支持 # (多级) 和 + (单级)
     */
    private boolean topicMatches(String topic, String filter) {
        if (filter.equals(topic) || filter.equals("#")) return true;

        String[] topicParts = topic.split("/");
        String[] filterParts = filter.split("/");

        int i = 0;
        for (; i < filterParts.length; i++) {
            if (filterParts[i].equals("#")) return true; // # 匹配剩余所有
            if (i >= topicParts.length) return false;
            if (!filterParts[i].equals("+") && !filterParts[i].equals(topicParts[i])) return false;
        }
        return i == topicParts.length;
    }

    /**
     * 停止 Broker
     */
    public void stopBroker() {
        if (!running.get()) return;

        try {
            running.set(false);

            if (localClient != null && localClient.isConnected()) {
                try {
                    localClient.disconnect();
                    localClient.close();
                } catch (MqttException e) {
                    Log.e(TAG, "断开 MQTT 客户端失败", e);
                }
                localClient = null;
            }

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

            if (executorService != null) {
                executorService.shutdownNow();
            }

            connectedClients.clear();
            Log.i(TAG, "MQTT Broker 完全停止");

        } catch (IOException e) {
            Log.e(TAG, "停止 Broker 失败", e);
        }
    }

    /**
     * 发布消息到指定主题（通过 Paho MQTT 客户端）
     */
    public boolean publishMessage(String topic, byte[] payload, int qos) {
        try {
            if (localClient == null || !localClient.isConnected()) {
                initLocalClient();
            }
            if (localClient != null && localClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload);
                message.setQos(qos);
                localClient.publish(topic, message);
                return true;
            }
        } catch (MqttException e) {
            Log.e(TAG, "发布消息失败", e);
        }
        return false;
    }

    /**
     * 发布文本消息
     */
    public boolean publishTextMessage(String topic, String message, int qos) {
        return publishMessage(topic, message.getBytes(), qos);
    }

    /**
     * 初始化本地 MQTT 客户端
     */
    private void initLocalClient() {
        try {
            localClient = new MqttClient(
                "tcp://127.0.0.1:" + DEFAULT_PORT,
                "fusion-internal-" + System.currentTimeMillis(),
                new MemoryPersistence()
            );
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(5);
            localClient.connect(opts);
            Log.d(TAG, "本地 MQTT 客户端已连接");
        } catch (MqttException e) {
            Log.w(TAG, "本地 MQTT 客户端连接失败（Broker 可能未就绪）: " + e.getMessage());
            localClient = null;
        }
    }

    /**
     * 获取连接数
     */
    public int getConnectionCount() {
        return connectedClients.size();
    }

    /**
     * 检查 Broker 是否可用
     */
    public boolean isBrokerAvailable() {
        return running.get() && serverSocket != null && !serverSocket.isClosed();
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "MQTT Broker 服务", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("MQTT Broker 后台运行，提供设备间通信服务");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MQTT Broker 运行中")
            .setContentText("端口 1883 · 低功耗后台服务")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
    }

    private void updateNotification() {
        int count = getConnectionCount();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MQTT Broker 运行中")
            .setContentText("端口 1883 · 连接数：" + count)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification);
    }

    /**
     * MQTT 消息回调接口
     */
    public interface MqttMessageCallback {
        void onMessageReceived(String topic, byte[] payload);
    }

    // 主题订阅管理
    private final ConcurrentHashMap<String, java.util.List<MqttMessageCallback>> topicSubscribers = new ConcurrentHashMap<>();

    /**
     * 订阅主题
     * @param topic 主题
     * @param callback 消息回调
     * @param qos QoS 级别
     * @return 是否订阅成功
     */
    public boolean subscribeTopic(String topic, MqttMessageCallback callback, int qos) {
        topicSubscribers.computeIfAbsent(topic, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(callback);
        Log.i(TAG, "已订阅主题：" + topic);
        return true;
    }

    /**
     * 取消订阅主题
     * @param topic 主题
     */
    public void unsubscribeTopic(String topic) {
        topicSubscribers.remove(topic);
        Log.i(TAG, "已取消订阅主题：" + topic);
    }

    /**
     * 向所有订阅者分发消息
     */
    private void dispatchMessage(String topic, byte[] payload) {
        java.util.List<MqttMessageCallback> callbacks = topicSubscribers.get(topic);
        if (callbacks != null) {
            for (MqttMessageCallback cb : callbacks) {
                try {
                    cb.onMessageReceived(topic, payload);
                } catch (Exception e) {
                    Log.e(TAG, "分发消息回调异常", e);
                }
            }
        }
        // 检查通配符订阅
        for (java.util.Map.Entry<String, java.util.List<MqttMessageCallback>> entry : topicSubscribers.entrySet()) {
            String subTopic = entry.getKey();
            if (subTopic.endsWith("#") && topic.startsWith(subTopic.substring(0, subTopic.length() - 1))) {
                for (MqttMessageCallback cb : entry.getValue()) {
                    try {
                        cb.onMessageReceived(topic, payload);
                    } catch (Exception e) {
                        Log.e(TAG, "分发通配符消息回调异常", e);
                    }
                }
            }
        }
    }
}
