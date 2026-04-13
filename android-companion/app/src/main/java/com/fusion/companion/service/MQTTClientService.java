package com.fusion.companion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.google.gson.Gson;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT 客户端服务
 * 为所有手机设备提供 MQTT 客户端功能，连接到卧室手机 C 的 MQTT Broker
 * 
 * 功能特性：
 * - 自动连接到指定 Broker（1883 端口）
 * - 自动重连机制（指数退避算法）
 * - 后台低功耗运行
 * - 发布/订阅消息
 * - 定期发布传感器数据（每 5 秒）
 * - 定期发布设备心跳（每 30 秒）
 * - 自动订阅控制命令主题
 * 
 * 订阅主题：
 * - devices/{deviceId} - 设备控制命令
 * - fusion/broadcast - 广播消息
 * - fusion/mode - 模式切换通知
 * 
 * 发布主题：
 * - sensors/{deviceId}/{sensorType} - 传感器数据（每 5 秒）
 * - devices/{deviceId}/heartbeat - 设备心跳（每 30 秒）
 * 
 * @author Fusion
 * @version 1.0
 */
public class MQTTClientService extends Service implements SensorEventListener {
    
    private static final String TAG = "MQTTClient";
    
    // ==================== 配置常量 ====================
    
    // 默认 Broker 地址（先尝试 PC，失败 fallback 本地）
    private static final String DEFAULT_BROKER_HOST = "192.168.1.100";
    private static final int DEFAULT_BROKER_PORT = 1883;
    private static final String FALLBACK_BROKER_HOST = "127.0.0.1";
    private static final int FALLBACK_BROKER_PORT = 1883;
    private boolean usingFallback = false;
    
    // 重连配置（指数退避）
    private static final int INITIAL_RECONNECT_DELAY = 1000;  // 初始重连延迟 1 秒
    private static final int MAX_RECONNECT_DELAY = 60000;     // 最大重连延迟 60 秒
    private static final int RECONNECT_MULTIPLIER = 2;        // 退避倍数
    
    // 发布间隔配置
    private static final long SENSOR_PUBLISH_INTERVAL = 5000;   // 传感器数据发布间隔 5 秒
    private static final long HEARTBEAT_PUBLISH_INTERVAL = 30000; // 心跳发布间隔 30 秒
    
    // 通知配置
    private static final int NOTIFICATION_ID = 3;
    private static final String CHANNEL_ID = "mqtt_client_channel";
    
    // SharedPreferences 配置
    private static final String PREFS_NAME = "mqtt_client_prefs";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_BROKER_HOST = "broker_host";
    private static final String KEY_BROKER_PORT = "broker_port";
    
    // ==================== 成员变量 ====================
    
    // MQTT 客户端
    private MqttClient mqttClient;
    private MqttCallbackExtended mqttCallback;
    
    // 服务运行状态
    private static AtomicBoolean running = new AtomicBoolean(false);
    
    // 连接状态
    private AtomicBoolean connected = new AtomicBoolean(false);
    
    // 重连控制
    private AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    
    // 主线程 Handler
    private Handler handler;
    private HandlerThread handlerThread;
    
    // 传感器管理器
    private SensorManager sensorManager;
    
    // 电池管理器
    private BatteryManager batteryManager;
    
    // 电源管理器（用于 WakeLock 低功耗设计）
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    
    // 设备 ID
    private String deviceId;
    
    // Broker 配置
    private String brokerHost;
    private int brokerPort;
    
    // 传感器数据缓存
    private ConcurrentHashMap<String, Float> sensorDataCache;
    
    // 定时任务 Runnable
    private Runnable sensorPublishRunnable;
    private Runnable heartbeatPublishRunnable;
    
    // Gson 用于 JSON 序列化
    private Gson gson;
    
    // 当前重连延迟
    private int currentReconnectDelay;
    
    // 消息回调监听器
    private MqttMessageListener messageListener;
    
    /**
     * MQTT 消息监听器接口
     */
    public interface MqttMessageListener {
        void onMessageReceived(String topic, byte[] payload);
    }
    
    /**
     * 获取服务运行状态
     * @return true 如果服务正在运行
     */
    public static boolean isRunning() {
        return running.get();
    }
    
    /**
     * 获取连接状态
     * @return true 如果已连接到 Broker
     */
    public boolean isConnected() {
        return connected.get() && mqttClient != null && mqttClient.isConnected();
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "MQTT Client Service 创建");
        
        handlerThread = new HandlerThread("MQTTClientHandler");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        sensorDataCache = new ConcurrentHashMap<>();
        gson = new Gson();
        currentReconnectDelay = INITIAL_RECONNECT_DELAY;
        
        // 初始化系统服务
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        
        // 加载配置
        loadConfiguration();
        
