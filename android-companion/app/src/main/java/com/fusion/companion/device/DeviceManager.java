package com.fusion.companion.device;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ESP32 / MCU 设备管理器
 * 
 * 负责设备的注册、状态查询、设备控制等功能
 * 通过 MQTT 协议与 ESP32/MCU 设备通信
 * 
 * 特性：
 * - MQTT 客户端集成（连接 PC 端 Broker）
 * - SharedPreferences 持久化存储设备列表
 * - 命令队列 + 离线设备自动重试
 * - 设备离线检测（心跳超时）
 * 
 * @author Fusion
 * @version 2.0.0
 */
public class DeviceManager {
    
    private static final String TAG = "DeviceManager";
    private static final String LOG_PREFIX = "[DeviceManager] ";
    
    // 单例实例
    private static volatile DeviceManager instance;
    
    // 应用上下文
    private Context context;
    
    // 设备缓存（内存）
    private final Map<String, Device> deviceMap = new ConcurrentHashMap<>();
    
    // 设备状态缓存
    private final Map<String, DeviceStatus> statusMap = new ConcurrentHashMap<>();
    
    // MQTT 客户端
    private MqttClient mqttClient;
    private boolean mqttConnected = false;
    private MqttConnectOptions connectOptions;
    
    // 线程池
    private ScheduledExecutorService executor;
    
    // 主线程 Handler
    private Handler mainHandler;
    
    // 命令队列（离线设备命令缓存）
    private final Queue<PendingCommand> pendingCommandQueue = new LinkedList<>();
    private static final int MAX_PENDING_COMMANDS = 50;
    private static final long OFFLINE_RETRY_INTERVAL = 30; // 秒
    
    // 设备心跳超时（毫秒）
    private static final long HEARTBEAT_TIMEOUT = 120000; // 2 分钟无心跳视为离线
    
    // 设备状态监听器
    private final List<DeviceStatusListener> listeners = new ArrayList<>();
    
    // 是否已初始化
    private boolean initialized = false;
    
    // SharedPreferences
    private static final String PREFS_NAME = "fusion_device_manager";
    private static final String KEY_DEVICES = "registered_devices";
    private SharedPreferences prefs;
    
    // Gson
    private Gson gson;
    
    // MQTT Broker 配置（与 MQTTClientService 共享）
    private static final String MQTT_PREFS_NAME = "mqtt_client_prefs";
    private static final String KEY_BROKER_HOST = "broker_host";
    private static final String KEY_BROKER_PORT = "broker_port";
    private String brokerHost;
    private int brokerPort;
    
    // 待处理命令
    private static class PendingCommand {
        String deviceId;
        String command;
        JSONObject params;
        long timestamp;
        int retryCount;
        
        PendingCommand(String deviceId, String command, JSONObject params) {
            this.deviceId = deviceId;
            this.command = command;
            this.params = params;
            this.timestamp = System.currentTimeMillis();
            this.retryCount = 0;
        }
    }
    
