package com.fusion.companion.service;

import android.content.Context;
import android.util.Log;

import com.fusion.companion.database.SensorDatabase;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT 数据存储服务
 * 监听 MQTT 主题，自动存储传感器数据和设备状态
 * 
 * 订阅主题：
 * - sensors/#：传感器数据主题
 * - devices/#：设备状态主题
 * 
 * 消息格式示例：
 * 传感器数据：{"device_id":"bedroom-phone-c","sensor_type":"temperature","value":25.5,"unit":"°C"}
 * 设备状态：{"device_id":"bedroom-phone-c","is_online":true,"battery_level":85,"mode":"normal"}
 * 
 * @author Fusion
 * @version 1.0
 */
public class MQTTDataStorageService {
    
    private static final String TAG = "MQTTDataStorage";
    
    // MQTT Broker 服务引用
    private final MQTTBrokerService mqttBroker;
    
    // 数据库实例
    private final SensorDatabase database;
    
    // Gson 解析器
    private final Gson gson;
    
    // 服务运行状态
    private boolean isRunning = false;
    
    // 已订阅的主题（避免重复订阅）
    private final Map<String, Boolean> subscribedTopics;
    
    // 上下文引用
    private final Context context;
    
    /**
     * 构造函数
     * @param context 应用上下文
     * @param mqttBroker MQTT Broker 服务实例
     */
    public MQTTDataStorageService(Context context, MQTTBrokerService mqttBroker) {
        this.context = context.getApplicationContext();
        this.mqttBroker = mqttBroker;
        this.database = SensorDatabase.getInstance(this.context);
        this.gson = new Gson();
        this.subscribedTopics = new ConcurrentHashMap<>();
        
        Log.i(TAG, "MQTT 数据存储服务初始化完成");
    }
    
    /**
     * 启动数据存储服务
     * 订阅传感器和设备主题
     */
    public void start() {
        if (isRunning) {
            Log.w(TAG, "服务已在运行中，跳过启动");
            return;
        }
        
        Log.i(TAG, "启动 MQTT 数据存储服务");
        
        // 订阅传感器数据主题
        subscribeToSensorTopics();
        
        // 订阅设备状态主题
        subscribeToDeviceTopics();
        
        isRunning = true;
        Log.i(TAG, "MQTT 数据存储服务启动完成");
    }
    
    /**
     * 停止数据存储服务
     * 取消所有订阅
     */
    public void stop() {
        if (!isRunning) {
            Log.w(TAG, "服务未运行，跳过停止");
            return;
        }
        
        Log.i(TAG, "停止 MQTT 数据存储服务");
        
        // 取消订阅传感器主题
        unsubscribeTopic("sensors/#");
        
        // 取消订阅设备主题
        unsubscribeTopic("devices/#");
        
        isRunning = false;
        Log.i(TAG, "MQTT 数据存储服务已停止");
    }
    
    /**
     * 订阅传感器数据主题
     * 支持所有以 sensors/ 开头的主题
     */
    private void subscribeToSensorTopics() {
        if (mqttBroker == null) {
            Log.w(TAG, "MQTT Broker 服务未就绪，延迟订阅传感器主题");
            return;
        }
        
        String topic = "sensors/#";
        
        boolean success = mqttBroker.subscribeTopic(topic, this::onSensorMessageReceived, 1);
        
        if (success) {
            subscribedTopics.put(topic, true);
            Log.i(TAG, "已订阅传感器主题：" + topic);
        } else {
            Log.e(TAG, "订阅传感器主题失败：" + topic);
        }
    }
    
    /**
     * 订阅设备状态主题
     * 支持所有以 devices/ 开头的主题
     */
    private void subscribeToDeviceTopics() {
        if (mqttBroker == null) {
            Log.w(TAG, "MQTT Broker 服务未就绪，延迟订阅设备主题");
            return;
        }
        
        String topic = "devices/#";
        
        boolean success = mqttBroker.subscribeTopic(topic, this::onDeviceMessageReceived, 1);
        
        if (success) {
            subscribedTopics.put(topic, true);
            Log.i(TAG, "已订阅设备主题：" + topic);
        } else {
            Log.e(TAG, "订阅设备主题失败：" + topic);
        }
    }
    
    /**
     * 取消订阅指定主题
     * @param topic 主题名称
     */
    private void unsubscribeTopic(String topic) {
        if (mqttBroker == null) return;
        mqttBroker.unsubscribeTopic(topic);
        subscribedTopics.remove(topic);
        Log.d(TAG, "已取消订阅主题：" + topic);
    }
    
