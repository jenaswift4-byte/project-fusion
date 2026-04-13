package com.fusion.companion.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 传感器数据采集器
 * 
 * 功能特性:
 * - 支持所有可用传感器（光线/距离/温度/湿度/气压/陀螺仪/加速度计）
 * - 每 5 秒主动轮询一次数据 (绕过 MIUI 省电模式下 onSensorChanged 不触发的问题)
 * - 支持 Batch 模式 (Android 9+)：flush() 触发传感器批量上报
 * - 通过 MQTT 发布传感器数据
 * 
 * 采集模式:
 * 1. 轮询模式 (POLL): 使用 Handler 定时调用 sensorManager.flush() 或读取缓存
 * 2. 事件模式 (EVENT): 传统 onSensorChanged 回调 (MIUI 省电下可能失效)
 * 
 * 默认使用轮询模式，确保 MIUI / 国产 ROM 下也能正常采集。
 * 
 * 支持的传感器类型:
 * - TYPE_LIGHT (光线传感器)
 * - TYPE_PROXIMITY (距离传感器)
 * - TYPE_AMBIENT_TEMPERATURE (温度传感器)
 * - TYPE_RELATIVE_HUMIDITY (湿度传感器)
 * - TYPE_PRESSURE (气压计)
 * - TYPE_GYROSCOPE (陀螺仪)
 * - TYPE_ACCELEROMETER (加速度计)
 * - TYPE_MAGNETIC_FIELD (磁力计)
 * - TYPE_STEP_COUNTER (步数)
 * 
 * @author Fusion
 * @version 2.0 - 定时轮询模式绕过 MIUI 省电限制
 */
public class SensorCollector implements SensorEventListener {
    
    private static final String TAG = "SensorCollector";
    
    // 默认采集间隔 (毫秒)
    private static final int DEFAULT_COLLECTION_INTERVAL = 5000; // 5 秒
    
    // MQTT 配置
    private static final String MQTT_BROKER_URL = "tcp://127.0.0.1:1883";
    private String mqttBrokerUrl = MQTT_BROKER_URL;  // 可通过 setBrokerUrl 动态修改
    private static final String MQTT_CLIENT_ID = "sensor-collector";
    private static final int MQTT_QOS = 1;
    private static final String TOPIC_PREFIX = "sensors/";
    
    // 传感器类型映射表 (传感器类型 -> 字符串名称)
    private static final Map<Integer, String> SENSOR_TYPE_MAP = new HashMap<>();
    static {
        SENSOR_TYPE_MAP.put(Sensor.TYPE_LIGHT, "light");
        SENSOR_TYPE_MAP.put(Sensor.TYPE_PROXIMITY, "proximity");
        SENSOR_TYPE_MAP.put(Sensor.TYPE_AMBIENT_TEMPERATURE, "temperature");
        SENSOR_TYPE_MAP.put(Sensor.TYPE_RELATIVE_HUMIDITY, "humidity");
        SENSOR_TYPE_MAP.put(Sensor.TYPE_PRESSURE, "pressure");
        SENSOR_TYPE_MAP.put(Sensor.TYPE_GYROSCOPE, "gyroscope");
        SENSOR_TYPE_MAP.put(Sensor.TYPE_ACCELEROMETER, "accelerometer");
        SENSOR_TYPE_MAP.put(Sensor.TYPE_MAGNETIC_FIELD, "magnetic_field");
        SENSOR_TYPE_MAP.put(Sensor.TYPE_STEP_COUNTER, "step_counter");
    };
    
