/**
 * PC 在线检测功能 - 使用示例
 * 
 * 本文件展示如何在项目中使用 PCOnlineDetector 功能
 * 包括：基本使用、配置管理、监听器、集成到现有服务等
 * 
 * @author Fusion
 * @version 1.0
 */
package com.fusion.companion.service;

import android.content.Context;
import android.util.Log;

import java.util.Map;

/**
 * PC 在线检测使用示例
 */
public class PCOnlineDetectorExamples {
    
    private static final String TAG = "PCDetectorExample";
    
    // ==================== 示例 1: 基本使用 ====================
    
    /**
     * 示例 1: 基本使用 - 快速启动检测
     * 
     * 场景：在 Activity 或 Service 中快速启动 PC 检测
     */
    public void example1_BasicUsage(Context context) {
        // 创建检测器
        PCOnlineDetector detector = new PCOnlineDetector(context);
        
        // 添加 PC 配置（支持多个 PC）
        detector.addPCConfig("main-pc", "192.168.1.100", "主 PC");
        detector.addPCConfig("backup-pc", "192.168.1.101", "备用 PC");
        
        // 注册状态变化监听器
        detector.addListener(new PCOnlineDetector.PCOnlineListener() {
            @Override
            public void onPCOnline(String pcId, String ipAddress) {
                Log.i(TAG, "✅ PC 上线：" + pcId + " - " + ipAddress);
                // 处理 PC 上线逻辑
            }
            
            @Override
            public void onPCOffline(String pcId, String ipAddress) {
                Log.i(TAG, "❌ PC 离线：" + pcId + " - " + ipAddress);
                // 处理 PC 离线逻辑
            }
        });
        
        // 启动检测
        detector.startDetection();
        
        // 查询 PC 状态
        boolean isOnline = detector.isPCOnline("main-pc");
        long lastSeen = detector.getLastSeen("main-pc");
        
        Log.i(TAG, "主 PC 在线：" + isOnline + ", 最后在线时间：" + lastSeen);
    }
    
    // ==================== 示例 2: 使用配置管理器 ====================
    
    /**
     * 示例 2: 使用配置管理器 - 持久化配置
     * 
     * 场景：需要保存/加载 PC 配置，支持动态修改
     */
    public void example2_ConfigManager(Context context) {
        // 创建配置管理器
        PCOnlineDetectorConfig config = new PCOnlineDetectorConfig(context);
        
        // 添加 PC 配置（自动持久化）
        config.addPCConfig("main-pc", "192.168.1.100", "主 PC", true);
        config.addPCConfig("backup-pc", "192.168.1.101", "备用 PC", true);
        
        // 修改 MQTT Broker 配置
        config.setMqttHost("192.168.1.100");
        config.setMqttPort(1883);
        
        // 修改检测参数
        config.setPingInterval(10000);      // 10 秒
        config.setPingTimeout(30000);       // 30 秒
        config.setOfflineThreshold(3);      // 连续 3 次失败判定离线
        
        // 获取所有配置
        var pcConfigs = config.getEnabledPCConfigs();
        Log.i(TAG, "已配置 " + pcConfigs.size() + " 个 PC");
        
        // 导出配置为 JSON
        String jsonConfig = config.exportConfigToJson();
        Log.i(TAG, "导出配置：" + jsonConfig);
        
        // 从 JSON 导入配置
        config.importConfigFromJson(jsonConfig);
        
        // 注册配置变更监听器
        config.addConfigChangeListener((key, newValue) -> {
            Log.i(TAG, "配置变更：" + key + " = " + newValue);
        });
    }
    
    // ==================== 示例 3: 高级查询 ====================
    
