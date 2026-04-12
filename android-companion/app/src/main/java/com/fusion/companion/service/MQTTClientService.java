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
    
    // 默认 Broker 地址（卧室手机 C）
    private static final String DEFAULT_BROKER_HOST = "192.168.1.100";
    private static final int DEFAULT_BROKER_PORT = 1883;
    
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
    
    // 服务运行状态
    private static AtomicBoolean running = new AtomicBoolean(false);
    
    // 连接状态
    private AtomicBoolean connected = new AtomicBoolean(false);
    
    // 重连控制
    private AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    
    // 主线程 Handler
    private Handler handler;
    
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
        
        handler = new Handler(Looper.getMainLooper());
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
        
        try {
            // 创建 MQTT 客户端
            mqttClient = new MqttClient(
                brokerUrl,
                deviceId,
                new MemoryPersistence()  // 使用内存持久化（轻量级）
            );
            
            // 配置连接选项
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);  // 清洁会话（断开后清除）
            connOpts.setConnectionTimeout(30);  // 连接超时 30 秒
            connOpts.setKeepAliveInterval(60);  // KeepAlive 60 秒
            connOpts.setAutomaticReconnect(false);  // 使用自定义重连逻辑
            connOpts.setMaxInflight(100);  // 最大飞行消息数
            
            // 设置回调
            mqttClient.setCallback(new MqttCallbackExtended() {
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
                    
                    // 自动重连
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                }
                
                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    Log.d(TAG, "收到消息 - 主题：" + topic + ", 内容：" + new String(message.getPayload()));
                    
                    // 通知监听器
                    if (messageListener != null) {
                        messageListener.onMessageReceived(topic, message.getPayload());
                    }
                }
                
                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    Log.d(TAG, "消息投递完成 - ID: " + token.getMessageId());
                }
            });
            
            // 连接到 Broker
            mqttClient.connect(connOpts);
            Log.i(TAG, "MQTT 连接请求已发送");
            
        } catch (MqttException e) {
            Log.e(TAG, "MQTT 连接失败：" + e.getMessage(), e);
            connected.set(false);
            
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
        
        Log.i(TAG, "已订阅主题：" + deviceTopic + ", fusion/broadcast, fusion/mode");
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
            publishSensorData();
            handler.postDelayed(sensorPublishRunnable, SENSOR_PUBLISH_INTERVAL);
        };
        handler.post(sensorPublishRunnable);
        
        // 心跳发布任务（每 30 秒）
        heartbeatPublishRunnable = () -> {
            publishHeartbeat();
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
        if (sensorDataCache.isEmpty()) {
            Log.d(TAG, "暂无传感器数据，跳过发布");
            return;
        }
        
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
            publishJsonMessage(topic, data, 1);
            
            Log.d(TAG, "发布传感器数据：" + topic + " - " + value + " " + data.unit);
        }
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
    public String getDeviceId() {
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
}
