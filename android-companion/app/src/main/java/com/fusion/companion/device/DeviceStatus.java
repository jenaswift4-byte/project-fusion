package com.fusion.companion.device;

import org.json.JSONObject;

/**
 * 设备状态模型
 * 
 * 表示 ESP32 设备的实时状态信息
 * 
 * @author Backend Architect
 * @version 1.0.0
 */
public class DeviceStatus {
    
    /** 设备 ID */
    public String deviceId;
    
    /** 设备状态（open, close, on, off, unknown 等） */
    public String status;
    
    /** 位置信息（窗帘 0-100，灯光 0-100 等） */
    public int position;
    
    /** 亮度（灯光 0-100） */
    public int brightness;
    
    /** 电量百分比（0-100，-1 表示不支持或未知） */
    public int battery;
    
    /** 信号强度（RSSI，-1 表示未知） */
    public int rssi;
    
    /** 温度（传感器数据，-999 表示不支持或未知） */
    public float temperature;
    
    /** 湿度（传感器数据，-999 表示不支持或未知） */
    public float humidity;
    
    /** 其他数据（JSON 格式） */
    public JSONObject data;
    
    /** 最后更新时间（毫秒时间戳） */
    public long lastUpdate;
    
    /** 是否正在执行命令 */
    public boolean isExecuting;
    
    /** 当前执行的命令 */
    public String executingCommand;
    
    /**
     * 默认构造函数
     */
    public DeviceStatus() {
        this.deviceId = "";
        this.status = "unknown";
        this.position = -1;
        this.brightness = -1;
        this.battery = -1;
        this.rssi = -1;
        this.temperature = -999;
        this.humidity = -999;
        this.data = null;
        this.lastUpdate = 0;
        this.isExecuting = false;
        this.executingCommand = null;
    }
    
    /**
     * 创建设备状态对象
     * 
     * @param deviceId 设备 ID
     * @param status 状态
     * @return DeviceStatus 对象
     */
    public static DeviceStatus create(String deviceId, String status) {
        DeviceStatus ds = new DeviceStatus();
        ds.deviceId = deviceId;
        ds.status = status;
        ds.lastUpdate = System.currentTimeMillis();
        return ds;
    }
    
    /**
     * 从 JSON 解析设备状态
     * 
     * @param json JSON 字符串
     * @return DeviceStatus 对象
     */
    public static DeviceStatus fromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            DeviceStatus status = new DeviceStatus();
            
            status.deviceId = obj.optString("deviceId", "");
            status.status = obj.optString("status", "unknown");
            status.position = obj.optInt("position", -1);
            status.brightness = obj.optInt("brightness", -1);
            status.battery = obj.optInt("battery", -1);
            status.rssi = obj.optInt("rssi", -1);
            status.temperature = (float) obj.optDouble("temperature", -999);
            status.humidity = (float) obj.optDouble("humidity", -999);
            status.data = obj.optJSONObject("data");
            status.lastUpdate = obj.optLong("timestamp", System.currentTimeMillis());
            
            return status;
            
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 转换为 JSON 字符串
     * 
     * @return JSON 字符串
     */
    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("deviceId", deviceId);
            obj.put("status", status);
            obj.put("position", position);
            obj.put("brightness", brightness);
            obj.put("battery", battery);
            obj.put("rssi", rssi);
            obj.put("temperature", temperature);
            obj.put("humidity", humidity);
            if (data != null) {
                obj.put("data", data);
            }
            obj.put("lastUpdate", lastUpdate);
            obj.put("isExecuting", isExecuting);
            obj.put("executingCommand", executingCommand);
            return obj.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 判断设备是否在线
     * 
     * @return 是否在线（根据最后更新时间判断，5 分钟内更新视为在线）
     */
    public boolean isOnline() {
        long now = System.currentTimeMillis();
        long fiveMinutes = 5 * 60 * 1000;
        return (now - lastUpdate) < fiveMinutes;
    }
    
    /**
     * 获取状态描述
     * 
     * @return 状态描述字符串
     */
    public String getStatusDescription() {
        if (status == null || status.isEmpty()) {
            return "未知";
        }
        
        switch (status) {
            case "open":
                return "打开";
            case "close":
                return "关闭";
            case "on":
                return "开启";
            case "off":
                return "关闭";
            case "online":
                return "在线";
            case "offline":
                return "离线";
            case "unknown":
                return "未知";
            default:
                return status;
        }
    }
    
    /**
     * 获取电量描述
     * 
     * @return 电量描述
     */
    public String getBatteryDescription() {
        if (battery < 0) {
            return "不支持";
        }
        
        if (battery >= 80) {
            return "电量充足 (" + battery + "%)";
        } else if (battery >= 50) {
            return "电量中等 (" + battery + "%)";
        } else if (battery >= 20) {
            return "电量较低 (" + battery + "%)";
        } else {
            return "电量不足 (" + battery + "%)";
        }
    }
    
    @Override
    public String toString() {
        return "DeviceStatus{" +
                "deviceId='" + deviceId + '\'' +
                ", status='" + status + '\'' +
                ", position=" + position +
                ", brightness=" + brightness +
                ", battery=" + battery +
                ", rssi=" + rssi +
                ", temperature=" + temperature +
                ", humidity=" + humidity +
                ", lastUpdate=" + lastUpdate +
                ", isExecuting=" + isExecuting +
                '}';
    }
}
