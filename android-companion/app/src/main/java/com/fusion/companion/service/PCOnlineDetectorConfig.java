package com.fusion.companion.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * PC 在线检测器配置管理器
 * 
 * 功能：
 * - 管理 PC 配置列表（支持多个 PC）
 * - 持久化配置到 SharedPreferences
 * - 支持动态添加/删除/修改配置
 * - 配置变更自动通知
 * 
 * 配置项：
 * - PC 列表（ID、IP 地址、名称、启用状态）
 * - MQTT Broker 地址
 * - 检测参数（Ping 间隔、超时时间等）
 * 
 * @author Fusion
 * @version 1.0
 */
public class PCOnlineDetectorConfig {
    
    private static final String TAG = "PCDetectorConfig";
    
    // ==================== SharedPreferences 配置 ====================
    
    private static final String PREFS_NAME = "pc_detector_prefs";
    
    // PC 配置列表
    private static final String KEY_PC_CONFIGS = "pc_configs";
    
    // MQTT Broker 配置
    private static final String KEY_MQTT_HOST = "mqtt_host";
    private static final String KEY_MQTT_PORT = "mqtt_port";
    
    // 检测参数配置
    private static final String KEY_PING_INTERVAL = "ping_interval";
    private static final String KEY_PING_TIMEOUT = "ping_timeout";
    private static final String KEY_OFFLINE_THRESHOLD = "offline_threshold";
    
    // 默认配置
    private static final String DEFAULT_MQTT_HOST = "192.168.1.100";
    private static final int DEFAULT_MQTT_PORT = 1883;
    private static final int DEFAULT_PING_INTERVAL = 10000;    // 10 秒
    private static final int DEFAULT_PING_TIMEOUT = 30000;     // 30 秒
    private static final int DEFAULT_OFFLINE_THRESHOLD = 3;    // 连续 3 次失败
    
    // ==================== 成员变量 ====================
    
    private final Context context;
    private final SharedPreferences prefs;
    private final Gson gson;
    
    // 配置变更监听器
    private final List<ConfigChangeListener> configChangeListeners;
    
    /**
     * 配置变更监听器接口
     */
    public interface ConfigChangeListener {
        void onConfigChanged(String key, Object newValue);
    }
    
    /**
     * 构造函数
     * @param context Android 上下文
     */
    public PCOnlineDetectorConfig(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        this.configChangeListeners = new ArrayList<>();
        
        Log.i(TAG, "配置管理器初始化完成");
    }
    
    // ==================== PC 配置管理 ====================
    
    /**
     * 获取所有 PC 配置
     * @return PC 配置列表
     */
    public List<PCOnlineDetector.PCConfig> getPCConfigs() {
        String json = prefs.getString(KEY_PC_CONFIGS, null);
        
        if (json == null) {
            return new ArrayList<>();
        }
        
        try {
            Type listType = new TypeToken<ArrayList<PCOnlineDetector.PCConfig>>(){}.getType();
            List<PCOnlineDetector.PCConfig> configs = gson.fromJson(json, listType);
            return configs != null ? configs : new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "解析 PC 配置失败：" + e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 保存 PC 配置列表
     * @param configs PC 配置列表
     */
    public void savePCConfigs(List<PCOnlineDetector.PCConfig> configs) {
        String json = gson.toJson(configs);
        prefs.edit().putString(KEY_PC_CONFIGS, json).apply();
        
        Log.i(TAG, "PC 配置已保存，数量：" + (configs != null ? configs.size() : 0));
        
        // 通知配置变更
        notifyConfigChanged(KEY_PC_CONFIGS, configs);
    }
    
    /**
     * 添加 PC 配置
     * @param config PC 配置对象
     */
    public void addPCConfig(PCOnlineDetector.PCConfig config) {
        List<PCOnlineDetector.PCConfig> configs = getPCConfigs();
        
        // 检查是否已存在
        boolean exists = false;
        for (PCOnlineDetector.PCConfig c : configs) {
            if (c.id.equals(config.id)) {
                exists = true;
                break;
            }
        }
        
        if (!exists) {
            configs.add(config);
            savePCConfigs(configs);
            Log.i(TAG, "添加 PC 配置：" + config.id);
        } else {
            Log.w(TAG, "PC 配置已存在：" + config.id);
        }
    }
    
    /**
     * 移除 PC 配置
     * @param pcId PC 标识
     */
    public void removePCConfig(String pcId) {
        List<PCOnlineDetector.PCConfig> configs = getPCConfigs();
        
        PCOnlineDetector.PCConfig removed = null;
        for (PCOnlineDetector.PCConfig config : configs) {
            if (config.id.equals(pcId)) {
                removed = config;
                break;
            }
        }
        
        if (removed != null) {
            configs.remove(removed);
            savePCConfigs(configs);
            Log.i(TAG, "移除 PC 配置：" + pcId);
        }
    }
    
    /**
     * 更新 PC 配置
     * @param config PC 配置对象
     */
    public void updatePCConfig(PCOnlineDetector.PCConfig config) {
        List<PCOnlineDetector.PCConfig> configs = getPCConfigs();
        
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).id.equals(config.id)) {
                configs.set(i, config);
                savePCConfigs(configs);
                Log.i(TAG, "更新 PC 配置：" + config.id);
                return;
            }
        }
        
