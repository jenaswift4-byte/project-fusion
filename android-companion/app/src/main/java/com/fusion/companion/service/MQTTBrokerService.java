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

import org.eclipse.paho.broker.v5.BrokerOptions;
import org.eclipse.paho.broker.v5.MqttMoxieServer;
import org.eclipse.paho.broker.v5.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT Broker 服务
 * 轻量级 MQTT 服务器实现，运行在 1883 端口
 * 支持发布/订阅模式，支持多客户端连接（至少 10 个并发）
 * 后台运行，低功耗设计
 * 
 * 功能特性:
 * - 标准 MQTT 3.1.1 协议支持
 * - 发布/订阅消息模式
 * - 多客户端并发连接（默认支持 10+ 连接）
 * - 内存持久化（轻量级，无文件 IO）
 * - 低电量消耗（无轮询，事件驱动）
 * - 后台服务运行（前台通知保活）
 */
public class MQTTBrokerService extends Service {

    private static final String TAG = "MQTTBroker";
    private static final int DEFAULT_PORT = 1883;
    private static final int MAX_CONNECTIONS = 20;  // 最大连接数
    private static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "mqtt_broker_channel";

    // MQTT Broker 实例
    private MqttMoxieServer broker;
    
    // 服务运行状态
    private static AtomicBoolean running = new AtomicBoolean(false);
    
    // 本地 MQTT 客户端（用于内部消息转发）
    private MqttClient localClient;
    
    // 客户端连接跟踪（用于监控和调试）
    private ConcurrentHashMap<String, Long> connectedClients;
    
    // 主线程 Handler（用于 UI 操作）
    private Handler handler;

    /**
     * 获取 Broker 运行状态
     * @return true 如果 Broker 正在运行
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "MQTT Broker Service 启动请求");
        
        // 创建前台通知（防止被系统杀死）
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 在后台线程启动 Broker（避免阻塞主线程）
        new Thread(this::startBroker).start();
        
        // 返回 STICKY 保持服务运行
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
     * 启动 MQTT Broker
     * 配置参数：
     * - 端口：1883（标准 MQTT 端口）
     * - 最大连接数：20
     * - 持久化：内存模式（轻量级）
     * - 超时：60 秒
     */
    private void startBroker() {
        if (running.get()) {
            Log.w(TAG, "Broker 已在运行，跳过启动");
            return;
        }

        try {
            // 配置 Broker 选项
            BrokerOptions options = new BrokerOptions();
            options.setServerName("Fusion-MQTT-Broker");
            options.setPort(DEFAULT_PORT);
            options.setMaxConnections(MAX_CONNECTIONS);  // 支持最多 20 个连接
            options.setSendBufferSize(8192);  // 8KB 发送缓冲区
            options.setReceiveBufferSize(8192);  // 8KB 接收缓冲区
            options.setConnectionTimeout(60);  // 60 秒超时
            options.setPersistenceEnabled(false);  // 禁用持久化（轻量级）
            
            // 创建 Broker 实例（使用内存持久化）
            broker = new MqttMoxieServer();
            
            // 启动 Broker
            broker.startBroker(options);
            running.set(true);
            
            Log.i(TAG, "MQTT Broker 已启动 - 端口：" + DEFAULT_PORT + ", 最大连接：" + MAX_CONNECTIONS);
            
            // 初始化本地客户端（用于内部消息转发）
            initLocalClient();
            
            handler.post(this::updateNotification);
            
        } catch (Exception e) {
            Log.e(TAG, "MQTT Broker 启动失败", e);
            running.set(false);
            stopSelf();
        }
    }

    /**
     * 停止 MQTT Broker
     * 优雅关闭所有连接
     */
    public void stopBroker() {
        if (!running.get()) {
            Log.w(TAG, "Broker 未运行，跳过停止");
            return;
        }

        try {
            // 停止本地客户端
            if (localClient != null && localClient.isConnected()) {
                localClient.disconnect();
                localClient.close();
                localClient = null;
                Log.d(TAG, "本地 MQTT 客户端已断开");
            }
            
            // 停止 Broker
            if (broker != null) {
                broker.stop();
                broker = null;
                Log.d(TAG, "MQTT Broker 已停止");
            }
            
            running.set(false);
            connectedClients.clear();
            
            Log.i(TAG, "MQTT Broker 完全停止");
            
        } catch (Exception e) {
            Log.e(TAG, "停止 Broker 失败", e);
        }
    }