    // 传感器单位映射表
    private static final Map<Integer, String> SENSOR_UNIT_MAP = new HashMap<>();
    static {
        SENSOR_UNIT_MAP.put(Sensor.TYPE_LIGHT, "lux");
        SENSOR_UNIT_MAP.put(Sensor.TYPE_PROXIMITY, "cm");
        SENSOR_UNIT_MAP.put(Sensor.TYPE_AMBIENT_TEMPERATURE, "°C");
        SENSOR_UNIT_MAP.put(Sensor.TYPE_RELATIVE_HUMIDITY, "%");
        SENSOR_UNIT_MAP.put(Sensor.TYPE_PRESSURE, "hPa");
        SENSOR_UNIT_MAP.put(Sensor.TYPE_GYROSCOPE, "rad/s");
        SENSOR_UNIT_MAP.put(Sensor.TYPE_ACCELEROMETER, "m/s²");
        SENSOR_UNIT_MAP.put(Sensor.TYPE_MAGNETIC_FIELD, "μT");
        SENSOR_UNIT_MAP.put(Sensor.TYPE_STEP_COUNTER, "steps");
    };
    
    // 上下文和传感器管理器
    private final Context context;
    private final SensorManager sensorManager;
    
    // 设备 ID (用于 MQTT 主题)
    private final String deviceId;
    
    // 运行状态
    private final AtomicBoolean isCollecting = new AtomicBoolean(false);
    
    // 已注册的传感器
    private final List<Sensor> registeredSensors = new ArrayList<>();
    
    // 传感器数据缓存 (用于限流)
    private final ConcurrentHashMap<Integer, Long> lastPublishTime = new ConcurrentHashMap<>();
    
    // 传感器数据缓存 (轮询模式: 存储最新值)
    private final ConcurrentHashMap<Integer, float[]> sensorValueCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> sensorUpdateTimestamp = new ConcurrentHashMap<>();
    
    // 采集间隔 (毫秒)
    private int collectionInterval = DEFAULT_COLLECTION_INTERVAL;
    
    // 轮询定时器
    private Handler pollHandler;
    private Runnable pollTask;
    
    // 轮询模式标记: true=定时 flush (推荐), false=被动事件 (MIUI 省电下失效)
    private boolean pollMode = true;
    
    // MQTT 客户端
    private volatile MqttClient mqttClient;
    
    // 主线程 Handler
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    // 后台线程池 (用于 MQTT 网络 IO，绝不阻塞主线程)
    private final ExecutorService mqttExecutor = Executors.newSingleThreadExecutor();
    
    // 延迟任务
    private Runnable collectTask;
    
    /**
     * 构造函数
     * @param context 应用上下文
     * @param deviceId 设备 ID (用于 MQTT 主题)
     */
    public SensorCollector(Context context, String deviceId) {
        this.context = context;
        this.deviceId = deviceId;
        
        // 初始化传感器管理器
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        
        // 尝试从 SharedPreferences 读取 Broker 配置 (与 MQTTClientService 共享配置)
        SharedPreferences prefs = context.getSharedPreferences("mqtt_client_prefs", Context.MODE_PRIVATE);
        String host = prefs.getString("broker_host", "");
        int port = prefs.getInt("broker_port", 1883);
        if (!host.isEmpty()) {
            mqttBrokerUrl = "tcp://" + host + ":" + port;
        }
        
        Log.i(TAG, "传感器采集器初始化完成 - 设备 ID: " + deviceId + ", Broker: " + mqttBrokerUrl);
    }

    /**
     * 设置 MQTT Broker URL (连接外部 Broker)
     * @param url Broker URL, e.g. "tcp://192.168.1.100:1883"
     */
    public void setBrokerUrl(String url) {
        mqttBrokerUrl = url;
        Log.i(TAG, "MQTT Broker URL 已更新: " + url);
    }
    
    /**
     * 启动传感器采集 (非阻塞，MQTT 初始化在后台线程)
     * @return true 如果启动流程已提交
     */
    public boolean startCollection() {
        if (isCollecting.get()) {
            Log.w(TAG, "传感器采集已在运行，跳过启动");
            return true;
        }
        
        // 注册传感器 (轻量操作，主线程 OK)
        registerAllSensors();
        
        // 标记为采集状态
        isCollecting.set(true);
        
        // MQTT 初始化丢到后台线程，避免阻塞主线程
        mqttExecutor.execute(() -> {
            try {
                initMQTTClient();
            } catch (Exception e) {
                Log.e(TAG, "后台初始化 MQTT 失败", e);
            }
        });
        
        // 启动定时采集任务 (用于检查 MQTT 连接状态)
        startCollectionTask();
        
        Log.i(TAG, "传感器采集已启动 - 注册传感器数量：" + registeredSensors.size());
        return true;
    }
    