        Log.w(TAG, "PC 配置不存在，无法更新：" + config.id);
    }
    
    /**
     * 获取 PC 配置
     * @param pcId PC 标识
     * @return PC 配置对象，不存在返回 null
     */
    public PCOnlineDetector.PCConfig getPCConfig(String pcId) {
        List<PCOnlineDetector.PCConfig> configs = getPCConfigs();
        
        for (PCOnlineDetector.PCConfig config : configs) {
            if (config.id.equals(pcId)) {
                return config;
            }
        }
        
        return null;
    }
    
    /**
     * 获取启用的 PC 配置列表
     * @return 启用的 PC 配置列表
     */
    public List<PCOnlineDetector.PCConfig> getEnabledPCConfigs() {
        List<PCOnlineDetector.PCConfig> allConfigs = getPCConfigs();
        List<PCOnlineDetector.PCConfig> enabledConfigs = new ArrayList<>();
        
        for (PCOnlineDetector.PCConfig config : allConfigs) {
            if (config.enabled) {
                enabledConfigs.add(config);
            }
        }
        
        return enabledConfigs;
    }
    
    // ==================== MQTT 配置管理 ====================
    
    /**
     * 获取 MQTT Broker 主机地址
     * @return Broker 主机地址
     */
    public String getMqttHost() {
        return prefs.getString(KEY_MQTT_HOST, DEFAULT_MQTT_HOST);
    }
    
    /**
     * 设置 MQTT Broker 主机地址
     * @param host Broker 主机地址
     */
    public void setMqttHost(String host) {
        prefs.edit().putString(KEY_MQTT_HOST, host).apply();
        Log.i(TAG, "MQTT Host 已更新：" + host);
        notifyConfigChanged(KEY_MQTT_HOST, host);
    }
    
    /**
     * 获取 MQTT Broker 端口
     * @return Broker 端口
     */
    public int getMqttPort() {
        return prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT);
    }
    
    /**
     * 设置 MQTT Broker 端口
     * @param port Broker 端口
     */
    public void setMqttPort(int port) {
        prefs.edit().putInt(KEY_MQTT_PORT, port).apply();
        Log.i(TAG, "MQTT Port 已更新：" + port);
        notifyConfigChanged(KEY_MQTT_PORT, port);
    }
    
    // ==================== 检测参数配置 ====================
    
    /**
     * 获取 Ping 检测间隔
     * @return 间隔（毫秒）
     */
    public int getPingInterval() {
        return prefs.getInt(KEY_PING_INTERVAL, DEFAULT_PING_INTERVAL);
    }
    
    /**
     * 设置 Ping 检测间隔
     * @param interval 间隔（毫秒）
     */
    public void setPingInterval(int interval) {
        prefs.edit().putInt(KEY_PING_INTERVAL, interval).apply();
        Log.i(TAG, "Ping 间隔已更新：" + interval + "ms");
        notifyConfigChanged(KEY_PING_INTERVAL, interval);
    }
    
    /**
     * 获取 Ping 超时时间
     * @return 超时时间（毫秒）
     */
    public int getPingTimeout() {
        return prefs.getInt(KEY_PING_TIMEOUT, DEFAULT_PING_TIMEOUT);
    }
    
    /**
     * 设置 Ping 超时时间
     * @param timeout 超时时间（毫秒）
     */
    public void setPingTimeout(int timeout) {
        prefs.edit().putInt(KEY_PING_TIMEOUT, timeout).apply();
        Log.i(TAG, "Ping 超时已更新：" + timeout + "ms");
        notifyConfigChanged(KEY_PING_TIMEOUT, timeout);
    }
    
    /**
     * 获取离线判定阈值
     * @return 连续失败次数
     */
    public int getOfflineThreshold() {
        return prefs.getInt(KEY_OFFLINE_THRESHOLD, DEFAULT_OFFLINE_THRESHOLD);
    }
    
    /**
     * 设置离线判定阈值
     * @param threshold 连续失败次数
     */
    public void setOfflineThreshold(int threshold) {
        prefs.edit().putInt(KEY_OFFLINE_THRESHOLD, threshold).apply();
        Log.i(TAG, "离线阈值已更新：" + threshold);
        notifyConfigChanged(KEY_OFFLINE_THRESHOLD, threshold);
    }
    
    // ==================== 配置变更监听 ====================
    
    /**
     * 注册配置变更监听器
     * @param listener 监听器
     */
    public void addConfigChangeListener(ConfigChangeListener listener) {
        if (!configChangeListeners.contains(listener)) {
            configChangeListeners.add(listener);
            Log.d(TAG, "注册配置变更监听器，当前数量：" + configChangeListeners.size());
        }
    }
    
    /**
     * 注销配置变更监听器
     * @param listener 监听器
     */
    public void removeConfigChangeListener(ConfigChangeListener listener) {
        configChangeListeners.remove(listener);
        Log.d(TAG, "注销配置变更监听器，当前数量：" + configChangeListeners.size());
    }
    
    /**
     * 通知配置变更
     * @param key 配置键
     * @param newValue 新值
     */
    private void notifyConfigChanged(String key, Object newValue) {
        for (ConfigChangeListener listener : configChangeListeners) {
            try {
                listener.onConfigChanged(key, newValue);
            } catch (Exception e) {
                Log.e(TAG, "配置变更监听器回调异常", e);
            }
        }
    }
    
    // ==================== 便捷方法 ====================
    
    /**
     * 添加 PC 配置（便捷方法）
     * @param id PC 唯一标识
     * @param ipAddress IP 地址或主机名
     * @param name PC 名称
     * @param enabled 是否启用
     */
    public void addPCConfig(String id, String ipAddress, String name, boolean enabled) {
        PCOnlineDetector.PCConfig config = new PCOnlineDetector.PCConfig(id, ipAddress, name);
        config.enabled = enabled;
        addPCConfig(config);
    }
    
    /**
     * 添加 PC 配置（简化方法）
     * @param id PC 唯一标识
     * @param ipAddress IP 地址或主机名
     */
    public void addPCConfig(String id, String ipAddress) {
        addPCConfig(id, ipAddress, null, true);
    }
    
    /**
     * 启用/禁用 PC 配置
     * @param pcId PC 标识
     * @param enabled 是否启用
     */
    public void setPCEnabled(String pcId, boolean enabled) {
        PCOnlineDetector.PCConfig config = getPCConfig(pcId);
        if (config != null) {
            config.enabled = enabled;
            updatePCConfig(config);
        }
    }
    
    /**
     * 检查配置是否有效
     * @return true 如果配置有效
     */
    public boolean isValidConfig() {
        List<PCOnlineDetector.PCConfig> configs = getEnabledPCConfigs();
        return !configs.isEmpty();
    }
    
    /**
     * 清空所有配置
     */
    public void clearAllConfigs() {
        prefs.edit().clear().apply();
        Log.i(TAG, "所有配置已清空");
        notifyConfigChanged("all", null);
    }
    
    /**
     * 导出配置为 JSON
     * @return JSON 字符串
     */
    public String exportConfigToJson() {
        ConfigExport export = new ConfigExport();
        export.pc_configs = getPCConfigs();
        export.mqtt_host = getMqttHost();
        export.mqtt_port = getMqttPort();
        export.ping_interval = getPingInterval();
        export.ping_timeout = getPingTimeout();
        export.offline_threshold = getOfflineThreshold();
        
        return gson.toJson(export);
    }
    
    /**
     * 从 JSON 导入配置
     * @param json JSON 字符串
     * @return true 如果导入成功
     */
    public boolean importConfigFromJson(String json) {
        try {
            ConfigExport export = gson.fromJson(json, ConfigExport.class);
            
            if (export != null) {
                if (export.pc_configs != null) {
                    savePCConfigs(export.pc_configs);
                }
                if (export.mqtt_host != null) {
                    setMqttHost(export.mqtt_host);
                }
                if (export.mqtt_port > 0) {
                    setMqttPort(export.mqtt_port);
                }
                if (export.ping_interval > 0) {
                    setPingInterval(export.ping_interval);
                }
                if (export.ping_timeout > 0) {
                    setPingTimeout(export.ping_timeout);
                }
                if (export.offline_threshold > 0) {
                    setOfflineThreshold(export.offline_threshold);
                }
                
                Log.i(TAG, "配置导入成功");
                return true;
            }
            
            return false;
        } catch (Exception e) {
            Log.e(TAG, "配置导入失败：" + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 配置导出模型类
     */
    public static class ConfigExport {
        public List<PCOnlineDetector.PCConfig> pc_configs;
        public String mqtt_host;
        public int mqtt_port;
        public int ping_interval;
        public int ping_timeout;
        public int offline_threshold;
    }
}