    /**
     * 私有构造函数
     * @param context 应用上下文
     */
    private DeviceManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newScheduledThreadPool(2);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // 从 SharedPreferences 加载 Broker 配置
        loadBrokerConfig();
    }
    
    /**
     * 获取设备管理器单例
     * @param context 应用上下文
     * @return DeviceManager 实例
     */
    public static DeviceManager getInstance(Context context) {
        if (instance == null) {
            synchronized (DeviceManager.class) {
                if (instance == null) {
                    instance = new DeviceManager(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化设备管理器
     * 连接 MQTT Broker，订阅设备主题，加载持久化的设备列表
     */
    public void init() {
        if (initialized) {
            Log.w(TAG, LOG_PREFIX + "设备管理器已初始化");
            return;
        }
        
        Log.i(TAG, LOG_PREFIX + "初始化设备管理器");
        
        try {
            // 1. 连接 MQTT Broker
            connectMQTT();
            
            // 2. 从数据库加载设备
            loadDevicesFromDatabase();
            
            // 3. 订阅所有设备主题
            subscribeToDeviceTopics();
            
            // 4. 启动离线命令重试定时器
            startPendingCommandRetry();
            
            // 5. 启动心跳超时检测
            startHeartbeatMonitor();
            
            initialized = true;
            Log.i(TAG, LOG_PREFIX + "设备管理器初始化完成 (设备数: " + deviceMap.size() + ")");
            
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "初始化失败", e);
        }
    }
    
    /**
     * 释放资源
     */
    public void destroy() {
        Log.d(TAG, LOG_PREFIX + "释放设备管理器资源");
        
        try {
            // 保存所有设备状态到数据库
            saveAllDevicesToDatabase();
            
            // 断开 MQTT 连接
            disconnectMQTT();
            
            // 停止线程池
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            
            deviceMap.clear();
            statusMap.clear();
            pendingCommandQueue.clear();
            listeners.clear();
            initialized = false;
            
            Log.i(TAG, LOG_PREFIX + "设备管理器已释放");
            
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "释放资源失败", e);
        }
    }
    
    // ==================== MQTT 连接管理 ====================
    
    private void loadBrokerConfig() {
        SharedPreferences mqttPrefs = context.getSharedPreferences(MQTT_PREFS_NAME, Context.MODE_PRIVATE);
        this.brokerHost = mqttPrefs.getString(KEY_BROKER_HOST, "192.168.1.100");
        this.brokerPort = mqttPrefs.getInt(KEY_BROKER_PORT, 1883);
        Log.i(TAG, LOG_PREFIX + "Broker 配置: " + brokerHost + ":" + brokerPort);
    }
    
    private void connectMQTT() {
        executor.execute(() -> {
            try {
                String brokerUrl = "tcp://" + brokerHost + ":" + brokerPort;
                String clientId = "DeviceManager-" + android.os.Build.SERIAL;
                
                mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
                connectOptions = new MqttConnectOptions();
                connectOptions.setAutomaticReconnect(true);
                connectOptions.setCleanSession(true);
                connectOptions.setConnectionTimeout(10);
                connectOptions.setKeepAliveInterval(60);
                
                mqttClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        mqttConnected = true;
                        Log.i(TAG, LOG_PREFIX + "MQTT 连接成功" + (reconnect ? " (重连)" : ""));
                        // 重连后重新订阅
                        subscribeToDeviceTopics();
                    }
                    
                    @Override
                    public void connectionLost(Throwable cause) {
                        mqttConnected = false;
                        Log.w(TAG, LOG_PREFIX + "MQTT 连接断开: " + cause.getMessage());
                    }
                    
                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        handleMQTTMessage(topic, new String(message.getPayload()));
                    }
                    
                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        // 消息投递完成
                    }
                });
                
                mqttClient.connect(connectOptions);
                Log.i(TAG, LOG_PREFIX + "正在连接 MQTT Broker: " + brokerUrl);
                
            } catch (MqttException e) {
                Log.e(TAG, LOG_PREFIX + "MQTT 连接失败", e);
                mqttConnected = false;
            }
        });
    }
    
    private void disconnectMQTT() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                mqttClient.close();
            }
        } catch (MqttException e) {
            Log.w(TAG, LOG_PREFIX + "MQTT 断开异常", e);
        }
        mqttConnected = false;
        mqttClient = null;
    }
    
    private boolean publishMQTT(String topic, String payload) {
        if (!mqttConnected || mqttClient == null || !mqttClient.isConnected()) {
            Log.w(TAG, LOG_PREFIX + "MQTT 未连接，无法发布: " + topic);
            return false;
        }
        try {
            MqttMessage msg = new MqttMessage(payload.getBytes());
            msg.setQos(1);
            msg.setRetained(false);
            mqttClient.publish(topic, msg);
            return true;
        } catch (MqttException e) {
            Log.e(TAG, LOG_PREFIX + "MQTT 发布失败: " + topic, e);
            return false;
        }
    }
    
    private boolean subscribeMQTT(String topic) {
        if (!mqttConnected || mqttClient == null || !mqttClient.isConnected()) {
            return false;
        }
        try {
            mqttClient.subscribe(topic, 1);
            Log.d(TAG, LOG_PREFIX + "已订阅: " + topic);
            return true;
        } catch (MqttException e) {
            Log.e(TAG, LOG_PREFIX + "MQTT 订阅失败: " + topic, e);
            return false;
        }
    }
    
    private void handleMQTTMessage(String topic, String payload) {
        Log.d(TAG, LOG_PREFIX + "收到 MQTT 消息: " + topic);
        
        try {
            // 设备状态上报
            if (topic.contains("/status")) {
                String deviceId = extractDeviceIdFromTopic(topic, "/status");
                if (deviceId != null) {
                    DeviceStatus status = DeviceStatus.fromJson(payload);
                    if (status != null) {
                        updateDeviceStatus(deviceId, status);
                    }
                }
            }
            
            // 设备响应
            if (topic.contains("/response")) {
                String deviceId = extractDeviceIdFromTopic(topic, "/response");
                if (deviceId != null) {
                    handleDeviceResponse(deviceId, payload);
                }
            }
            
            // 设备心跳
            if (topic.contains("/heartbeat")) {
                String deviceId = extractDeviceIdFromTopic(topic, "/heartbeat");
                if (deviceId != null && deviceMap.containsKey(deviceId)) {
                    Device device = deviceMap.get(deviceId);
                    if (!device.isOnline) {
                        updateDeviceOnlineStatus(deviceId, true);
                    }
                    device.lastSeenAt = System.currentTimeMillis();
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "处理 MQTT 消息失败", e);
        }
    }
    
    private String extractDeviceIdFromTopic(String topic, String suffix) {
        // topic 格式: devices/{deviceId}/status
        String prefix = "devices/";
        int start = topic.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = topic.indexOf(suffix, start);
        if (end < 0) return null;
        return topic.substring(start, end);
    }
    
    private void subscribeToDeviceTopics() {
        if (!mqttConnected) return;
        
        // 订阅所有设备的状态和响应主题
        for (String deviceId : deviceMap.keySet()) {
            subscribeToDevice(deviceId);
        }
        
        // 订阅广播主题，自动发现新设备
        subscribeMQTT("devices/+/status");
        subscribeMQTT("devices/+/response");
        subscribeMQTT("devices/+/heartbeat");
    }
    
    private void subscribeToDevice(String deviceId) {
        String statusTopic = DeviceProtocol.ControlTopics.getStatusTopic(deviceId);
        String responseTopic = DeviceProtocol.ControlTopics.getResponseTopic(deviceId);
        
        subscribeMQTT(statusTopic);
        subscribeMQTT(responseTopic);
        
        // 也订阅心跳
        subscribeMQTT("devices/" + deviceId + "/heartbeat");
    }
    
    // ==================== 持久化存储 ====================
    
    private void loadDevicesFromDatabase() {
        String json = prefs.getString(KEY_DEVICES, null);
        if (json == null || json.isEmpty()) {
            Log.i(TAG, LOG_PREFIX + "无持久化设备数据");
            return;
        }
        
        try {
            Type listType = new TypeToken<List<Device>>() {}.getType();
            List<Device> savedDevices = gson.fromJson(json, listType);
            
            if (savedDevices != null) {
                for (Device device : savedDevices) {
                    deviceMap.put(device.deviceId, device);
                    
                    // 初始化空状态
                    if (!statusMap.containsKey(device.deviceId)) {
                        DeviceStatus status = new DeviceStatus();
                        status.deviceId = device.deviceId;
                        status.status = "unknown";
                        status.lastUpdate = System.currentTimeMillis();
                        statusMap.put(device.deviceId, status);
                    }
                }
                Log.i(TAG, LOG_PREFIX + "从数据库加载 " + savedDevices.size() + " 个设备");
            }
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "加载设备数据失败", e);
        }
    }
    
    private void saveDeviceToDatabase(String deviceId) {
        try {
            Device device = deviceMap.get(deviceId);
            if (device == null) return;
            
            List<Device> allDevices = new ArrayList<>(deviceMap.values());
            String json = gson.toJson(allDevices);
            prefs.edit().putString(KEY_DEVICES, json).apply();
            
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "保存设备数据失败: " + deviceId, e);
        }
    }
    
    private void saveAllDevicesToDatabase() {
        try {
            List<Device> allDevices = new ArrayList<>(deviceMap.values());
            String json = gson.toJson(allDevices);
            prefs.edit().putString(KEY_DEVICES, json).apply();
            Log.i(TAG, LOG_PREFIX + "已保存 " + allDevices.size() + " 个设备到数据库");
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "保存所有设备数据失败", e);
        }
    }
    
    // ==================== 命令队列 + 离线重试 ====================
    
    private void startPendingCommandRetry() {
        executor.scheduleAtFixedRate(() -> {
            try {
                processPendingCommands();
            } catch (Exception e) {
                Log.e(TAG, LOG_PREFIX + "处理待发送命令失败", e);
            }
        }, OFFLINE_RETRY_INTERVAL, OFFLINE_RETRY_INTERVAL, TimeUnit.SECONDS);
    }
    
    private void processPendingCommands() {
        Iterator<PendingCommand> it = pendingCommandQueue.iterator();
        while (it.hasNext()) {
            PendingCommand cmd = it.next();
            
            Device device = deviceMap.get(cmd.deviceId);
            if (device == null) {
                // 设备已注销，丢弃命令
                it.remove();
                continue;
            }
            
            if (device.isOnline) {
                // 设备在线，尝试发送
                if (executeCommand(cmd)) {
                    it.remove();
                    Log.i(TAG, LOG_PREFIX + "离线命令重发成功: " + cmd.deviceId + " -> " + cmd.command);
                } else {
                    cmd.retryCount++;
                    if (cmd.retryCount > 5) {
                        it.remove();
                        Log.w(TAG, LOG_PREFIX + "离线命令重试超限，丢弃: " + cmd.command);
                    }
                }
            } else if (System.currentTimeMillis() - cmd.timestamp > 3600000) {
                // 超过 1 小时的命令丢弃
                it.remove();
                Log.w(TAG, LOG_PREFIX + "离线命令过期，丢弃: " + cmd.command);
            }
        }
    }
    
    private boolean executeCommand(PendingCommand cmd) {
        try {
            String message = DeviceProtocol.ControlMessage.createMessage(cmd.command, cmd.params);
            String topic = DeviceProtocol.ControlTopics.getControlTopic(cmd.deviceId);
            return publishMQTT(topic, message);
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "执行命令失败", e);
            return false;
        }
    }
    
    // ==================== 心跳超时检测 ====================
    
    private void startHeartbeatMonitor() {
        executor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Device> entry : deviceMap.entrySet()) {
                Device device = entry.getValue();
                if (device.isOnline && device.lastSeenAt > 0) {
                    if (now - device.lastSeenAt > HEARTBEAT_TIMEOUT) {
                        Log.w(TAG, LOG_PREFIX + "设备心跳超时: " + entry.getKey());
                        updateDeviceOnlineStatus(entry.getKey(), false);
                    }
                }
            }
        }, HEARTBEAT_TIMEOUT, HEARTBEAT_TIMEOUT / 2, TimeUnit.MILLISECONDS);
    }
    
    // ==================== 设备注册管理 ====================
    
    /**
     * 注册设备
     * 
     * @param deviceId 设备 ID（唯一标识）
     * @param deviceType 设备类型（curtain, light, socket 等）
     * @return 是否注册成功
     */
    public boolean registerDevice(String deviceId, String deviceType) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, LOG_PREFIX + "设备 ID 不能为空");
            return false;
        }
        
        if (deviceType == null || deviceType.isEmpty()) {
            Log.e(TAG, LOG_PREFIX + "设备类型不能为空");
            return false;
        }
        
        // 检查设备是否已存在
        if (deviceMap.containsKey(deviceId)) {
            Log.w(TAG, LOG_PREFIX + "设备已存在：" + deviceId);
            return false;
        }
        
        try {
            // 创建设备对象
            Device device = new Device();
            device.deviceId = deviceId;
            device.deviceType = deviceType;
            device.name = generateDeviceName(deviceType, deviceId);
            device.registeredAt = System.currentTimeMillis();
            device.isOnline = false;
            device.lastSeenAt = 0;
            
            // 添加到缓存
            deviceMap.put(deviceId, device);
            
            // 初始化设备状态
            DeviceStatus status = new DeviceStatus();
            status.deviceId = deviceId;
            status.status = "unknown";
            status.lastUpdate = System.currentTimeMillis();
            statusMap.put(deviceId, status);
            
            // 订阅设备 MQTT 主题
            subscribeToDevice(deviceId);
            
            // 保存到数据库
            saveDeviceToDatabase(deviceId);
            
            Log.i(TAG, LOG_PREFIX + "设备注册成功：" + deviceId + " (" + deviceType + ")");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "注册设备失败", e);
            return false;
        }
    }
    
    /**
     * 注销设备
     * 
     * @param deviceId 设备 ID
     * @return 是否注销成功
     */
    public boolean unregisterDevice(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, LOG_PREFIX + "设备 ID 不能为空");
            return false;
        }
        
        // 检查设备是否存在
        if (!deviceMap.containsKey(deviceId)) {
            Log.w(TAG, LOG_PREFIX + "设备不存在：" + deviceId);
            return false;
        }
        
        try {
            // 从缓存移除
            deviceMap.remove(deviceId);
            statusMap.remove(deviceId);
            
            // 清除该设备的待发送命令
            pendingCommandQueue.removeIf(cmd -> cmd.deviceId.equals(deviceId));
            
            // 保存到数据库（移除后保存全部）
            saveAllDevicesToDatabase();
            
            Log.i(TAG, LOG_PREFIX + "设备注销成功：" + deviceId);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "注销设备失败", e);
            return false;
        }
    }
    
    /**
     * 更新设备在线状态
     * 
     * @param deviceId 设备 ID
     * @param isOnline 是否在线
     */
    public void updateDeviceOnlineStatus(String deviceId, boolean isOnline) {
        Device device = deviceMap.get(deviceId);
        if (device != null) {
            boolean wasOnline = device.isOnline;
            device.isOnline = isOnline;
            if (isOnline) {
                device.lastSeenAt = System.currentTimeMillis();
            }
            
            // 更新到数据库
            saveDeviceToDatabase(deviceId);
            
            if (wasOnline != isOnline) {
                Log.i(TAG, LOG_PREFIX + "设备在线状态变更：" + deviceId + " -> " + (isOnline ? "在线" : "离线"));
            }
        }
    }
    
    // ==================== 设备状态查询 ====================
    
    public DeviceStatus getDeviceStatus(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, LOG_PREFIX + "设备 ID 不能为空");
            return null;
        }
        
        DeviceStatus status = statusMap.get(deviceId);
        if (status == null) {
            Log.w(TAG, LOG_PREFIX + "设备状态不存在：" + deviceId);
            return null;
        }
        
        return status;
    }
    
    public Device getDevice(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, LOG_PREFIX + "设备 ID 不能为空");
            return null;
        }
        
        Device device = deviceMap.get(deviceId);
        if (device == null) {
            Log.w(TAG, LOG_PREFIX + "设备不存在：" + deviceId);
            return null;
        }
        
        return device;
    }
    
    public boolean isDeviceOnline(String deviceId) {
        Device device = deviceMap.get(deviceId);
        return device != null && device.isOnline;
    }
    
    public List<Device> listDevices() {
        return new ArrayList<>(deviceMap.values());
    }
    
    public List<Device> listDevicesByType(String deviceType) {
        List<Device> result = new ArrayList<>();
        for (Device device : deviceMap.values()) {
            if (device.deviceType.equals(deviceType)) {
                result.add(device);
            }
        }
        return result;
    }
    
    public List<Device> listOnlineDevices() {
        List<Device> result = new ArrayList<>();
        for (Device device : deviceMap.values()) {
            if (device.isOnline) {
                result.add(device);
            }
        }
        return result;
    }
    
    public int getDeviceCount() {
        return deviceMap.size();
    }
    
    public int getOnlineDeviceCount() {
        int count = 0;
        for (Device device : deviceMap.values()) {
            if (device.isOnline) {
                count++;
            }
        }
        return count;
    }
    
    // ==================== 设备控制 ====================
    
    /**
     * 发送控制命令
     * 
     * @param deviceId 设备 ID
     * @param command 命令类型
     * @param params 命令参数
     * @return 是否发送成功（或已加入离线队列）
     */
    public boolean sendCommand(String deviceId, String command, JSONObject params) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, LOG_PREFIX + "设备 ID 不能为空");
            return false;
        }
        
        if (command == null || command.isEmpty()) {
            Log.e(TAG, LOG_PREFIX + "命令不能为空");
            return false;
        }
        
        // 检查设备是否存在
        Device device = deviceMap.get(deviceId);
        if (device == null) {
            Log.e(TAG, LOG_PREFIX + "设备不存在：" + deviceId);
            return false;
        }
        
        // 检查设备是否在线
        if (!device.isOnline) {
            Log.w(TAG, LOG_PREFIX + "设备离线，加入命令队列：" + deviceId);
            return enqueuePendingCommand(deviceId, command, params);
        }
        
        try {
            // 创建控制命令消息
            String message = DeviceProtocol.ControlMessage.createMessage(command, params);
            
            // 获取设备控制主题
            String topic = DeviceProtocol.ControlTopics.getControlTopic(deviceId);
            
            // 通过 MQTT 发送命令
            boolean success = publishMQTT(topic, message);
            
            if (success) {
                Log.d(TAG, LOG_PREFIX + "发送命令：" + deviceId + " -> " + command);
                Log.d(TAG, LOG_PREFIX + "主题：" + topic);
                Log.d(TAG, LOG_PREFIX + "消息：" + message);
            } else {
                // MQTT 发送失败，加入队列
                Log.w(TAG, LOG_PREFIX + "MQTT 发送失败，加入队列：" + command);
                return enqueuePendingCommand(deviceId, command, params);
            }
            
            return success;
            
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "发送命令失败", e);
            return false;
        }
    }
    
    /**
     * 将命令加入待发送队列
     */
    private boolean enqueuePendingCommand(String deviceId, String command, JSONObject params) {
        if (pendingCommandQueue.size() >= MAX_PENDING_COMMANDS) {
            // 队列满，丢弃最旧的命令
            PendingCommand oldest = pendingCommandQueue.poll();
            if (oldest != null) {
                Log.w(TAG, LOG_PREFIX + "命令队列已满，丢弃旧命令: " + oldest.command);
            }
        }
        pendingCommandQueue.add(new PendingCommand(deviceId, command, params));
        Log.d(TAG, LOG_PREFIX + "命令已入队: " + command + " (队列: " + pendingCommandQueue.size() + ")");
        return true; // 返回 true 表示已接受命令
    }
    
    /**
     * 发送控制命令（无参数）
     */
    public boolean sendCommand(String deviceId, String command) {
        return sendCommand(deviceId, command, null);
    }
    
    public boolean openDevice(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.CommandTypes.OPEN);
    }
    
    public boolean closeDevice(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.CommandTypes.CLOSE);
    }
    
    // ==================== 窗帘控制 ====================
    
    public boolean openCurtain(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.CurtainCommands.OPEN);
    }
    
    public boolean closeCurtain(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.CurtainCommands.CLOSE);
    }
    
    public boolean stopCurtain(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.CurtainCommands.STOP);
    }
    
    public boolean setCurtainPosition(String deviceId, int position) {
        if (position < 0 || position > 100) {
            Log.e(TAG, LOG_PREFIX + "窗帘位置必须在 0-100 之间");
            return false;
        }
        
        try {
            JSONObject params = new JSONObject();
            params.put("position", position);
            return sendCommand(deviceId, DeviceProtocol.CurtainCommands.SET_POSITION, params);
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "设置窗帘位置失败", e);
            return false;
        }
    }
    
    // ==================== 灯光控制 ====================
    
    public boolean turnOnLight(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.LightCommands.ON);
    }
    
    public boolean turnOffLight(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.LightCommands.OFF);
    }
    
    public boolean dimLight(String deviceId, int brightness) {
        if (brightness < 0 || brightness > 100) {
            Log.e(TAG, LOG_PREFIX + "亮度必须在 0-100 之间");
            return false;
        }
        
        try {
            JSONObject params = new JSONObject();
            params.put("brightness", brightness);
            return sendCommand(deviceId, DeviceProtocol.LightCommands.DIM, params);
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "调节亮度失败", e);
            return false;
        }
    }
    
    public boolean setLightColor(String deviceId, int r, int g, int b) {
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
            Log.e(TAG, LOG_PREFIX + "RGB 值必须在 0-255 之间");
            return false;
        }
        
        try {
            JSONObject params = new JSONObject();
            params.put("r", r);
            params.put("g", g);
            params.put("b", b);
            return sendCommand(deviceId, DeviceProtocol.LightCommands.COLOR, params);
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "设置颜色失败", e);
            return false;
        }
    }
    
    // ==================== 插座控制 ====================
    
    public boolean turnOnSocket(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.SocketCommands.ON);
    }
    
    public boolean turnOffSocket(String deviceId) {
        return sendCommand(deviceId, DeviceProtocol.SocketCommands.OFF);
    }
    
    // ==================== 设备状态更新 ====================
    
    /**
     * 更新设备状态
     * 
     * @param deviceId 设备 ID
     * @param status 状态对象
     */
    public void updateDeviceStatus(String deviceId, DeviceStatus status) {
        if (deviceId == null || status == null) {
            return;
        }
        
        status.deviceId = deviceId;
        status.lastUpdate = System.currentTimeMillis();
        
        // 更新缓存
        statusMap.put(deviceId, status);
        
        // 保存到数据库
        saveDeviceToDatabase(deviceId);
        
        // 通知监听器
        notifyStatusChanged(deviceId, status);
        
        Log.d(TAG, LOG_PREFIX + "设备状态更新：" + deviceId + " -> " + status.status);
    }
    
    /**
     * 处理设备响应
     * 
     * @param deviceId 设备 ID
     * @param responseJson 响应 JSON
     */
    public void handleDeviceResponse(String deviceId, String responseJson) {
        DeviceProtocol.ResponseMessage response = DeviceProtocol.ResponseMessage.parseMessage(responseJson);
        if (response != null) {
            if (response.success) {
                Log.d(TAG, LOG_PREFIX + "设备响应成功：" + deviceId + " - " + response.message);
            } else {
                Log.e(TAG, LOG_PREFIX + "设备响应失败：" + deviceId + " - " + response.message);
            }
        }
    }
    
    // ==================== 设备状态监听器 ====================
    
    public void addStatusListener(DeviceStatusListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeStatusListener(DeviceStatusListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyStatusChanged(String deviceId, DeviceStatus status) {
        for (DeviceStatusListener listener : listeners) {
            try {
                listener.onStatusChanged(deviceId, status);
            } catch (Exception e) {
                Log.e(TAG, LOG_PREFIX + "通知监听器失败", e);
            }
        }
    }
    
    // ==================== 辅助方法 ====================
    
    private String generateDeviceName(String deviceType, String deviceId) {
        String typeNames = deviceType.substring(0, 1).toUpperCase() + deviceType.substring(1);
        return typeNames + " - " + deviceId;
    }
    
    /**
     * 获取待发送命令队列大小
     */
    public int getPendingCommandCount() {
        return pendingCommandQueue.size();
    }
    
    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 检查 MQTT 是否已连接
     */
    public boolean isMQTTConnected() {
        return mqttConnected;
    }
    
    // ==================== 接口定义 ====================
    
    /**
     * 设备状态监听器接口
     */
    public interface DeviceStatusListener {
        void onStatusChanged(String deviceId, DeviceStatus status);
    }
}