    /**
     * 初始化本地 MQTT 客户端
     * 用于服务内部发布/订阅消息
     */
    private void initLocalClient() {
        try {
            // 连接到本地 Broker
            localClient = new MqttClient(
                "tcp://127.0.0.1:" + DEFAULT_PORT,
                "fusion-internal-client",
                new MemoryPersistence()
            );
            
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setConnectionTimeout(10);
            connOpts.setKeepAliveInterval(30);
            
            localClient.connect(connOpts);
            Log.d(TAG, "本地 MQTT 客户端已连接");
            
        } catch (MqttException e) {
            Log.e(TAG, "本地 MQTT 客户端初始化失败", e);
        }
    }

    /**
     * 发布消息到指定主题
     * @param topic MQTT 主题
     * @param payload 消息内容
     * @param qos 服务质量等级 (0, 1, 2)
     * @return true 如果发布成功
     */
    public boolean publishMessage(String topic, byte[] payload, int qos) {
        if (!running.get() || localClient == null || !localClient.isConnected()) {
            Log.w(TAG, "Broker 未运行或客户端未连接，无法发布消息");
            return false;
        }

        try {
            MqttMessage message = new MqttMessage(payload);
            message.setQos(qos);
            message.setRetained(false);
            
            localClient.publish(topic, message);
            Log.d(TAG, "消息已发布到主题：" + topic + ", 大小：" + payload.length + " bytes");
            return true;
            
        } catch (MqttException e) {
            Log.e(TAG, "发布消息失败", e);
            return false;
        }
    }

    /**
     * 发布文本消息（便捷方法）
     * @param topic MQTT 主题
     * @param message 文本消息
     * @param qos 服务质量等级
     * @return true 如果发布成功
     */
    public boolean publishTextMessage(String topic, String message, int qos) {
        return publishMessage(topic, message.getBytes(), qos);
    }

    /**
     * 订阅指定主题
     * @param topic 主题（支持通配符 + 和 #）
     * @param callback 消息回调接口
     * @param qos 服务质量等级
     * @return true 如果订阅成功
     */
    public boolean subscribeTopic(String topic, MqttMessageCallback callback, int qos) {
        if (!running.get() || localClient == null || !localClient.isConnected()) {
            Log.w(TAG, "Broker 未运行或客户端未连接，无法订阅");
            return false;
        }

        try {
            localClient.subscribe(topic, qos, (topic1, message) -> {
                if (callback != null) {
                    callback.onMessageReceived(topic1, message.getPayload());
                }
            });
            
            Log.d(TAG, "已订阅主题：" + topic);
            return true;
            
        } catch (MqttException e) {
            Log.e(TAG, "订阅主题失败", e);
            return false;
        }
    }

    /**
     * 取消订阅指定主题
     * @param topic 主题
     * @return true 如果取消成功
     */
    public boolean unsubscribeTopic(String topic) {
        if (!running.get() || localClient == null || !localClient.isConnected()) {
            return false;
        }

        try {
            localClient.unsubscribe(topic);
            Log.d(TAG, "已取消订阅主题：" + topic);
            return true;
            
        } catch (MqttException e) {
            Log.e(TAG, "取消订阅失败", e);
            return false;
        }
    }

    /**
     * 获取当前连接的客户端数量
     * @return 连接数
     */
    public int getConnectionCount() {
        return connectedClients.size();
    }

    /**
     * 检查 Broker 是否可用
     * @return true 如果 Broker 运行中且可连接
     */
    public boolean isBrokerAvailable() {
        return running.get() && broker != null;
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MQTT Broker 服务",
                NotificationManager.IMPORTANCE_LOW  // 低重要性，不干扰用户
            );
            channel.setDescription("MQTT Broker 后台运行，提供设备间通信服务");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.setVibrationEnabled(false);
            
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    /**
     * 创建前台通知
     * @return Notification 对象
     */
    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MQTT Broker 运行中")
            .setContentText("端口 1883 · 低功耗后台服务")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
    }

    /**
     * 更新通知显示连接数
     */
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
        nm.notify(NOTIFICATION_ID, notification);
    }

    /**
     * MQTT 消息回调接口
     */
    public interface MqttMessageCallback {
        void onMessageReceived(String topic, byte[] payload);
    }
}