    /**
     * 处理传感器消息
     * @param topic MQTT 主题
     * @param payload 消息内容（JSON 格式）
     */
    private void onSensorMessageReceived(String topic, byte[] payload) {
        if (payload == null || payload.length == 0) {
            Log.w(TAG, "收到空的传感器消息，主题：" + topic);
            return;
        }
        
        String message = new String(payload);
        Log.d(TAG, "收到传感器消息 - 主题：" + topic + ", 消息：" + message);
        
        try {
            // 解析 JSON 消息
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            
            // 提取字段
            String deviceId = json.has("device_id") ? json.get("device_id").getAsString() : extractDeviceIdFromTopic(topic);
            String sensorType = json.has("sensor_type") ? json.get("sensor_type").getAsString() : extractSensorTypeFromTopic(topic);
            double value = json.has("value") ? json.get("value").getAsDouble() : 0.0;
            String unit = json.has("unit") ? json.get("unit").getAsString() : "";
            
            // 验证必要字段
            if (deviceId == null || deviceId.isEmpty()) {
                Log.e(TAG, "传感器消息缺少 device_id 字段");
                return;
            }
            
            if (sensorType == null || sensorType.isEmpty()) {
                Log.e(TAG, "传感器消息缺少 sensor_type 字段");
                return;
            }
            
            // 存储到数据库
            long rowId = database.insertSensorData(deviceId, sensorType, value, unit);
            
            if (rowId > 0) {
                Log.i(TAG, "传感器数据已存储 - ID: " + rowId + 
                        ", 设备：" + deviceId + 
                        ", 类型：" + sensorType + 
                        ", 值：" + value + " " + unit);
            } else {
                Log.e(TAG, "传感器数据存储失败");
            }
            
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "解析传感器消息 JSON 失败：" + message, e);
        } catch (Exception e) {
            Log.e(TAG, "处理传感器消息失败", e);
        }
    }
    
    /**
     * 处理设备状态消息
     * @param topic MQTT 主题
     * @param payload 消息内容（JSON 格式）
     */
    private void onDeviceMessageReceived(String topic, byte[] payload) {
        if (payload == null || payload.length == 0) {
            Log.w(TAG, "收到空的设备消息，主题：" + topic);
            return;
        }
        
        String message = new String(payload);
        Log.d(TAG, "收到设备消息 - 主题：" + topic + ", 消息：" + message);
        
        try {
            // 解析 JSON 消息
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            
            // 提取字段
            String deviceId = json.has("device_id") ? json.get("device_id").getAsString() : extractDeviceIdFromTopic(topic);
            boolean isOnline = json.has("is_online") ? json.get("is_online").getAsBoolean() : true;
            
            Integer batteryLevel = null;
            if (json.has("battery_level")) {
                batteryLevel = json.get("battery_level").getAsInt();
            }
            
            String mode = json.has("mode") ? json.get("mode").getAsString() : null;
            
            // 验证必要字段
            if (deviceId == null || deviceId.isEmpty()) {
                Log.e(TAG, "设备消息缺少 device_id 字段");
                return;
            }
            
            // 更新设备状态
            long rowId = database.updateDeviceState(deviceId, isOnline, batteryLevel, mode);
            
            Log.i(TAG, "设备状态已更新 - 设备：" + deviceId + 
                    ", 在线：" + isOnline + 
                    ", 电量：" + (batteryLevel != null ? batteryLevel + "%" : "N/A") + 
                    ", 模式：" + (mode != null ? mode : "N/A"));
            
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "解析设备消息 JSON 失败：" + message, e);
        } catch (Exception e) {
            Log.e(TAG, "处理设备消息失败", e);
        }
    }
    
    /**
     * 从主题中提取设备 ID
     * 例如：sensors/bedroom-phone-c/temperature -> bedroom-phone-c
     * @param topic MQTT 主题
     * @return 设备 ID
     */
    private String extractDeviceIdFromTopic(String topic) {
        if (topic == null || topic.isEmpty()) {
            return null;
        }
        
        // 移除前缀
        String[] parts;
        if (topic.startsWith("sensors/")) {
            parts = topic.substring(8).split("/");
        } else if (topic.startsWith("devices/")) {
            parts = topic.substring(8).split("/");
        } else {
            return null;
        }
        
        if (parts.length > 0) {
            return parts[0];
        }
        
        return null;
    }
    
    /**
     * 从主题中提取传感器类型
     * 例如：sensors/bedroom-phone-c/temperature -> temperature
     * @param topic MQTT 主题
     * @return 传感器类型
     */
    private String extractSensorTypeFromTopic(String topic) {
        if (topic == null || topic.isEmpty()) {
            return null;
        }
        
        if (!topic.startsWith("sensors/")) {
            return null;
        }
        
        String[] parts = topic.substring(8).split("/");
        
        if (parts.length > 1) {
            return parts[1];
        }
        
        return null;
    }
    
    /**
     * 检查服务是否正在运行
     * @return true 如果服务正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * 获取已订阅的主题列表
     * @return 主题列表
     */
    public String[] getSubscribedTopics() {
        return subscribedTopics.keySet().toArray(new String[0]);
    }
    
    /**
     * 手动触发数据清理
     */
    public void cleanupOldData() {
        database.cleanupOldData();
    }
    
    /**
     * 获取数据库实例（用于高级查询）
     * @return SensorDatabase 实例
     */
    public SensorDatabase getDatabase() {
        return database;
    }
}