    /**
     * 停止传感器采集
     */
    public void stopCollection() {
        if (!isCollecting.get()) {
            Log.w(TAG, "传感器采集未运行，跳过停止");
            return;
        }
        
        // 停止定时采集任务
        stopCollectionTask();
        
        // 注销所有传感器监听
        unregisterAllSensors();
        
        // 断开 MQTT 连接 (后台线程)
        mqttExecutor.execute(() -> {
            try {
                disconnectMQTTClient();
            } catch (Exception e) {
                Log.e(TAG, "断开 MQTT 失败", e);
            }
        });
        
        isCollecting.set(false);
        lastPublishTime.clear();
        
        Log.i(TAG, "传感器采集已停止");
    }
    
    /**
     * 检查采集是否正在运行
     * @return true 如果正在采集
     */
    public boolean isCollecting() {
        return isCollecting.get();
    }
    
    /**
     * 设置采集间隔
     * @param intervalMs 采集间隔 (毫秒)
     */
    public void setCollectionInterval(int intervalMs) {
        if (intervalMs < 1000) {
            Log.w(TAG, "采集间隔过短，设置为最小值 1000ms");
            intervalMs = 1000;
        }
        
        this.collectionInterval = intervalMs;
        Log.d(TAG, "采集间隔已设置：" + intervalMs + "ms");
        
        // 如果正在采集，重新启动任务以应用新间隔
        if (isCollecting.get()) {
            stopCollectionTask();
            startCollectionTask();
        }
    }
    
    /**
     * 获取采集间隔
     * @return 采集间隔 (毫秒)
     */
    public int getCollectionInterval() {
        return collectionInterval;
    }
    
    /**
     * 初始化 MQTT 客户端
     */
    private void initMQTTClient() {
        if (mqttClient != null && mqttClient.isConnected()) {
            Log.d(TAG, "MQTT 客户端已连接，跳过初始化");
            return;
        }
        
        try {
            // 创建 MQTT 客户端 (使用内存持久化)
            mqttClient = new MqttClient(
                mqttBrokerUrl,
                MQTT_CLIENT_ID + "-" + deviceId,
                new MemoryPersistence()
            );
            
            // 配置连接选项
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setConnectionTimeout(10);
            connOpts.setKeepAliveInterval(30);
            connOpts.setAutomaticReconnect(true);
            
            // 连接到 Broker (本地或远程)
            mqttClient.connect(connOpts);
            Log.d(TAG, "MQTT 客户端已连接：" + mqttBrokerUrl);
            
        } catch (MqttException e) {
            Log.e(TAG, "MQTT 客户端初始化失败", e);
            mqttClient = null;
        }
    }
    
