package com.fusion.companion.device;

import org.json.JSONObject;

/**
 * 设备信息模型
 * 
 * 表示 ESP32 设备的基本信息
 * 
 * @author Backend Architect
 * @version 1.0.0
 */
public class Device {
    
    /** 设备唯一标识 ID */
    public String deviceId;
    
    /** 设备类型（curtain, light, socket, sensor 等） */
    public String deviceType;
    
    /** 设备名称（用户自定义） */
    public String name;
    
    /** 设备描述 */
    public String description;
    
    /** 设备位置/房间 */
    public String location;
    
    /** 设备固件版本 */
    public String firmwareVersion;
    
    /** 设备硬件版本 */
    public String hardwareVersion;
    
    /** 设备 MAC 地址 */
    public String macAddress;
    
    /** 设备 IP 地址 */
    public String ipAddress;
    
    /** 是否在线 */
    public boolean isOnline;
    
    /** 注册时间（毫秒时间戳） */
    public long registeredAt;
    
    /** 最后_seen 时间（毫秒时间戳） */
    public long lastSeenAt;
    
    /** 设备配置（JSON 格式） */
    public JSONObject config;
    
    /** 设备能力列表 */
    public String[] capabilities;
    
    /** 是否启用 */
    public boolean enabled;
    
    /** 是否被收藏 */
    public boolean favorite;
    
    /** 设备图标 URL */
    public String iconUrl;
    
    /**
     * 默认构造函数
     */
    public Device() {
        this.deviceId = "";
        this.deviceType = "";
        this.name = "";
        this.description = "";
        this.location = "";
        this.firmwareVersion = "";
        this.hardwareVersion = "";
        this.macAddress = "";
        this.ipAddress = "";
        this.isOnline = false;
        this.registeredAt = 0;
        this.lastSeenAt = 0;
        this.config = null;
        this.capabilities = new String[0];
        this.enabled = true;
        this.favorite = false;
        this.iconUrl = "";
    }
    
    /**
     * 创建设备对象
     * 
     * @param deviceId 设备 ID
     * @param deviceType 设备类型
     * @param name 设备名称
     * @return Device 对象
     */
    public static Device create(String deviceId, String deviceType, String name) {
        Device device = new Device();
        device.deviceId = deviceId;
        device.deviceType = deviceType;
        device.name = name;
        device.registeredAt = System.currentTimeMillis();
        return device;
    }
    
    /**
     * 从 JSON 解析设备信息
     * 
     * @param json JSON 字符串
     * @return Device 对象
     */
    public static Device fromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            Device device = new Device();
            
            device.deviceId = obj.optString("deviceId", "");
            device.deviceType = obj.optString("deviceType", "");
            device.name = obj.optString("name", "");
            device.description = obj.optString("description", "");
            device.location = obj.optString("location", "");
            device.firmwareVersion = obj.optString("firmwareVersion", "");
            device.hardwareVersion = obj.optString("hardwareVersion", "");
            device.macAddress = obj.optString("macAddress", "");
            device.ipAddress = obj.optString("ipAddress", "");
            device.isOnline = obj.optBoolean("isOnline", false);
            device.registeredAt = obj.optLong("registeredAt", 0);
            device.lastSeenAt = obj.optLong("lastSeenAt", 0);
            device.config = obj.optJSONObject("config");
            device.enabled = obj.optBoolean("enabled", true);
            device.favorite = obj.optBoolean("favorite", false);
            device.iconUrl = obj.optString("iconUrl", "");
            
            // 解析能力数组
            if (obj.has("capabilities")) {
                org.json.JSONArray array = obj.getJSONArray("capabilities");
                device.capabilities = new String[array.length()];
                for (int i = 0; i < array.length(); i++) {
                    device.capabilities[i] = array.getString(i);
                }
            }
            
            return device;
            
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
            obj.put("deviceType", deviceType);
            obj.put("name", name);
            obj.put("description", description);
            obj.put("location", location);
            obj.put("firmwareVersion", firmwareVersion);
            obj.put("hardwareVersion", hardwareVersion);
            obj.put("macAddress", macAddress);
            obj.put("ipAddress", ipAddress);
            obj.put("isOnline", isOnline);
            obj.put("registeredAt", registeredAt);
            obj.put("lastSeenAt", lastSeenAt);
            if (config != null) {
                obj.put("config", config);
            }
            obj.put("enabled", enabled);
            obj.put("favorite", favorite);
            obj.put("iconUrl", iconUrl);
            
            // 添加能力数组
            if (capabilities != null && capabilities.length > 0) {
                org.json.JSONArray array = new org.json.JSONArray();
                for (String cap : capabilities) {
                    array.put(cap);
                }
                obj.put("capabilities", array);
            }
            
            return obj.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 获取设备类型名称
     * 
     * @return 类型名称
     */
    public String getTypeName() {
        if (deviceType == null || deviceType.isEmpty()) {
            return "未知设备";
        }
        
        switch (deviceType) {
            case "curtain":
                return "窗帘";
            case "light":
                return "灯光";
            case "socket":
                return "插座";
            case "sensor":
                return "传感器";
            case "switch":
                return "开关";
            case "thermostat":
                return "温控器";
            case "lock":
                return "门锁";
            default:
                return deviceType;
        }
    }
    
    /**
     * 获取设备图标
     * 
     * @return 图标资源名称
     */
    public String getIcon() {
        if (iconUrl != null && !iconUrl.isEmpty()) {
            return iconUrl;
        }
        
        // 根据设备类型返回默认图标
        switch (deviceType) {
            case "curtain":
                return "ic_curtain";
            case "light":
                return "ic_light";
            case "socket":
                return "ic_socket";
            case "sensor":
                return "ic_sensor";
            case "switch":
                return "ic_switch";
            case "thermostat":
                return "ic_thermostat";
            case "lock":
                return "ic_lock";
            default:
                return "ic_device";
        }
    }
    
    /**
     * 检查设备是否支持某项能力
     * 
     * @param capability 能力名称
     * @return 是否支持
     */
    public boolean hasCapability(String capability) {
        if (capabilities == null) {
            return false;
        }
        
        for (String cap : capabilities) {
            if (cap.equals(capability)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 获取设备状态摘要
     * 
     * @return 状态摘要
     */
    public String getStatusSummary() {
        if (isOnline) {
            return "在线";
        } else {
            if (lastSeenAt > 0) {
                long minutes = (System.currentTimeMillis() - lastSeenAt) / (1000 * 60);
                if (minutes < 60) {
                    return minutes + "分钟前在线";
                } else if (minutes < 1440) {
                    return (minutes / 60) + "小时前在线";
                } else {
                    return (minutes / 1440) + "天前在线";
                }
            }
            return "离线";
        }
    }
    
    @Override
    public String toString() {
        return "Device{" +
                "deviceId='" + deviceId + '\'' +
                ", deviceType='" + deviceType + '\'' +
                ", name='" + name + '\'' +
                ", location='" + location + '\'' +
                ", isOnline=" + isOnline +
                ", enabled=" + enabled +
                '}';
    }
}
