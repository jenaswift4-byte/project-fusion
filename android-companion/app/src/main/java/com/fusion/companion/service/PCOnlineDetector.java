package com.fusion.companion.service;

import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PC 在线检测器
 * 
 * 功能特性：
 * - 支持多个 PC 检测（主 PC、备用 PC）
 * - 双重检测机制：Ping 检测 + MQTT 心跳检测
 * - 每 10 秒 ping 一次 PC，超时时间 30 秒
 * - 低功耗设计，使用 WakeLock 控制
 * - 状态变化回调通知
 * - 通过 MQTT 发布 PC 状态
 * 
 * 检测逻辑：
 * 1. Ping 检测：每 10 秒 ping 一次 PC，连续 3 次失败判定为离线
 * 2. MQTT 心跳：PC 端定期发送心跳到 MQTT，手机接收判断在线
 * 3. 任意一种检测成功即认为 PC 在线
 * 
 * @author Fusion
 * @version 1.0
 */
public class PCOnlineDetector {
    
    private static final String TAG = "PCOnlineDetector";
    
    // ==================== 配置常量 ====================
    
    // Ping 检测配置
    private static final int PING_INTERVAL_MS = 10000;        // Ping 间隔 10 秒
    private static final int PING_TIMEOUT_MS = 30000;         // Ping 超时 30 秒
    private static final int OFFLINE_THRESHOLD = 3;           // 离线判定阈值（连续失败次数）
    
    // MQTT 心跳配置
    private static final int HEARTBEAT_TIMEOUT_MS = 60000;    // 心跳超时 60 秒（超过未收到心跳判定离线）
    private static final String MQTT_BROKER_HOST = "192.168.1.100";  // MQTT Broker 地址
    private static final int MQTT_BROKER_PORT = 1883;         // MQTT Broker 端口
    private static final String TOPIC_PC_STATUS = "fusion/pc/status";      // PC 状态发布主题
    private static final String TOPIC_PC_HEARTBEAT = "fusion/pc/heartbeat"; // PC 心跳订阅主题
    
    // WakeLock 配置
    private static final String WAKE_LOCK_TAG = "Fusion::PCOnlineDetector";
    private static final int WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000; // 10 分钟超时
    
    // ==================== 成员变量 ====================
    
    // Android 上下文（使用 Application Context）
    private final android.content.Context context;
    
    // 电源管理器（用于 WakeLock）
    private final PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    
    // 主线程 Handler
    private final Handler handler;
    
    // 线程池（用于异步 Ping 检测）
    private final ExecutorService executor;
    
    // PC 配置列表
    private final List<PCConfig> pcConfigs;
    
    // PC 状态缓存
    private final Map<String, PCStatus> pcStatusMap;
    
    // 检测器运行状态
    private final AtomicBoolean isRunning;
    
    // MQTT 客户端
    private MqttClient mqttClient;
    private final AtomicBoolean mqttConnected;
    
    // 监听器列表
    private final List<PCOnlineListener> listeners;
    
    // Gson 用于 JSON 序列化
    private final com.google.gson.Gson gson;
    
    // 当前活跃 PC（最后在线的 PC）
    private String activePcId;
    
    /**
     * PC 配置类
     */
    public static class PCConfig {
        public String id;           // PC 唯一标识
        public String ipAddress;    // IP 地址或主机名
        public String name;         // PC 名称（可选）
        public boolean enabled;     // 是否启用
        
        public PCConfig(String id, String ipAddress, String name) {
            this.id = id;
            this.ipAddress = ipAddress;
            this.name = name;
            this.enabled = true;
        }
        
        public PCConfig(String id, String ipAddress) {
            this(id, ipAddress, null);
        }
    }
    
    /**
     * PC 状态类
     */
    public static class PCStatus {
        public String pcId;             // PC ID
        public boolean isOnline;        // 是否在线
        public long lastSeen;           // 最后在线时间戳
        public int consecutiveFailures; // 连续失败次数
        public String ipAddress;        // PC IP 地址
        public DetectionMode mode;      // 检测模式
        
        public PCStatus(String pcId, String ipAddress) {
            this.pcId = pcId;
            this.ipAddress = ipAddress;
            this.isOnline = false;
            this.lastSeen = 0;
            this.consecutiveFailures = 0;
            this.mode = DetectionMode.OFFLINE;
        }
    }
    
    /**
     * 检测模式枚举
     */
    public enum DetectionMode {
        OFFLINE,        // 离线
        PING,           // 通过 Ping 检测到
        MQTT_HEARTBEAT  // 通过 MQTT 心跳检测到
    }
    