    /**
     * 断开 MQTT 客户端连接
     */
    private void disconnectMQTTClient() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                mqttClient.close();
                mqttClient = null;
                Log.d(TAG, "MQTT 客户端已断开");
            } catch (MqttException e) {
                Log.e(TAG, "断开 MQTT 连接失败", e);
            }
        }
    }
    
    /**
     * 发布传感器数据到 MQTT
     * @param sensorType 传感器类型
     * @param value 传感器数值
     * @param unit 单位
     */
    private void publishSensorData(int sensorType, float value, String unit) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            Log.w(TAG, "MQTT 客户端未连接，跳过发布");
            return;
        }
        
        try {
            // 限流检查 (防止发布过于频繁)
            Long lastTime = lastPublishTime.get(sensorType);
            long currentTime = System.currentTimeMillis();
            
            if (lastTime != null && (currentTime - lastTime) < collectionInterval) {
                return; // 未到采集间隔，跳过
            }
            
            // 构建 JSON 消息
            JSONObject jsonData = new JSONObject();
            jsonData.put("device_id", deviceId);
            jsonData.put("sensor_type", SENSOR_TYPE_MAP.get(sensorType));
            jsonData.put("value", Math.round(value * 100.0) / 100.0); // 保留两位小数
            jsonData.put("unit", unit);
            jsonData.put("timestamp", currentTime);
            
            // 构建 MQTT 主题
            String topic = TOPIC_PREFIX + deviceId + "/" + SENSOR_TYPE_MAP.get(sensorType);
            
            // 创建 MQTT 消息
            MqttMessage message = new MqttMessage(jsonData.toString().getBytes());
            message.setQos(MQTT_QOS);
            message.setRetained(false);
            
            // 发布消息
            mqttClient.publish(topic, message);
            
            // 更新最后发布时间
            lastPublishTime.put(sensorType, currentTime);
            
            Log.d(TAG, "发布传感器数据：" + SENSOR_TYPE_MAP.get(sensorType) + 
                     " = " + value + " " + unit);
            
        } catch (Exception e) {
            Log.e(TAG, "发布传感器数据失败", e);
        }
    }
    
    /**
     * 注册所有可用传感器
     */
    private void registerAllSensors() {
        if (sensorManager == null) {
            Log.e(TAG, "SensorManager 不可用");
            return;
        }
        
        // 获取所有传感器列表
        List<Sensor> allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        
        // 注册我们关心的传感器
        for (Sensor sensor : allSensors) {
            int type = sensor.getType();
            
            // 检查是否是我们支持的传感器类型
            if (SENSOR_TYPE_MAP.containsKey(type)) {
                // 注册传感器监听器
                // 使用 SENSOR_DELAY_NORMAL 以平衡性能和精度
                boolean registered = sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                );
                
                if (registered) {
                    registeredSensors.add(sensor);
                    Log.d(TAG, "传感器已注册：" + sensor.getName() + 
                          " (类型：" + SENSOR_TYPE_MAP.get(type) + ")");
                } else {
                    Log.w(TAG, "传感器注册失败：" + sensor.getName());
                }
            }
        }
        
        if (registeredSensors.isEmpty()) {
            Log.w(TAG, "没有可用的传感器");
        }
    }
    
    /**
     * 注销所有传感器监听
     */
    private void unregisterAllSensors() {
        if (sensorManager == null || registeredSensors.isEmpty()) {
            return;
        }
        
        for (Sensor sensor : registeredSensors) {
            try {
                sensorManager.unregisterListener(this, sensor);
                Log.d(TAG, "传感器已注销：" + sensor.getName());
            } catch (Exception e) {
                Log.w(TAG, "注销传感器失败：" + sensor.getName(), e);
            }
        }
        
        registeredSensors.clear();
    }
    
    /**
     * 启动定时采集任务 (轮询模式)
     * 
     * 轮询策略:
     * 1. 优先使用 sensorManager.flush() 触发批量上报 (Android 9+)
     * 2. flush 失败时，直接读取缓存中的最新值并发布
     * 3. 同时检查 MQTT 连接状态并自动重连
     */
    private void startCollectionTask() {
        if (pollHandler == null) {
            pollHandler = new Handler(Looper.getMainLooper());
        }
        
        pollTask = new Runnable() {
            @Override
            public void run() {
                if (!isCollecting.get()) {
                    return;
                }
                
                // ═══ 轮询: 主动 flush 传感器数据 ═══
                if (pollMode && sensorManager != null && !registeredSensors.isEmpty()) {
                    // 方案 1: flush() 触发传感器上报 (Android 9+)
                    // 这会立即触发 onSensorChanged，更新缓存
                    try {
                        boolean flushed = sensorManager.flush(SensorCollector.this);
                        if (!flushed) {
                            Log.d(TAG, "传感器 flush 无效，将使用缓存数据");
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "flush 异常: " + e.getMessage());
                    }
                    
                    // 方案 2: 读取缓存并发布 (确保即使 flush 失败也有数据)
                    long now = System.currentTimeMillis();
                    for (Sensor sensor : registeredSensors) {
                        int type = sensor.getType();
                        long lastUpdate = sensorUpdateTimestamp.getOrDefault(type, 0L);
                        
                        // 如果数据在 2 倍间隔内还有效，直接用缓存
                        if (now - lastUpdate < collectionInterval * 2) {
                            float[] values = sensorValueCache.get(type);
                            if (values != null) {
                                String unit = SENSOR_UNIT_MAP.get(type);
                                final float value = values[0];
                                mqttExecutor.execute(() -> publishSensorData(type, value, unit));
                            }
                        }
                    }
                }
                
                // ═══ MQTT 重连检查 ═══
                if (mqttClient == null || !mqttClient.isConnected()) {
                    Log.w(TAG, "MQTT 连接断开，尝试重连...");
                    mqttExecutor.execute(() -> {
                        try {
                            initMQTTClient();
                        } catch (Exception e) {
                            Log.e(TAG, "MQTT 重连失败", e);
                        }
                    });
                }
                
                // 调度下一次轮询
                pollHandler.postDelayed(this, collectionInterval);
            }
        };
        
        pollHandler.post(pollTask);
        Log.i(TAG, "定时轮询任务已启动 (间隔: " + collectionInterval + "ms, 模式: " + (pollMode ? "POLL" : "EVENT") + ")");
    }
    
    /**
     * 停止定时采集任务
     */
    private void stopCollectionTask() {
        if (pollTask != null && pollHandler != null) {
            pollHandler.removeCallbacks(pollTask);
            pollTask = null;
            Log.d(TAG, "定时采集任务已停止");
        }
    }
    
    /**
     * 设置采集模式
     * @param poll true=轮询模式 (推荐, MIUI兼容), false=事件模式
     */
    public void setPollMode(boolean poll) {
        this.pollMode = poll;
        Log.i(TAG, "采集模式: " + (poll ? "POLL (轮询)" : "EVENT (事件)"));
    }
    
    // ==================== SensorEventListener 实现 ====================
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isCollecting.get()) {
            return;
        }
        
        final int sensorType = event.sensor.getType();
        
        // 缓存所有值 (多值传感器如加速度计有 x,y,z)
        sensorValueCache.put(sensorType, event.values.clone());
        sensorUpdateTimestamp.put(sensorType, System.currentTimeMillis());
        
        // 事件模式: 直接发布 (轮询模式下由 pollTask 统一发布)
        if (!pollMode) {
            final float value = event.values[0];
            final String unit = SENSOR_UNIT_MAP.get(sensorType);
            mqttExecutor.execute(() -> publishSensorData(sensorType, value, unit));
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 传感器精度变化回调
        // 可以在这里处理精度变化事件
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w(TAG, "传感器精度不可靠：" + sensor.getName());
        }
    }
    
    /**
     * 获取已注册的传感器数量
     * @return 传感器数量
     */
    public int getRegisteredSensorCount() {
        return registeredSensors.size();
    }
    
    /**
     * 获取支持的传感器类型列表
     * @return 传感器类型名称列表
     */
    public static List<String> getSupportedSensorTypes() {
        return new ArrayList<>(SENSOR_TYPE_MAP.values());
    }
    
    /**
     * 检查是否支持指定类型的传感器
     * @param context 应用上下文
     * @param sensorType 传感器类型
     * @return true 如果支持
     */
    public static boolean isSensorAvailable(Context context, int sensorType) {
        SensorManager sensorManager = (SensorManager) 
            context.getSystemService(Context.SENSOR_SERVICE);
        
        if (sensorManager == null) {
            return false;
        }
        
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        return sensor != null;
    }
    
    /**
     * 获取可用传感器列表
     * @param context 应用上下文
     * @return 可用传感器类型名称列表
     */
    public static List<String> getAvailableSensors(Context context) {
        List<String> availableSensors = new ArrayList<>();
        SensorManager sensorManager = (SensorManager) 
            context.getSystemService(Context.SENSOR_SERVICE);
        
        if (sensorManager == null) {
            return availableSensors;
        }
        
        for (Map.Entry<Integer, String> entry : SENSOR_TYPE_MAP.entrySet()) {
            Sensor sensor = sensorManager.getDefaultSensor(entry.getKey());
            if (sensor != null) {
                availableSensors.add(entry.getValue());
            }
        }
        
        return availableSensors;
    }
}