        // 初始化传感器监听
        initSensors();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "MQTT Client Service 启动请求");
        
        // 创建前台通知（防止被系统杀死）
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 获取 WakeLock（低功耗设计，防止 CPU 休眠）
        acquireWakeLock();
        
        // 在后台线程启动 MQTT 连接
        new Thread(this::connectToBroker).start();
        
        // 返回 STICKY 保持服务运行
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "MQTT Client Service 销毁");
        
        // 停止定时任务
        stopPeriodicTasks();
        
        // 断开 MQTT 连接
        disconnectFromBroker();
        
        // 释放 WakeLock
        releaseWakeLock();
        
        // 注销传感器监听
        unregisterSensors();
        
        running.set(false);
        
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    // ==================== 配置管理 ====================
    
    /**
     * 加载配置信息
     */
    private void loadConfiguration() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        // 生成设备 ID（基于设备型号和随机数）
        String savedDeviceId = prefs.getString(KEY_DEVICE_ID, null);
        if (savedDeviceId == null) {
            // 生成新设备 ID：device-型号后 6 位 - 随机 4 位
            String model = android.os.Build.MODEL.replaceAll("\\s+", "-").toLowerCase();
            if (model.length() > 10) {
                model = model.substring(0, 10);
            }
            String randomSuffix = String.format("%04d", (int)(Math.random() * 10000));
            savedDeviceId = "device-" + model + "-" + randomSuffix;
            
            prefs.edit().putString(KEY_DEVICE_ID, savedDeviceId).apply();
            Log.i(TAG, "生成新设备 ID: " + savedDeviceId);
        }
        deviceId = savedDeviceId;
        
        // 加载 Broker 配置
        brokerHost = prefs.getString(KEY_BROKER_HOST, DEFAULT_BROKER_HOST);
        brokerPort = prefs.getInt(KEY_BROKER_PORT, DEFAULT_BROKER_PORT);
        
        Log.i(TAG, "配置加载完成 - 设备 ID: " + deviceId + ", Broker: " + brokerHost + ":" + brokerPort);
    }
    
    /**
     * 更新 Broker 配置
     * @param host Broker 主机地址
     * @param port Broker 端口
     */
    public void updateBrokerConfig(String host, int port) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_BROKER_HOST, host)
            .putInt(KEY_BROKER_PORT, port)
            .apply();
        
        brokerHost = host;
        brokerPort = port;
        
        Log.i(TAG, "Broker 配置更新：" + host + ":" + port);
        
        // 重新连接
        if (running.get()) {
            disconnectFromBroker();
            shouldReconnect.set(true);
            connectToBroker();
        }
    }
    
    /**
     * 处理 PC Broker 发现消息
     * 当手机连上本地 Broker 后，收到 PC 端广播的 Broker 地址，
     * 自动更新配置并重连 SensorCollector 到 PC Broker
     * 
     * 消息格式：
     * {
     *   "host": "192.168.1.100",
     *   "port": 1883,
     *   "action": "connect",
     *   "timestamp": 1234567890
     * }
     */
    private void handleBrokerDiscovery(String payload) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(payload);
            String host = json.getString("host");
            int port = json.optInt("port", 1883);
            String action = json.optString("action", "connect");
            
            // 如果收到的就是当前 Broker，不需要切换
            if (host.equals(brokerHost) && port == brokerPort) {
                Log.d(TAG, "Broker 发现: 已连接到目标 Broker " + host + ":" + port);
                return;
            }
            
            // 只处理 connect action
            if (!"connect".equals(action)) {
                return;
            }
            
            Log.i(TAG, "发现 PC Broker: " + host + ":" + port + "，当前: " + brokerHost + ":" + brokerPort);
            
            // 如果当前在 fallback 模式，立即断开并重连到 PC Broker
            if (usingFallback) {
                usingFallback = false;
                Log.i(TAG, "PC Broker 上线，从 fallback 切回 PC Broker");
                try {
                    if (mqttClient != null && mqttClient.isConnected()) {
                        mqttClient.disconnect();
                    }
                } catch (Exception ignored) {}
                brokerHost = host;
                brokerPort = port;
                connectToBroker();
                return;
            }
            
            // 更新 SharedPreferences (SensorCollector 下次读取时生效)
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                .putString(KEY_BROKER_HOST, host)
                .putInt(KEY_BROKER_PORT, port)
                .apply();
            
            // 更新 SensorCollector 的 Broker URL
            if (FusionBridgeService.getSensorCollector() != null) {
                FusionBridgeService.getSensorCollector().setBrokerUrl("tcp://" + host + ":" + port);
                Log.i(TAG, "已更新 SensorCollector Broker URL: tcp://" + host + ":" + port);
            }
            
            Log.i(TAG, "PC Broker 地址已保存到 SharedPreferences，SensorCollector 将在下次启动时连接");
            
        } catch (org.json.JSONException e) {
            Log.e(TAG, "解析 Broker 发现消息失败: " + e.getMessage());
        }
    }
    
    // ==================== MQTT 连接管理 ====================
    
    /**
     * 连接到 MQTT Broker
     * 使用指数退避算法自动重连
     */
    private void connectToBroker() {
        if (running.get() && connected.get()) {
            Log.w(TAG, "MQTT 已连接，跳过连接");
            return;
        }
        
        running.set(true);
        shouldReconnect.set(true);
        
        String brokerUrl = "tcp://" + brokerHost + ":" + brokerPort;
        Log.i(TAG, "开始连接 MQTT Broker: " + brokerUrl);
        
        MqttConnectOptions connOpts = new MqttConnectOptions();
        connOpts.setCleanSession(true);
        connOpts.setConnectionTimeout(30);
        connOpts.setKeepAliveInterval(60);
        connOpts.setAutomaticReconnect(false);
        connOpts.setMaxInflight(100);
        
        try {
            // 创建 MQTT 客户端
            mqttClient = new MqttClient(
                brokerUrl,
                deviceId,
                new MemoryPersistence()  // 使用内存持久化（轻量级）
            );
            
            // 设置回调
            mqttCallback = new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    Log.i(TAG, "MQTT 连接成功 - 重连：" + reconnect + ", 服务器：" + serverURI);
                    connected.set(true);
                    currentReconnectDelay = INITIAL_RECONNECT_DELAY;  // 重置重连延迟
                    
                    handler.post(() -> updateNotification(true));
                    
                    // 启动定时任务
                    startPeriodicTasks();
                    
                    // 通知监听器
                    if (messageListener != null) {
                        messageListener.onMessageReceived("system/connect", "connected".getBytes());
                    }
                }
                
                @Override
                public void connectionLost(Throwable throwable) {
                    Log.w(TAG, "MQTT 连接丢失：" + throwable.getMessage());
                    connected.set(false);
                    
                    handler.post(() -> updateNotification(false));
                    
                    // 停止定时任务
                    stopPeriodicTasks();
                    
                    // 如果当前是 fallback 模式，重置为优先连 PC Broker
                    if (usingFallback) {
                        usingFallback = false;
                        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        brokerHost = sp.getString(KEY_BROKER_HOST, DEFAULT_BROKER_HOST);
                        brokerPort = sp.getInt(KEY_BROKER_PORT, DEFAULT_BROKER_PORT);
                        Log.i(TAG, "Fallback 断开，恢复 PC Broker 配置: " + brokerHost + ":" + brokerPort);
                    }
                    
                    // 自动重连
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                }
                
                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    String payloadStr = new String(message.getPayload());
                    Log.d(TAG, "收到消息 - 主题：" + topic + ", 内容：" + payloadStr);
                    
                    // 处理 PC Broker 发现消息
                    if ("fusion/pc/broker".equals(topic)) {
                        handleBrokerDiscovery(payloadStr);
                    }
                    
                    // 处理设备控制命令
                    if (topic.startsWith("devices/") && topic.endsWith("/heartbeat")) {
                        // 心跳消息，忽略
                        return;
                    }
                    
                    // ═══ PC 命令通道 (fusion/cmd/*) ═══
                    if (topic.startsWith("fusion/cmd/")) {
                        handleCommand(topic, payloadStr);
                        return;
                    }
                    
                    // ═══ 音频控制命令 (fusion/audio/*) ═══
                    if (topic.startsWith("fusion/audio/")) {
                        handleAudioCommand(topic, payloadStr);
                        return;
                    }
                    
                    // ═══ 摄像头控制命令 (fusion/camera/*) ═══
                    if (topic.startsWith("fusion/camera/")) {
                        handleCameraCommand(topic, payloadStr);
                        return;
                    }
                    
                    // 通知监听器
                    if (messageListener != null) {
                        messageListener.onMessageReceived(topic, message.getPayload());
                    }
                }
                
                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    Log.d(TAG, "消息投递完成 - ID: " + token.getMessageId());
                }
            };
            
            mqttClient.setCallback(mqttCallback);
            mqttClient.connect(connOpts);
            Log.i(TAG, "MQTT 连接请求已发送");
            
        } catch (MqttException e) {
            Log.e(TAG, "MQTT 连接失败：" + e.getMessage());
            connected.set(false);
            
            // Fallback: 尝试连接本地 Broker
            if (!usingFallback && !DEFAULT_BROKER_HOST.equals(FALLBACK_BROKER_HOST)) {
                Log.i(TAG, "PC Broker 不可达，尝试 Fallback 到本地 Broker: " + FALLBACK_BROKER_HOST + ":" + FALLBACK_BROKER_PORT);
                usingFallback = true;
                try {
                    String fallbackUrl = "tcp://" + FALLBACK_BROKER_HOST + ":" + FALLBACK_BROKER_PORT;
                    mqttClient = new MqttClient(fallbackUrl, deviceId + "-fallback", new MemoryPersistence());
                    mqttClient.setCallback(mqttCallback);  // 复用同一回调
                    mqttClient.connect(connOpts);
                    brokerHost = FALLBACK_BROKER_HOST;
                    brokerPort = FALLBACK_BROKER_PORT;
                    Log.i(TAG, "Fallback 本地 Broker 连接成功: " + fallbackUrl);
                    return;
                } catch (MqttException e2) {
                    Log.w(TAG, "Fallback 本地 Broker 也失败: " + e2.getMessage());
                }
            }
            
            // 调度重连
            if (shouldReconnect.get()) {
                scheduleReconnect();
            }
        }
    }
    
    /**
     * 调度重连（指数退避算法）
     * 退避策略：1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s, ...
     */
    private void scheduleReconnect() {
        int delay = currentReconnectDelay;
        Log.i(TAG, "计划重连 - 延迟：" + delay + "ms");
        
        handler.postDelayed(() -> {
            if (shouldReconnect.get() && !connected.get()) {
                Log.i(TAG, "开始重连...");
                connectToBroker();
            }
        }, delay);
        
        // 增加下次重连延迟（指数退避）
        currentReconnectDelay = Math.min(currentReconnectDelay * RECONNECT_MULTIPLIER, MAX_RECONNECT_DELAY);
    }
    
    /**
     * 断开与 Broker 的连接
     */
    private void disconnectFromBroker() {
        shouldReconnect.set(false);
        
        if (mqttClient != null) {
            try {
                // 停止定时任务
                stopPeriodicTasks();
                
                // 断开连接
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect(3000);  // 3 秒超时断开
                    Log.i(TAG, "MQTT 已断开连接");
                }
                
                // 关闭客户端
                mqttClient.close();
                mqttClient = null;
                connected.set(false);
                
                handler.post(() -> updateNotification(false));
                
            } catch (MqttException e) {
                Log.e(TAG, "断开连接失败", e);
            }
        }
    }
    
    // ==================== 消息发布和订阅 ====================
    
    /**
     * 发布消息到指定主题
     * @param topic MQTT 主题
     * @param payload 消息内容（字节数组）
     * @param qos 服务质量等级 (0=至多一次，1=至少一次，2=只有一次)
     * @return true 如果发布成功
     */
    public boolean publishMessage(String topic, byte[] payload, int qos) {
        if (!connected.get() || mqttClient == null || !mqttClient.isConnected()) {
            Log.w(TAG, "MQTT 未连接，无法发布消息");
            return false;
        }
        
        try {
            MqttMessage message = new MqttMessage(payload);
            message.setQos(qos);
            message.setRetained(false);
            
            mqttClient.publish(topic, message);
            Log.d(TAG, "消息已发布 - 主题：" + topic + ", 大小：" + payload.length + " bytes");
            return true;
            
        } catch (MqttException e) {
            Log.e(TAG, "发布消息失败：" + e.getMessage(), e);
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
     * 发布 JSON 消息（便捷方法）
     * @param topic MQTT 主题
     * @param data 数据对象（会自动转为 JSON）
     * @param qos 服务质量等级
     * @return true 如果发布成功
     */
    public boolean publishJsonMessage(String topic, Object data, int qos) {
        String json = gson.toJson(data);
        return publishTextMessage(topic, json, qos);
    }
    
    /**
     * 订阅指定主题
     * @param topic MQTT 主题（支持通配符 + 和 #）
     * @param qos 服务质量等级
     * @return true 如果订阅成功
     */
    public boolean subscribeTopic(String topic, int qos) {
        if (!connected.get() || mqttClient == null || !mqttClient.isConnected()) {
            Log.w(TAG, "MQTT 未连接，无法订阅");
            return false;
        }
        
        try {
            mqttClient.subscribe(topic, qos);
            Log.d(TAG, "已订阅主题：" + topic);
            return true;
            
        } catch (MqttException e) {
            Log.e(TAG, "订阅主题失败：" + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 取消订阅指定主题
     * @param topic MQTT 主题
     * @return true 如果取消成功
     */
    public boolean unsubscribeTopic(String topic) {
        if (!connected.get() || mqttClient == null || !mqttClient.isConnected()) {
            return false;
        }
        
        try {
            mqttClient.unsubscribe(topic);
            Log.d(TAG, "已取消订阅主题：" + topic);
            return true;
            
        } catch (MqttException e) {
            Log.e(TAG, "取消订阅失败：" + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 自动订阅系统主题
     * 在连接成功后自动调用
     */
    private void autoSubscribeTopics() {
        Log.i(TAG, "自动订阅系统主题");
        
        // 订阅设备控制命令
        String deviceTopic = "devices/" + deviceId;
        subscribeTopic(deviceTopic, 1);
        
        // 订阅广播消息
        subscribeTopic("fusion/broadcast", 1);
        
        // 订阅模式切换通知
        subscribeTopic("fusion/mode", 1);
        
        // 订阅 PC Broker 发现消息 (PC Bridge 启动时会广播)
        subscribeTopic("fusion/pc/broker", 1);
        
        // 订阅摄像头控制命令
        subscribeTopic("fusion/camera/" + deviceId + "/command", 1);
        
        // 订阅音频控制命令
        subscribeTopic("fusion/audio/" + deviceId + "/command", 1);
        
        // 订阅 PC 命令通道 (fusion/cmd/{deviceId} 和 fusion/cmd/broadcast)
        subscribeTopic("fusion/cmd/" + deviceId, 1);
        subscribeTopic("fusion/cmd/broadcast", 1);
        
        // 订阅全设备命令 (通配符)
        subscribeTopic("fusion/cmd/#", 1);
        
        Log.i(TAG, "已订阅主题: " + deviceTopic + ", fusion/broadcast, fusion/mode, fusion/pc/broker, fusion/camera/" + deviceId + "/command, fusion/audio/" + deviceId + "/command, fusion/cmd/#");
    }
    
    // ==================== 传感器数据发布 ====================
    
    /**
     * 初始化传感器监听
     */
    private void initSensors() {
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager 不可用");
            return;
        }
        
        // 注册常用传感器
        registerSensor(Sensor.TYPE_TEMPERATURE);
        registerSensor(Sensor.TYPE_LIGHT);
        registerSensor(Sensor.TYPE_PRESSURE);
        registerSensor(Sensor.TYPE_RELATIVE_HUMIDITY);
        registerSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
        
        Log.i(TAG, "传感器监听初始化完成");
    }
    
    /**
     * 注册传感器监听
     * @param sensorType 传感器类型
     */
    private void registerSensor(int sensorType) {
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "已注册传感器：" + getSensorTypeName(sensorType));
        } else {
            Log.d(TAG, "设备不支持传感器：" + getSensorTypeName(sensorType));
        }
    }
    
    /**
     * 注销传感器监听
     */
    private void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.d(TAG, "传感器监听已注销");
        }
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == null) {
            return;
        }
        
        String sensorType = getSensorTypeName(event.sensor.getType());
        float value = event.values[0];
        
        // 缓存传感器数据
        sensorDataCache.put(sensorType, value);
        Log.d(TAG, "传感器数据更新：" + sensorType + " = " + value);
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 精度变化回调（暂不处理）
    }
    
    /**
     * 获取传感器类型名称
     * @param type 传感器类型常量
     * @return 传感器名称
     */
    private String getSensorTypeName(int type) {
        switch (type) {
            case Sensor.TYPE_TEMPERATURE:
                return "temperature";
            case Sensor.TYPE_LIGHT:
                return "light";
            case Sensor.TYPE_PRESSURE:
                return "pressure";
            case Sensor.TYPE_RELATIVE_HUMIDITY:
                return "humidity";
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
                return "ambient_temperature";
            default:
                return "sensor_" + type;
        }
    }
    
    /**
     * 启动定时任务
     */
    private void startPeriodicTasks() {
        Log.i(TAG, "启动定时任务");
        
        // 自动订阅主题
        autoSubscribeTopics();
        
        // 传感器数据发布任务（每 5 秒）
        sensorPublishRunnable = () -> {
            try {
                Log.d(TAG, "定时任务: 执行传感器数据发布, 缓存大小=" + sensorDataCache.size());
                publishSensorData();
            } catch (Exception e) {
                Log.e(TAG, "传感器发布异常: " + e.getMessage(), e);
            }
            handler.postDelayed(sensorPublishRunnable, SENSOR_PUBLISH_INTERVAL);
        };
        handler.post(sensorPublishRunnable);
        Log.d(TAG, "传感器发布任务已提交到 Handler");
        
        // 心跳发布任务（每 30 秒）
        heartbeatPublishRunnable = () -> {
            try {
                publishHeartbeat();
            } catch (Exception e) {
                Log.e(TAG, "心跳发布异常: " + e.getMessage(), e);
            }
            handler.postDelayed(heartbeatPublishRunnable, HEARTBEAT_PUBLISH_INTERVAL);
        };
        handler.post(heartbeatPublishRunnable);
    }
    
    /**
     * 停止定时任务
     */
    private void stopPeriodicTasks() {
        Log.i(TAG, "停止定时任务");
        
        if (sensorPublishRunnable != null) {
            handler.removeCallbacks(sensorPublishRunnable);
            sensorPublishRunnable = null;
        }
        
        if (heartbeatPublishRunnable != null) {
            handler.removeCallbacks(heartbeatPublishRunnable);
            heartbeatPublishRunnable = null;
        }
    }
    
    /**
     * 发布传感器数据
     */
    private void publishSensorData() {
        Log.d(TAG, "publishSensorData() 被调用, 缓存大小=" + sensorDataCache.size() + ", 为空=" + sensorDataCache.isEmpty() + ", 连接=" + connected.get());
        if (sensorDataCache.isEmpty()) {
            Log.d(TAG, "暂无传感器数据，跳过发布");
            return;
        }
        
        if (!connected.get() || mqttClient == null || !mqttClient.isConnected()) {
            Log.w(TAG, "MQTT 未连接，跳过传感器发布");
            return;
        }
        
        int published = 0;
        for (Map.Entry<String, Float> entry : sensorDataCache.entrySet()) {
            String sensorType = entry.getKey();
            float value = entry.getValue();
            
            // 构建发布主题
            String topic = "sensors/" + deviceId + "/" + sensorType;
            
            // 构建消息内容
            SensorDataMessage data = new SensorDataMessage();
            data.device_id = deviceId;
            data.sensor_type = sensorType;
            data.value = value;
            data.unit = getSensorUnit(sensorType);
            data.timestamp = System.currentTimeMillis();
            
            // 发布 JSON 消息
            boolean ok = publishJsonMessage(topic, data, 1);
            if (ok) published++;
            
            Log.d(TAG, "发布传感器数据：" + topic + " - " + value + " " + data.unit + (ok ? " ✅" : " ❌"));
        }
        Log.d(TAG, "传感器发布完成: " + published + "/" + sensorDataCache.size() + " 成功");
    }
    
    /**
     * 获取传感器单位
     * @param sensorType 传感器类型名称
     * @return 单位字符串
     */
    private String getSensorUnit(String sensorType) {
        switch (sensorType) {
            case "temperature":
            case "ambient_temperature":
                return "°C";
            case "light":
                return "lux";
            case "pressure":
                return "hPa";
            case "humidity":
                return "%";
            default:
                return "";
        }
    }
    
    /**
     * 发布设备心跳
     */
    private void publishHeartbeat() {
        // 获取电池电量
        int batteryLevel = getBatteryLevel();
        
        // 构建心跳消息
        HeartbeatMessage heartbeat = new HeartbeatMessage();
        heartbeat.device_id = deviceId;
        heartbeat.is_online = true;
        heartbeat.battery_level = batteryLevel;
        heartbeat.timestamp = System.currentTimeMillis();
        
        // 发布心跳
        String topic = "devices/" + deviceId + "/heartbeat";
        publishJsonMessage(topic, heartbeat, 1);
        
        Log.d(TAG, "发布心跳 - 电量：" + batteryLevel + "%");
    }
    
    /**
     * 获取电池电量百分比
     * @return 电量百分比（0-100）
     */
    private int getBatteryLevel() {
        if (batteryManager == null) {
            return -1;  // 不支持电池
        }
        
        try {
            Integer batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return batteryPct != null ? batteryPct : -1;
        } catch (Exception e) {
            Log.w(TAG, "获取电池电量失败：" + e.getMessage());
            return -1;
        }
    }
    
    // ==================== 低功耗设计 ====================
    
    /**
     * 获取 WakeLock（防止 CPU 休眠）
     */
    private void acquireWakeLock() {
        if (powerManager == null) {
            return;
        }
        
        try {
            // 使用 PARTIAL_WAKE_LOCK（只保持 CPU 运行，屏幕可以关闭）
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Fusion::MQTTClientService"
            );
            wakeLock.setReferenceCounted(false);  // 不使用引用计数
            wakeLock.acquire(10 * 60 * 1000L);  // 最多持有 10 分钟（超时自动释放）
            Log.d(TAG, "WakeLock 已获取（10 分钟超时）");
        } catch (Exception e) {
            Log.e(TAG, "获取 WakeLock 失败", e);
        }
    }
    
    /**
     * 释放 WakeLock
     */
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock 已释放");
            wakeLock = null;
        }
    }
    
    // ==================== 通知管理 ====================
    
    /**
     * 创建通知渠道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MQTT 客户端服务",
                NotificationManager.IMPORTANCE_LOW  // 低重要性，不干扰用户
            );
            channel.setDescription("MQTT 客户端后台运行，提供设备间通信服务");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            
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
            .setContentTitle("MQTT 客户端运行中")
            .setContentText("设备：" + deviceId + " · 低功耗后台服务")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
    }
    
    /**
     * 更新通知显示连接状态
     * @param isConnected 是否已连接
     */
    private void updateNotification(boolean isConnected) {
        String status = isConnected ? "已连接" : "连接中...";
        String brokerInfo = brokerHost + ":" + brokerPort;
        
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MQTT 客户端 " + status)
            .setContentText("设备：" + deviceId + " · Broker: " + brokerInfo)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
        
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification);
    }
    
    // ==================== 数据模型类 ====================
    
    /**
     * 传感器数据消息模型
     */
    public static class SensorDataMessage {
        public String device_id;      // 设备 ID
        public String sensor_type;    // 传感器类型
        public float value;           // 传感器数值
        public String unit;           // 单位
        public long timestamp;        // 时间戳
        
        @Override
        public String toString() {
            return "SensorDataMessage{" +
                    "device_id='" + device_id + '\'' +
                    ", sensor_type='" + sensor_type + '\'' +
                    ", value=" + value +
                    ", unit='" + unit + '\'' +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
    
    /**
     * 心跳消息模型
     */
    public static class HeartbeatMessage {
        public String device_id;      // 设备 ID
        public boolean is_online;     // 是否在线
        public int battery_level;     // 电池电量（0-100，-1 表示不支持）
        public long timestamp;        // 时间戳
        
        @Override
        public String toString() {
            return "HeartbeatMessage{" +
                    "device_id='" + device_id + '\'' +
                    ", is_online=" + is_online +
                    ", battery_level=" + battery_level +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
    
    // ==================== 公开方法 ====================
    
    /**
     * 设置消息监听器
     * @param listener 消息监听器
     */
    public void setMessageListener(MqttMessageListener listener) {
        this.messageListener = listener;
    }
    
    /**
     * 获取设备 ID
     * @return 设备 ID
     */
    public String getMqttDeviceId() {
        return deviceId;
    }
    
    /**
     * 获取 Broker 主机地址
     * @return Broker 主机地址
     */
    public String getBrokerHost() {
        return brokerHost;
    }
    
    /**
     * 获取 Broker 端口
     * @return Broker 端口
     */
    public int getBrokerPort() {
        return brokerPort;
    }
    
    /**
     * 获取最新的传感器数据
     * @param sensorType 传感器类型
     * @return 传感器数值，不存在返回 null
     */
    public Float getLatestSensorData(String sensorType) {
        return sensorDataCache.get(sensorType);
    }
    
    /**
     * 获取所有传感器数据
     * @return 传感器数据 Map
     */
    public Map<String, Float> getAllSensorData() {
        return new HashMap<>(sensorDataCache);
    }
    
    // ==================== PC 命令通道处理 ====================
    
    /**
     * 处理 PC 端发送的 MQTT 命令
     * 
     * 支持的命令:
     * - ping: 心跳测试
     * - ring: 铃声/振动
     * - open_url: 打开链接
     * - set_volume: 设置音量
     * - play_sound: 播放声音 (TTS)
     * - vibrate: 振动
     * - toast: 显示 Toast
     * - capture: 截图
     * - compute_task: 算力任务
     * - get_info: 获取设备信息
     */
    private void handleCommand(String topic, String payload) {
        try {
            org.json.JSONObject cmd = new org.json.JSONObject(payload);
            String action = cmd.optString("action", "");
            String cmdId = cmd.optString("id", "");
            org.json.JSONObject params = cmd.optJSONObject("params");
            if (params == null) params = new org.json.JSONObject();
            
            Log.i(TAG, "收到命令: " + action + " (id=" + cmdId + ")");
            
            // 检查目标是否是自己
            String target = cmd.optString("target", "");
            String broadcastTopic = "fusion/cmd/broadcast";
            
            // 广播命令或目标是本设备
            boolean isForMe = broadcastTopic.equals(topic) 
                || ("fusion/cmd/" + deviceId).equals(topic)
                || topic.startsWith("fusion/cmd/" + deviceId + "/");
            
            if (!isForMe && !broadcastTopic.equals(topic)) {
                // 不是给本设备的命令
                return;
            }
            
            org.json.JSONObject response = new org.json.JSONObject();
            response.put("cmd_id", cmdId);
            response.put("status", "ok");
            response.put("timestamp", System.currentTimeMillis());
            
            switch (action) {
                case "ping":
                    response.put("result", "pong");
                    break;
                    
                case "ring":
                    playRingtone();
                    response.put("result", "ringing");
                    break;
                    
                case "vibrate":
                    vibrateDevice(params.optInt("duration", 500));
                    response.put("result", "vibrated");
                    break;
                    
                case "toast":
                    showToast(params.optString("message", "Fusion Command"));
                    response.put("result", "shown");
                    break;
                    
                case "get_info":
                    response.put("result", getDeviceInfo());
                    break;
                    
                case "get_sensors":
                    response.put("result", getAllSensorData());
                    break;
                    
                case "set_volume":
                    int volume = params.optInt("level", 50);
                    setMediaVolume(volume);
                    response.put("result", "volume_set");
                    break;
                    
                case "play_sound":
                    String soundType = params.optString("type", "beep");
                    playSound(soundType, params);
                    response.put("result", "playing");
                    break;
                    
                case "compute_task":
                    // 算力卸载任务处理
                    String taskType = params.optString("task_type", "benchmark");
                    String result = handleComputeTask(taskType, params);
                    response.put("result", result);
                    break;
                    
                default:
                    response.put("status", "unknown_action");
                    response.put("result", "unsupported: " + action);
                    Log.w(TAG, "未知命令: " + action);
                    break;
            }
            
            // 发送命令响应
            String responseTopic = "fusion/cmd/" + deviceId + "/response";
            publishTextMessage(responseTopic, response.toString(), 1);
            Log.d(TAG, "命令响应已发送: " + action);
            
        } catch (Exception e) {
            Log.e(TAG, "处理命令失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理音频控制命令
     */
    private void handleAudioCommand(String topic, String payload) {
        try {
            org.json.JSONObject cmd = new org.json.JSONObject(payload);
            String action = cmd.optString("action", "");
            Log.i(TAG, "收到音频命令: " + action);
            
            switch (action) {
                case "start_mic":
                    startMicRecording();
                    break;
                case "stop_mic":
                    stopMicRecording();
                    break;
                case "play":
                    playMedia(cmd.optString("url", ""), cmd.optLong("position", -1));
                    break;
                case "pause":
                    pauseMedia();
                    break;
                case "stop":
                    stopMedia();
                    break;
                case "set_volume":
                    int vol = cmd.optInt("volume", 50);
                    setMediaVolume(vol);
                    break;
                case "play_tts":
                    speakText(cmd.optString("text", ""));
                    break;
                default:
                    Log.w(TAG, "未知音频命令: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理音频命令失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理摄像头控制命令
     */
    private void handleCameraCommand(String topic, String payload) {
        try {
            org.json.JSONObject cmd = new org.json.JSONObject(payload);
            String action = cmd.optString("action", "");
            Log.i(TAG, "收到摄像头命令: " + action);
            
            switch (action) {
                case "capture":
                    // 截图并通过 MQTT 发送
                    captureAndSendScreenshot();
                    break;
                case "start_stream":
                    // TODO: 启动视频流 (需要 native 代码)
                    Log.i(TAG, "视频流启动请求 (暂不支持)");
                    break;
                case "stop_stream":
                    Log.i(TAG, "视频流停止请求");
                    break;
                default:
                    Log.w(TAG, "未知摄像头命令: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理摄像头命令失败: " + e.getMessage(), e);
        }
    }
    
    // ==================== 命令执行实现 ====================
    
    private void playRingtone() {
        try {
            android.media.Ringtone ringtone = android.media.RingtoneManager.getRingtone(
                this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            );
            if (ringtone != null) {
                ringtone.play();
                handler.postDelayed(() -> {
                    if (ringtone.isPlaying()) ringtone.stop();
                }, 3000);
            }
        } catch (Exception e) {
            Log.e(TAG, "播放铃声失败: " + e.getMessage());
        }
    }
    
    private void vibrateDevice(int durationMs) {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, 128));
                } else {
                    vibrator.vibrate(durationMs);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "振动失败: " + e.getMessage());
        }
    }
    
    private void showToast(String message) {
        handler.post(() -> {
            try {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Toast 失败: " + e.getMessage());
            }
        });
    }
    
    private org.json.JSONObject getDeviceInfo() {
        org.json.JSONObject info = new org.json.JSONObject();
        try {
            info.put("device_id", deviceId);
            info.put("model", android.os.Build.MODEL);
            info.put("brand", android.os.Build.BRAND);
            info.put("android_version", android.os.Build.VERSION.RELEASE);
            info.put("sdk_int", android.os.Build.VERSION.SDK_INT);
            info.put("battery", getBatteryLevel());
            info.put("sensors", new org.json.JSONArray(getAllSensorData().keySet()));
        } catch (Exception e) {
            Log.e(TAG, "获取设备信息失败", e);
        }
        return info;
    }
    
    private void setMediaVolume(int level) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int targetVol = Math.max(0, Math.min(level, maxVol));
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0);
                Log.i(TAG, "音量已设置: " + targetVol + "/" + maxVol);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置音量失败: " + e.getMessage());
        }
    }
    
    private void playSound(String type, org.json.JSONObject params) {
        try {
            // 使用 ToneGenerator 播放系统音调 (不依赖音频文件，兼容 targetSdk 30)
            int toneType;
            switch (type) {
                case "alarm":   toneType = android.media.ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE; break;
                case "beep":    toneType = android.media.ToneGenerator.TONE_PROP_BEEP; break;
                case "confirm": toneType = android.media.ToneGenerator.TONE_PROP_ACK; break;
                case "error":   toneType = android.media.ToneGenerator.TONE_PROP_NACK; break;
                case "ring":    toneType = android.media.ToneGenerator.TONE_CDMA_PIP; break;
                default:        toneType = android.media.ToneGenerator.TONE_PROP_BEEP; break;
            }
            android.media.ToneGenerator toneGen = new android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_MUSIC, 50);
            toneGen.startTone(toneType, 500);
            handler.postDelayed(toneGen::release, 1000);
            Log.i(TAG, "播放声音: " + type + " (tone=" + toneType + ")");
        } catch (Exception e) {
            Log.e(TAG, "播放声音失败: " + e.getMessage());
        }
    }
    
    private void speakText(String text) {
        try {
            final android.speech.tts.TextToSpeech[] ttsHolder = new android.speech.tts.TextToSpeech[1];
            ttsHolder[0] = new android.speech.tts.TextToSpeech(this, status -> {
                if (status == android.speech.tts.TextToSpeech.SUCCESS && ttsHolder[0] != null) {
                    ttsHolder[0].speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "fusion_tts");
                    handler.postDelayed(() -> { if (ttsHolder[0] != null) ttsHolder[0].shutdown(); }, 5000);
                } else {
                    if (ttsHolder[0] != null) ttsHolder[0].shutdown();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "TTS 失败: " + e.getMessage());
        }
    }
    
    private void startMicRecording() {
        // 通过 ADB 或 WS 发送录音命令
        // 这里只做标记，实际录音由 FusionBridgeService 的 WebSocket 命令处理
        Log.i(TAG, "麦克风录音请求 (转交 FusionBridgeService)");
        if (FusionBridgeService.getWebSocketServer() != null) {
            try {
                org.json.JSONObject msg = new org.json.JSONObject();
                msg.put("type", "mic_control");
                msg.put("action", "start");
                FusionBridgeService.getWebSocketServer().broadcast(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "转发录音命令失败: " + e.getMessage());
            }
        }
    }
    
    private void stopMicRecording() {
        Log.i(TAG, "停止录音请求");
        if (FusionBridgeService.getWebSocketServer() != null) {
            try {
                org.json.JSONObject msg = new org.json.JSONObject();
                msg.put("type", "mic_control");
                msg.put("action", "stop");
                FusionBridgeService.getWebSocketServer().broadcast(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "转发停止录音命令失败: " + e.getMessage());
            }
        }
    }
    
    private void playMedia(String url, long position) {
        // 通过广播通知 MediaManager 播放
        try {
            Intent intent = new Intent("com.fusion.companion.action.PLAY_MEDIA");
            intent.setPackage(getPackageName());
            intent.putExtra("url", url);
            intent.putExtra("position", position);
            startService(intent);
            Log.i(TAG, "播放媒体: " + url);
        } catch (Exception e) {
            Log.e(TAG, "播放媒体失败: " + e.getMessage());
        }
    }
    
    private void pauseMedia() {
        try {
            Intent intent = new Intent("com.fusion.companion.action.PAUSE_MEDIA");
            intent.setPackage(getPackageName());
            startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "暂停媒体失败: " + e.getMessage());
        }
    }
    
    private void stopMedia() {
        try {
            Intent intent = new Intent("com.fusion.companion.action.STOP_MEDIA");
            intent.setPackage(getPackageName());
            startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "停止媒体失败: " + e.getMessage());
        }
    }
    
    private void captureAndSendScreenshot() {
        // 截图并发送到 MQTT
        new Thread(() -> {
            try {
                // 通过 ADB screencap 命令截图
                Process proc = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "screencap -p /sdcard/fusion_capture.png"}
                );
                proc.waitFor();
                
                // 读取截图文件并发送
                java.io.File file = new java.io.File("/sdcard/fusion_capture.png");
                if (file.exists()) {
                    byte[] imageData = new byte[(int) file.length()];
                    java.io.FileInputStream fis = new java.io.FileInputStream(file);
                    fis.read(imageData);
                    fis.close();
                    
                    // 发送到 MQTT (Base64 编码)
                    String base64 = android.util.Base64.encodeToString(imageData, 2);
                    org.json.JSONObject msg = new org.json.JSONObject();
                    msg.put("device_id", deviceId);
                    msg.put("image_base64", base64);
                    msg.put("timestamp", System.currentTimeMillis());
                    publishTextMessage("fusion/camera/" + deviceId + "/frame", msg.toString(), 0);
                    
                    // 清理文件
                    file.delete();
                    Log.i(TAG, "截图已发送 (大小: " + (imageData.length / 1024) + "KB)");
                }
            } catch (Exception e) {
                Log.e(TAG, "截图失败: " + e.getMessage());
            }
        }).start();
    }
    
    /**
     * 处理算力卸载任务
     */
    private String handleComputeTask(String taskType, org.json.JSONObject params) {
        Log.i(TAG, "算力任务: " + taskType);
        
        try {
            switch (taskType) {
                case "benchmark":
                    // 简单基准测试: 计算 Pi (Leibniz 公式) 测算 CPU 性能
                    long startTime = System.currentTimeMillis();
                    double pi = 0;
                    int iterations = params.optInt("iterations", 1000000);
                    for (int i = 0; i < iterations; i++) {
                        pi += (i % 2 == 0 ? 1.0 : -1.0) / (2 * i + 1);
                    }
                    pi *= 4;
                    long elapsed = System.currentTimeMillis() - startTime;
                    
                    org.json.JSONObject result = new org.json.JSONObject();
                    result.put("pi", pi);
                    result.put("iterations", iterations);
                    result.put("elapsed_ms", elapsed);
                    result.put("score", (int)(iterations / (elapsed / 1000.0))); // ops/sec
                    return result.toString();
                    
                case "hash":
                    // 文件哈希 (MD5/SHA-256)
                    String hashInput = params.optString("data", "fusion-benchmark");
                    String hashAlgo = params.optString("algorithm", "SHA-256");
                    int rounds = params.optInt("rounds", 10000);
                    long hashStart = System.currentTimeMillis();
                    String hashResult = hashInput;
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance(hashAlgo);
                    for (int i = 0; i < rounds; i++) {
                        md.reset();
                        byte[] hash = md.digest(hashResult.getBytes());
                        hashResult = android.util.Base64.encodeToString(hash, 2);
                    }
                    long hashElapsed = System.currentTimeMillis() - hashStart;
                    
                    org.json.JSONObject hashResultObj = new org.json.JSONObject();
                    hashResultObj.put("result_hash", hashResult);
                    hashResultObj.put("rounds", rounds);
                    hashResultObj.put("elapsed_ms", hashElapsed);
                    return hashResultObj.toString();
                    
                default:
                    return "{\"error\": \"unsupported_task: " + taskType + "\"}";
            }
        } catch (Exception e) {
            Log.e(TAG, "算力任务失败: " + e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}