    /**
     * PC 状态变化监听器接口
     */
    public interface PCOnlineListener {
        void onPCOnline(String pcId, String ipAddress);   // PC 上线
        void onPCOffline(String pcId, String ipAddress);  // PC 离线
    }
    
    /**
     * PC 心跳消息模型
     */
    public static class HeartbeatMessage {
        public String pc_id;            // PC 标识
        public String ip_address;       // PC 地址
        public long timestamp;          // 时间戳
        public String hostname;         // 主机名
        public Map<String, Object> extra; // 额外信息
        
        @Override
        public String toString() {
            return "HeartbeatMessage{" +
                    "pc_id='" + pc_id + '\'' +
                    ", ip_address='" + ip_address + '\'' +
                    ", timestamp=" + timestamp +
                    ", hostname='" + hostname + '\'' +
                    '}';
        }
    }
    
    /**
     * PC 状态消息模型（发布到 MQTT）
     */
    public static class PCStatusMessage {
        public boolean pc_online;       // PC 是否在线
        public String pc_ip;            // PC IP 地址
        public long last_seen;          // 最后在线时间
        public String mode;             // 检测模式
        public String pc_id;            // PC 标识
        public long timestamp;          // 消息时间戳
        
        public PCStatusMessage(PCStatus status) {
            this.pc_online = status.isOnline;
            this.pc_ip = status.ipAddress;
            this.last_seen = status.lastSeen;
            this.mode = status.mode.name().toLowerCase();
            this.pc_id = status.pcId;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return "PCStatusMessage{" +
                    "pc_online=" + pc_online +
                    ", pc_ip='" + pc_ip + '\'' +
                    ", last_seen=" + last_seen +
                    ", mode='" + mode + '\'' +
                    ", pc_id='" + pc_id + '\'' +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
    
    // ==================== 构造和初始化 ====================
    
    /**
     * 构造函数
     * @param context Android 上下文
     */
    public PCOnlineDetector(android.content.Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(3);
        this.pcConfigs = new ArrayList<>();
        this.pcStatusMap = new ConcurrentHashMap<>();
        this.isRunning = new AtomicBoolean(false);
        this.mqttConnected = new AtomicBoolean(false);
        this.listeners = new ArrayList<>();
        this.gson = new com.google.gson.Gson();
        
        // 初始化电源管理器
        this.powerManager = (PowerManager) this.context.getSystemService(
            android.content.Context.POWER_SERVICE
        );
        
        Log.i(TAG, "PC 在线检测器初始化完成");
    }
    
    /**
     * 添加 PC 配置
     * @param pcConfig PC 配置对象
     */
    public void addPCConfig(PCConfig pcConfig) {
        pcConfigs.add(pcConfig);
        pcStatusMap.put(pcConfig.id, new PCStatus(pcConfig.id, pcConfig.ipAddress));
        Log.i(TAG, "添加 PC 配置：" + pcConfig.id + " - " + pcConfig.ipAddress);
    }
    
    /**
     * 添加 PC 配置（便捷方法）
     * @param id PC 唯一标识
     * @param ipAddress IP 地址或主机名
     * @param name PC 名称
     */
    public void addPCConfig(String id, String ipAddress, String name) {
        addPCConfig(new PCConfig(id, ipAddress, name));
    }
    
    /**
     * 添加 PC 配置（简化方法）
     * @param id PC 唯一标识
     * @param ipAddress IP 地址或主机名
     */
    public void addPCConfig(String id, String ipAddress) {
        addPCConfig(new PCConfig(id, ipAddress));
    }
    
    /**
     * 移除 PC 配置
     * @param pcId PC 唯一标识
     */
    public void removePCConfig(String pcId) {
        PCConfig removed = null;
        for (PCConfig config : pcConfigs) {
            if (config.id.equals(pcId)) {
                removed = config;
                break;
            }
        }
        
        if (removed != null) {
            pcConfigs.remove(removed);
            pcStatusMap.remove(pcId);
            Log.i(TAG, "移除 PC 配置：" + pcId);
        }
    }
    
    /**
     * 注册状态变化监听器
     * @param listener 监听器
     */
    public void addListener(PCOnlineListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            Log.d(TAG, "注册监听器，当前监听器数量：" + listeners.size());
        }
    }
    
    /**
     * 注销状态变化监听器
     * @param listener 监听器
     */
    public void removeListener(PCOnlineListener listener) {
        listeners.remove(listener);
        Log.d(TAG, "注销监听器，当前监听器数量：" + listeners.size());
    }
    