    /**
     * 示例 3: 高级查询 - 获取详细状态信息
     * 
     * 场景：需要获取所有 PC 的详细状态信息
     */
    public void example3_AdvancedQuery(PCOnlineDetector detector) {
        // 获取所有 PC 状态
        Map<String, PCOnlineDetector.PCStatus> allStatus = detector.getAllPCStatus();
        
        for (Map.Entry<String, PCOnlineDetector.PCStatus> entry : allStatus.entrySet()) {
            PCOnlineDetector.PCStatus status = entry.getValue();
            
            Log.i(TAG, "PC ID: " + status.pcId);
            Log.i(TAG, "  IP 地址：" + status.ipAddress);
            Log.i(TAG, "  在线状态：" + (status.isOnline ? "在线" : "离线"));
            Log.i(TAG, "  检测模式：" + status.mode);
            Log.i(TAG, "  最后在线：" + status.lastSeen);
            Log.i(TAG, "  连续失败：" + status.consecutiveFailures);
        }
        
        // 获取活跃 PC（最后在线的 PC）
        String activePcId = detector.getActivePcId();
        if (activePcId != null) {
            Log.i(TAG, "活跃 PC: " + activePcId);
        } else {
            Log.i(TAG, "没有 PC 在线");
        }
        
        // 获取单个 PC 状态
        PCOnlineDetector.PCStatus mainPcStatus = detector.getPCStatus("main-pc");
        if (mainPcStatus != null) {
            Log.i(TAG, "主 PC 检测模式：" + mainPcStatus.mode);
        }
    }
    
    // ==================== 示例 4: 集成到 Service ====================
    
    /**
     * 示例 4: 集成到 Service - 作为后台服务运行
     * 
     * 场景：需要长期后台运行 PC 检测
     * 
     * 使用方式：
     * 1. 在 AndroidManifest.xml 注册服务
     * 2. 启动服务：startService(new Intent(context, PCOnlineDetectionService.class));
     * 3. 服务会自动加载配置并启动检测
     */
    public void example4_ServiceIntegration() {
        // 代码见 PCOnlineDetectionService.java
        Log.i(TAG, "Service 集成示例见 PCOnlineDetectionService.java");
    }
    
    // ==================== 示例 5: 动态管理 PC 配置 ====================
    
    /**
     * 示例 5: 动态管理 PC 配置 - 添加/删除/修改
     * 
     * 场景：运行时动态管理 PC 配置
     */
    public void example5_DynamicConfigManagement(PCOnlineDetector detector, 
                                                  PCOnlineDetectorConfig config) {
        // 添加新 PC
        config.addPCConfig("office-pc", "192.168.1.102", "办公室 PC", true);
        detector.addPCConfig("office-pc", "192.168.1.102", "办公室 PC");
        
        // 禁用 PC（不删除配置）
        config.setPCEnabled("backup-pc", false);
        
        // 修改 PC IP 地址
        PCOnlineDetector.PCConfig pcConfig = config.getPCConfig("main-pc");
        if (pcConfig != null) {
            pcConfig.ipAddress = "192.168.1.200";
            config.updatePCConfig(pcConfig);
        }
        
        // 删除 PC
        config.removePCConfig("office-pc");
        detector.removePCConfig("office-pc");
        
        // 清空所有配置
        // config.clearAllConfigs();
    }
    
    // ==================== 示例 6: 手动触发检测 ====================
    
    /**
     * 示例 6: 手动触发检测 - 立即检测 PC 状态
     * 
     * 场景：用户主动刷新或特定事件触发检测
     */
    public void example6_ManualTrigger(PCOnlineDetector detector) {
        // 手动触发一次 Ping 检测
        detector.triggerPingDetection("main-pc");
        
        Log.i(TAG, "已触发手动检测");
        
        // 等待检测完成（实际场景应该用回调）
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 查询检测结果
        boolean isOnline = detector.isPCOnline("main-pc");
        Log.i(TAG, "手动检测结果：" + (isOnline ? "在线" : "离线"));
    }
    
    // ==================== 示例 7: MQTT 状态发布 ====================
    
    /**
     * 示例 7: MQTT 状态发布 - 自动发布 PC 状态
     * 
     * 场景：PC 状态变化时自动发布到 MQTT，其他设备可以订阅
     * 
     * 发布主题：fusion/pc/status
     * 消息格式：
     * {
     *   "pc_online": true,
     *   "pc_ip": "192.168.1.100",
     *   "last_seen": 1234567890,
     *   "mode": "ping",
     *   "pc_id": "main-pc",
     *   "timestamp": 1234567890
     * }
     */
    public void example7_MQTTStatusPublish() {
        Log.i(TAG, "MQTT 状态发布是自动的，无需手动操作");
        Log.i(TAG, "状态变化时会自动发布到 fusion/pc/status 主题");
    }
    
    // ==================== 示例 8: PC 端心跳发送（Python） ====================
    
