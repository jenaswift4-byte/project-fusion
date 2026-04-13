package com.fusion.companion.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Map;

import com.fusion.companion.device.DeviceManager;

/**
 * PC 在线检测服务
 * 
 * 这是一个 Android 后台服务，封装了 PCOnlineDetector 的使用
 * 可以作为独立服务运行，也可以在其他 Service 中集成
 * 
 * 使用示例：
 * 1. 在 MainActivity 或其他地方启动服务：
 *    startService(new Intent(this, PCOnlineDetectionService.class));
 * 
 * 2. 服务会自动加载配置并启动检测
 * 
 * @author Fusion
 * @version 1.0
 */
public class PCOnlineDetectionService extends Service {
    
    private static final String TAG = "PCDetectionService";
    
    // PC 在线检测器
    private PCOnlineDetector detector;
    
    // 配置管理器
    private PCOnlineDetectorConfig config;
    
    // 主线程 Handler
    private Handler handler;
    
    // 状态变化监听器
    private PCOnlineDetector.PCOnlineListener listener;
    
    /**
     * 服务创建回调
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "PC 在线检测服务创建");
        
        handler = new Handler(Looper.getMainLooper());
        
        // 初始化配置管理器
        config = new PCOnlineDetectorConfig(this);
        
        // 初始化检测器
        detector = new PCOnlineDetector(this);
        
        // 注册状态变化监听器
        listener = new PCOnlineDetector.PCOnlineListener() {
            @Override
            public void onPCOnline(String pcId, String ipAddress) {
                Log.i(TAG, "🟢 PC 上线：" + pcId + " - " + ipAddress);
                
                // 1. 通知 ModeManager PC 在线
                try {
                    ModeManager modeManager = ModeManager.getInstance(PCOnlineDetectionService.this);
                    modeManager.setPcOnline(true);
                } catch (Exception e) {
                    Log.w(TAG, "通知 ModeManager 失败", e);
                }
                
                // 2. 通知 DeviceManager PC 在线（可连接 MQTT Broker）
                try {
                    DeviceManager deviceManager = DeviceManager.getInstance(PCOnlineDetectionService.this);
                    if (!deviceManager.isInitialized) {
                        deviceManager.init();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "通知 DeviceManager 失败", e);
                }
                
                // 3. 发送广播，通知 UI 层
                Intent broadcast = new Intent("com.fusion.companion.PC_STATUS_CHANGED");
                broadcast.putExtra("pc_id", pcId);
                broadcast.putExtra("ip_address", ipAddress);
                broadcast.putExtra("online", true);
                sendBroadcast(broadcast);
                
                Log.i(TAG, "PC 上线事件处理完成：已通知 ModeManager + DeviceManager + 广播");
            }
            
            @Override
            public void onPCOffline(String pcId, String ipAddress) {
                Log.i(TAG, "🔴 PC 离线：" + pcId + " - " + ipAddress);
                
                // 1. 通知 ModeManager PC 离线
                try {
                    ModeManager modeManager = ModeManager.getInstance(PCOnlineDetectionService.this);
                    modeManager.setPcOnline(false);
                } catch (Exception e) {
                    Log.w(TAG, "通知 ModeManager 失败", e);
                }
                
                // 2. 保存 DeviceManager 当前状态
                try {
                    DeviceManager deviceManager = DeviceManager.getInstance(PCOnlineDetectionService.this);
                    // DeviceManager 会在 destroy() 中保存，这里不主动销毁
                } catch (Exception e) {
                    Log.w(TAG, "通知 DeviceManager 失败", e);
                }
                
                // 3. 发送广播，通知 UI 层
                Intent broadcast = new Intent("com.fusion.companion.PC_STATUS_CHANGED");
                broadcast.putExtra("pc_id", pcId);
                broadcast.putExtra("ip_address", ipAddress);
                broadcast.putExtra("online", false);
                sendBroadcast(broadcast);
                
                // 4. 发送通知告知用户 PC 已离线
                try {
                    android.app.NotificationManager nm = 
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        android.app.NotificationChannel channel = new android.app.NotificationChannel(
                            "pc_status", "PC 状态", android.app.NotificationManager.IMPORTANCE_LOW);
                        nm.createNotificationChannel(channel);
                        
                        android.app.Notification notification = 
                            new android.app.Notification.Builder(PCOnlineDetectionService.this, "pc_status")
                            .setContentTitle("PC 已离线")
                            .setContentText(pcId + " (" + ipAddress + ") 已断开连接")
                            .setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setAutoCancel(true)
                            .build();
                        nm.notify(2001, notification);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "发送离线通知失败", e);
                }
                
                Log.i(TAG, "PC 离线事件处理完成：已通知 ModeManager + DeviceManager + 广播 + 通知");
            }
        };
        
        detector.addListener(listener);
        
        Log.i(TAG, "服务初始化完成");
    }
    
    /**
     * 服务启动回调
     * @param intent 启动 Intent
     * @param flags 启动标志
     * @param startId 启动 ID
     * @return 服务行为（START_STICKY 保持运行）
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "PC 在线检测服务启动");
        
        // 加载配置
        loadConfiguration();
        
        // 启动检测
        detector.startDetection();
        
        // 返回 STICKY 保持服务运行（被杀死后自动重启）
        return START_STICKY;
    }
    
    /**
     * 服务销毁回调
     */
    @Override
    public void onDestroy() {
        Log.i(TAG, "PC 在线检测服务销毁");
        
        // 停止检测
        detector.stopDetection();
        
        // 注销监听器
        detector.removeListener(listener);
        
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    /**
     * 加载配置
     */
    private void loadConfiguration() {
        Log.i(TAG, "加载配置...");
        
        // 检查是否已有配置
        if (!config.isValidConfig()) {
            Log.i(TAG, "未找到配置，使用默认配置");
            createDefaultConfiguration();
        }
        
        // 获取所有启用的 PC 配置
        List<PCOnlineDetector.PCConfig> configs = config.getEnabledPCConfigs();
        
        // 添加到检测器
        for (PCOnlineDetector.PCConfig pcConfig : configs) {
            detector.addPCConfig(pcConfig);
            Log.i(TAG, "加载 PC 配置：" + pcConfig.id + " - " + pcConfig.ipAddress);
        }
        
        Log.i(TAG, "配置加载完成，共 " + configs.size() + " 个 PC");
    }
    
    /**
     * 创建默认配置
     */
    private void createDefaultConfiguration() {
        Log.i(TAG, "创建默认配置");
        
        // 添加主 PC
        config.addPCConfig("main-pc", "192.168.1.100", "主 PC", true);
        
        // 添加备用 PC
        config.addPCConfig("backup-pc", "192.168.1.101", "备用 PC", true);
        
        // 设置 MQTT Broker
        config.setMqttHost("192.168.1.100");
        config.setMqttPort(1883);
        
        // 设置检测参数
        config.setPingInterval(10000);      // 10 秒
        config.setPingTimeout(30000);       // 30 秒
        config.setOfflineThreshold(3);      // 连续 3 次失败
        
        Log.i(TAG, "默认配置创建完成");
    }
    
    /**
     * 获取检测器实例（用于外部访问）
     * @return PCOnlineDetector 实例
     */
    public PCOnlineDetector getDetector() {
        return detector;
    }
    
    /**
     * 获取配置管理器实例
     * @return PCOnlineDetectorConfig 实例
     */
    public PCOnlineDetectorConfig getConfigManager() {
        return config;
    }
    
    /**
     * 获取所有 PC 状态（用于查询）
     * @return PC 状态 Map
     */
    public Map<String, PCOnlineDetector.PCStatus> getAllPCStatus() {
        if (detector != null) {
            return detector.getAllPCStatus();
        }
        return null;
    }
    
    /**
     * 检查指定 PC 是否在线
     * @param pcId PC 标识
     * @return true 如果在线
     */
    public boolean isPCOnline(String pcId) {
        return detector != null && detector.isPCOnline(pcId);
    }
    
    /**
     * 获取活跃 PC（最后在线的 PC）
     * @return PC 标识，没有返回 null
     */
    public String getActivePcId() {
        return detector != null ? detector.getActivePcId() : null;
    }
}