    // ==================== 启动和停止 ====================
    
    /**
     * 启动 PC 在线检测
     */
    public void startDetection() {
        if (isRunning.get()) {
            Log.w(TAG, "检测器已在运行，跳过启动");
            return;
        }
        
        Log.i(TAG, "启动 PC 在线检测");
        
        // 获取 WakeLock（防止 CPU 休眠）
        acquireWakeLock();
        
        // 启动 Ping 检测循环
        startPingDetection();
        
        // 启动 MQTT 心跳监听
        startMQTTListener();
        
        isRunning.set(true);
    }
    
    /**
     * 停止 PC 在线检测
     */
    public void stopDetection() {
        if (!isRunning.get()) {
            Log.w(TAG, "检测器未运行，跳过停止");
            return;
        }
        
        Log.i(TAG, "停止 PC 在线检测");
        
        isRunning.set(false);
        
        // 停止 MQTT 监听
        stopMQTTListener();
        
        // 释放 WakeLock
        releaseWakeLock();
        
        // 关闭线程池
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        Log.i(TAG, "检测器已停止");
    }
    
    // ==================== Ping 检测机制 ====================
    
    /**
     * 启动 Ping 检测循环
     */
    private void startPingDetection() {
        Log.i(TAG, "启动 Ping 检测循环 - 间隔：" + PING_INTERVAL_MS + "ms, 超时：" + PING_TIMEOUT_MS + "ms");
        
        // 提交到线程池执行
        executor.submit(() -> {
            while (isRunning.get()) {
                // 遍历所有 PC 配置进行 Ping 检测
                for (PCConfig config : pcConfigs) {
                    if (!config.enabled) {
                        continue;
                    }
                    
                    // 异步执行 Ping
                    executor.execute(() -> {
                        boolean reachable = pingPC(config.ipAddress);
                        updatePCStatus(config.id, config.ipAddress, reachable, DetectionMode.PING);
                    });
                }
                
                // 等待下一次检测
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Log.w(TAG, "Ping 检测被中断");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            Log.i(TAG, "Ping 检测循环已退出");
        });
    }
    
    /**
     * Ping 指定 IP 地址
     * @param ipAddress IP 地址或主机名
     * @return true 如果可达
     */
    private boolean pingPC(String ipAddress) {
        try {
            Log.d(TAG, "开始 Ping: " + ipAddress);
            
            InetAddress address = InetAddress.getByName(ipAddress);
            long startTime = System.currentTimeMillis();
            
            // 执行 Ping，超时时间 30 秒
            boolean isReachable = address.isReachable(PING_TIMEOUT_MS);
            
            long endTime = System.currentTimeMillis();
            long pingTime = endTime - startTime;
            
            if (isReachable) {
                Log.d(TAG, "Ping 成功：" + ipAddress + " - 耗时：" + pingTime + "ms");
            } else {
                Log.w(TAG, "Ping 失败：" + ipAddress + " - 超时或不可达");
            }
            
            return isReachable;
            
        } catch (IOException e) {
            Log.e(TAG, "Ping 异常：" + ipAddress + " - " + e.getMessage());
            return false;
        }
    }
    
    // ==================== MQTT 心跳检测机制 ====================
    
    /**
     * 启动 MQTT 心跳监听
     */
    private void startMQTTListener() {
        Log.i(TAG, "启动 MQTT 心跳监听 - Broker: " + MQTT_BROKER_HOST + ":" + MQTT_BROKER_PORT);
        
        executor.execute(() -> {
            String brokerUrl = "tcp://" + MQTT_BROKER_HOST + ":" + MQTT_BROKER_PORT;
            String clientId = "PCDetector-" + System.currentTimeMillis();
            
            try {
                // 创建 MQTT 客户端
                mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
                
                // 配置连接选项
                MqttConnectOptions connOpts = new MqttConnectOptions();
                connOpts.setCleanSession(true);
                connOpts.setConnectionTimeout(30);
                connOpts.setKeepAliveInterval(60);
                connOpts.setAutomaticReconnect(true);
                
                // 设置回调
                mqttClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        Log.i(TAG, "MQTT 连接成功 - 重连：" + reconnect);
                        mqttConnected.set(true);
                        
                        // 订阅心跳主题
                        subscribeToHeartbeatTopic();
                    }
                    
                    @Override
                    public void connectionLost(Throwable throwable) {
                        Log.w(TAG, "MQTT 连接丢失：" + throwable.getMessage());
                        mqttConnected.set(false);
                    }
                    
                    @Override
                    public void messageArrived(String topic, MqttMessage message) throws Exception {
                        Log.d(TAG, "收到 MQTT 消息 - 主题：" + topic);
                        
                        if (TOPIC_PC_HEARTBEAT.equals(topic)) {
                            handleHeartbeatMessage(new String(message.getPayload()));
                        }
                    }
                    
                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        // 发布完成回调（暂不处理）
                    }
                });
                
                // 连接到 Broker
                mqttClient.connect(connOpts);
                Log.i(TAG, "MQTT 连接请求已发送");
                
                // 等待连接
                int waitCount = 0;
                while (!mqttConnected.get() && waitCount < 10) {
                    Thread.sleep(1000);
                    waitCount++;
                }
                
                if (!mqttConnected.get()) {
                    Log.w(TAG, "MQTT 连接超时");
                }
                
            } catch (MqttException e) {
                Log.e(TAG, "MQTT 连接失败：" + e.getMessage(), e);
            } catch (InterruptedException e) {
                Log.w(TAG, "MQTT 连接被中断");
                Thread.currentThread().interrupt();
            }
        });
    }
    
    /**
     * 订阅心跳主题
     */
    private void subscribeToHeartbeatTopic() {
        if (!mqttConnected.get() || mqttClient == null) {
            return;
        }
        
        try {
            mqttClient.subscribe(TOPIC_PC_HEARTBEAT, 1);
            Log.i(TAG, "已订阅心跳主题：" + TOPIC_PC_HEARTBEAT);
        } catch (MqttException e) {
            Log.e(TAG, "订阅心跳主题失败：" + e.getMessage(), e);
        }
    }
    
    /**
     * 处理心跳消息
     * @param payload JSON 消息内容
     */
    private void handleHeartbeatMessage(String payload) {
        try {
            Log.d(TAG, "收到心跳：" + payload);
            
            HeartbeatMessage heartbeat = gson.fromJson(payload, HeartbeatMessage.class);
            
            if (heartbeat == null || heartbeat.pc_id == null) {
                Log.w(TAG, "心跳消息格式错误");
                return;
            }
            
            // 查找对应的 PC 配置
            PCConfig config = null;
            for (PCConfig pcConfig : pcConfigs) {
                if (pcConfig.id.equals(heartbeat.pc_id)) {
                    config = pcConfig;
                    break;
                }
            }
            
            if (config == null) {
                Log.w(TAG, "未找到 PC 配置：" + heartbeat.pc_id);
                return;
            }
            
            // 更新 PC 状态
            updatePCStatus(config.id, config.ipAddress, true, DetectionMode.MQTT_HEARTBEAT);
            
        } catch (Exception e) {
            Log.e(TAG, "处理心跳消息失败：" + e.getMessage(), e);
        }
    }
    
    /**
     * 停止 MQTT 监听
     */
    private void stopMQTTListener() {
        Log.i(TAG, "停止 MQTT 监听");
        
        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect(3000);
                }
                mqttClient.close();
                mqttClient = null;
            } catch (MqttException e) {
                Log.e(TAG, "断开 MQTT 连接失败", e);
            }
        }
        
        mqttConnected.set(false);
    }
    
    // ==================== 状态更新和通知 ====================
    
    /**
     * 更新 PC 状态
     * @param pcId PC 标识
     * @param ipAddress IP 地址
     * @param isReachable 是否可达
     * @param mode 检测模式
     */
    private void updatePCStatus(String pcId, String ipAddress, boolean isReachable, DetectionMode mode) {
        PCStatus status = pcStatusMap.get(pcId);
        if (status == null) {
            return;
        }
        
        boolean statusChanged = false;
        
        if (isReachable) {
            // PC 可达
            if (!status.isOnline) {
                // 从离线变为在线
                status.isOnline = true;
                status.lastSeen = System.currentTimeMillis();
                status.consecutiveFailures = 0;
                status.mode = mode;
                statusChanged = true;
                activePcId = pcId;
                
                Log.i(TAG, "PC 上线：" + pcId + " - " + ipAddress + " (检测方式：" + mode + ")");
                
                // 通知监听器
                notifyPCOnline(pcId, ipAddress);
                
                // 发布 MQTT 状态
                publishPCStatus(status);
            } else {
                // 保持在线状态
                status.lastSeen = System.currentTimeMillis();
                status.consecutiveFailures = 0;
                status.mode = mode;
            }
        } else {
            // PC 不可达
            status.consecutiveFailures++;
            Log.d(TAG, "PC 检测失败：" + pcId + " - 连续失败次数：" + status.consecutiveFailures);
            
            // 连续失败达到阈值，判定为离线
            if (status.consecutiveFailures >= OFFLINE_THRESHOLD && status.isOnline) {
                status.isOnline = false;
                status.mode = DetectionMode.OFFLINE;
                statusChanged = true;
                
                // 如果活跃 PC 离线，清除活跃 PC
                if (activePcId != null && activePcId.equals(pcId)) {
                    activePcId = null;
                }
                
                Log.i(TAG, "PC 离线：" + pcId + " - " + ipAddress);
                
                // 通知监听器
                notifyPCOffline(pcId, ipAddress);
                
                // 发布 MQTT 状态
                publishPCStatus(status);
            }
        }
    }
    
    /**
     * 通知监听器 PC 上线
     */
    private void notifyPCOnline(String pcId, String ipAddress) {
        handler.post(() -> {
            for (PCOnlineListener listener : listeners) {
                try {
                    listener.onPCOnline(pcId, ipAddress);
                } catch (Exception e) {
                    Log.e(TAG, "监听器回调异常", e);
                }
            }
        });
    }
    
    /**
     * 通知监听器 PC 离线
     */
    private void notifyPCOffline(String pcId, String ipAddress) {
        handler.post(() -> {
            for (PCOnlineListener listener : listeners) {
                try {
                    listener.onPCOffline(pcId, ipAddress);
                } catch (Exception e) {
                    Log.e(TAG, "监听器回调异常", e);
                }
            }
        });
    }
    
    /**
     * 发布 PC 状态到 MQTT
     */
    private void publishPCStatus(PCStatus status) {
        if (!mqttConnected.get() || mqttClient == null || !mqttClient.isConnected()) {
            Log.w(TAG, "MQTT 未连接，无法发布状态");
            return;
        }
        
        try {
            PCStatusMessage message = new PCStatusMessage(status);
            String json = gson.toJson(message);
            
            MqttMessage mqttMessage = new MqttMessage(json.getBytes());
            mqttMessage.setQos(1);
            mqttMessage.setRetained(false);
            
            mqttClient.publish(TOPIC_PC_STATUS, mqttMessage);
            
            Log.d(TAG, "PC 状态已发布：" + json);
            
        } catch (MqttException e) {
            Log.e(TAG, "发布 PC 状态失败：" + e.getMessage(), e);
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
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            Log.d(TAG, "WakeLock 已获取（" + (WAKE_LOCK_TIMEOUT_MS / 1000) + "秒超时）");
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
    
    // ==================== 公开查询方法 ====================
    
    /**
     * 检查 PC 是否在线
     * @param pcId PC 标识
     * @return true 如果在线
     */
    public boolean isPCOnline(String pcId) {
        PCStatus status = pcStatusMap.get(pcId);
        return status != null && status.isOnline;
    }
    
    /**
     * 获取最后在线时间
     * @param pcId PC 标识
     * @return 时间戳（毫秒），不存在返回 0
     */
    public long getLastSeen(String pcId) {
        PCStatus status = pcStatusMap.get(pcId);
        return status != null ? status.lastSeen : 0;
    }
    
    /**
     * 获取 PC 状态
     * @param pcId PC 标识
     * @return PC 状态对象
     */
    public PCStatus getPCStatus(String pcId) {
        return pcStatusMap.get(pcId);
    }
    
    /**
     * 获取所有 PC 状态
     * @return PC 状态 Map
     */
    public Map<String, PCStatus> getAllPCStatus() {
        return new ConcurrentHashMap<>(pcStatusMap);
    }
    
    /**
     * 获取活跃 PC（最后在线的 PC）
     * @return PC 标识，没有返回 null
     */
    public String getActivePcId() {
        return activePcId;
    }
    
    /**
     * 检测器是否正在运行
     * @return true 如果正在运行
     */
    public boolean isRunning() {
        return isRunning.get();
    }
    
    /**
     * 获取 MQTT 连接状态
     * @return true 如果已连接
     */
    public boolean isMQTTConnected() {
        return mqttConnected.get();
    }
    
    /**
     * 手动触发一次 Ping 检测（用于测试）
     * @param pcId PC 标识
     */
    public void triggerPingDetection(String pcId) {
        final PCConfig[] found = {null};
        for (PCConfig c : pcConfigs) {
            if (c.id.equals(pcId)) {
                found[0] = c;
                break;
            }
        }
        
        if (found[0] != null) {
            executor.execute(() -> {
                boolean reachable = pingPC(found[0].ipAddress);
                updatePCStatus(found[0].id, found[0].ipAddress, reachable, DetectionMode.PING);
            });
        }
    }
}