    /**
     * 示例 8: PC 端心跳发送 - Python 脚本
     * 
     * 场景：PC 启动时自动发送心跳到 MQTT
     * 
     * 使用方法：
     * 1. 安装依赖：pip install paho-mqtt
     * 2. 运行脚本：python pc-heartbeat-sender.py
     * 3. 配置开机自启（可选）
     * 
     * 代码见：pc-heartbeat-sender.py
     */
    public void example8_PcHeartbeatSender() {
        Log.i(TAG, "PC 端心跳发送示例见 pc-heartbeat-sender.py");
        Log.i(TAG, "启动命令：python pc-heartbeat-sender.py");
    }
    
    // ==================== 示例 9: 完整集成示例 ====================
    
    /**
     * 示例 9: 完整集成示例 - 在 MainActivity 中使用
     * 
     * 场景：在 MainActivity 中完整集成 PC 检测功能
     */
    public void example9_CompleteIntegration(Context context) {
        // 创建配置管理器
        PCOnlineDetectorConfig config = new PCOnlineDetectorConfig(context);
        
        // 检查是否已有配置
        if (!config.isValidConfig()) {
            Log.i(TAG, "首次使用，创建默认配置");
            
            // 创建默认配置
            config.addPCConfig("main-pc", "192.168.1.100", "主 PC", true);
            config.addPCConfig("backup-pc", "192.168.1.101", "备用 PC", false);
            config.setMqttHost("192.168.1.100");
            config.setMqttPort(1883);
        }
        
        // 创建检测器
        PCOnlineDetector detector = new PCOnlineDetector(context);
        
        // 加载配置
        var pcConfigs = config.getEnabledPCConfigs();
        for (PCOnlineDetector.PCConfig pcConfig : pcConfigs) {
            detector.addPCConfig(pcConfig);
        }
        
        // 注册监听器
        detector.addListener(new PCOnlineDetector.PCOnlineListener() {
            @Override
            public void onPCOnline(String pcId, String ipAddress) {
                Log.i(TAG, "🟢 PC 上线：" + pcId);
                // 更新 UI、发送广播等
            }
            
            @Override
            public void onPCOffline(String pcId, String ipAddress) {
                Log.i(TAG, "🔴 PC 离线：" + pcId);
                // 发送通知、切换备用 PC 等
            }
        });
        
        // 启动检测
        detector.startDetection();
        
        Log.i(TAG, "PC 在线检测已启动");
    }
    
    // ==================== 示例 10: 调试和日志 ====================
    
    /**
     * 示例 10: 调试和日志 - 查看详细状态
     * 
     * 场景：调试时查看详细状态信息
     */
    public void example10_DebugInfo(PCOnlineDetector detector, 
                                     PCOnlineDetectorConfig config) {
        Log.i(TAG, "========== PC 在线检测调试信息 ==========");
        
        // 检测器状态
        Log.i(TAG, "检测器运行状态：" + (detector.isRunning() ? "运行中" : "已停止"));
        Log.i(TAG, "MQTT 连接状态：" + (detector.isMQTTConnected() ? "已连接" : "未连接"));
        
        // PC 配置
        var pcConfigs = config.getPCConfigs();
        Log.i(TAG, "PC 配置数量：" + pcConfigs.size());
        for (PCOnlineDetector.PCConfig pcConfig : pcConfigs) {
            Log.i(TAG, "  - " + pcConfig.id + ": " + pcConfig.ipAddress + 
                         " (启用：" + pcConfig.enabled + ")");
        }
        
        // PC 状态
        var pcStatusMap = detector.getAllPCStatus();
        for (Map.Entry<String, PCOnlineDetector.PCStatus> entry : pcStatusMap.entrySet()) {
            PCOnlineDetector.PCStatus status = entry.getValue();
            Log.i(TAG, "PC 状态 - " + status.pcId + ":");
            Log.i(TAG, "  在线：" + (status.isOnline ? "是" : "否"));
            Log.i(TAG, "  模式：" + status.mode);
            Log.i(TAG, "  最后在线：" + new java.util.Date(status.lastSeen));
            Log.i(TAG, "  连续失败：" + status.consecutiveFailures);
        }
        
        // 配置参数
        Log.i(TAG, "MQTT Broker: " + config.getMqttHost() + ":" + config.getMqttPort());
        Log.i(TAG, "Ping 间隔：" + config.getPingInterval() + "ms");
        Log.i(TAG, "Ping 超时：" + config.getPingTimeout() + "ms");
        Log.i(TAG, "离线阈值：" + config.getOfflineThreshold());
        
        Log.i(TAG, "==========================================");
    }
}
