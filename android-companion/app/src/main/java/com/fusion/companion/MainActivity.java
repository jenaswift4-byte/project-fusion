package com.fusion.companion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.fusion.companion.database.SensorDatabase;
import com.fusion.companion.service.FusionBridgeService;
import com.fusion.companion.FusionWebSocketServer;
import com.fusion.companion.service.LLMService;
import com.fusion.companion.service.MQTTBrokerService;
import com.fusion.companion.service.MQTTDataStorageService;
import com.fusion.companion.service.SensorCollector;
import com.fusion.companion.service.ModeManager;

import java.util.List;

/**
 * 主活动
 * 显示系统状态，提供用户交互界面
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private TextView statusText;
    private TextView dbStatusText;
    private TextView sensorStatusText;
    private TextView modeStatusText;
    private SharedPreferences prefs;
    private ModeManager modeManager;
    
    // 数据库和 MQTT 服务
    private SensorDatabase sensorDatabase;
    private MQTTDataStorageService mqttDataStorageService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("fusion", Context.MODE_PRIVATE);

        // 构建简易 UI
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("🔀 Project Fusion Companion");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 32);
        layout.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setPadding(0, 0, 0, 24);
        layout.addView(statusText);
        
        // 数据库状态显示
        dbStatusText = new TextView(this);
        dbStatusText.setTextSize(14);
        dbStatusText.setPadding(0, 0, 0, 24);
        layout.addView(dbStatusText);
        
        // 传感器状态显示
        sensorStatusText = new TextView(this);
        sensorStatusText.setTextSize(14);
        sensorStatusText.setPadding(0, 0, 0, 24);
        layout.addView(sensorStatusText);
        
        // 模式状态显示
        modeStatusText = new TextView(this);
        modeStatusText.setTextSize(14);
        modeStatusText.setPadding(0, 0, 0, 24);
        layout.addView(modeStatusText);

        // 通知权限按钮
        Button notifBtn = new Button(this);
        notifBtn.setText("开启通知访问权限");
        notifBtn.setOnClickListener(v -> {
            if (!isNotificationListenerEnabled()) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } else {
                Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(notifBtn);

        // 启动服务按钮
        Button startBtn = new Button(this);
        startBtn.setText("启动 Fusion Bridge 服务");
        startBtn.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, FusionBridgeService.class);
            startForegroundService(serviceIntent);
            updateStatus();
        });
        layout.addView(startBtn);

        // 停止服务按钮
        Button stopBtn = new Button(this);
        stopBtn.setText("停止服务");
        stopBtn.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, FusionBridgeService.class);
            stopService(serviceIntent);
            updateStatus();
        });
        layout.addView(stopBtn);
        
        // 测试数据库按钮
        Button testDbBtn = new Button(this);
        testDbBtn.setText("📊 测试数据库功能");
        testDbBtn.setOnClickListener(v -> testDatabaseFunction());
        layout.addView(testDbBtn);
        
        // 清理旧数据按钮
        Button cleanupBtn = new Button(this);
        cleanupBtn.setText("🧹 清理 7 天前旧数据");
        cleanupBtn.setOnClickListener(v -> cleanupOldData());
        layout.addView(cleanupBtn);
        
        // 清空数据库按钮
        Button clearBtn = new Button(this);
        clearBtn.setText("⚠️ 清空所有数据");
        clearBtn.setOnClickListener(v -> {
            if (sensorDatabase != null) {
                sensorDatabase.clearAllData();
                Toast.makeText(this, "数据库已清空", Toast.LENGTH_SHORT).show();
                updateDbStatus();
            }
        });
        layout.addView(clearBtn);
        
        // 测试传感器采集按钮
        Button testSensorBtn = new Button(this);
        testSensorBtn.setText("📡 测试传感器采集");
        testSensorBtn.setOnClickListener(v -> testSensorCollection());
        layout.addView(testSensorBtn);
        
        // 模式切换按钮
        Button modeSwitchBtn = new Button(this);
        modeSwitchBtn.setText("🔄 手动切换模式");
        modeSwitchBtn.setOnClickListener(v -> manualSwitchMode());
        layout.addView(modeSwitchBtn);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateDbStatus();
        updateSensorStatus();
        updateModeStatus();
        // 自动启动 Bridge 服务 (如果未运行)
        if (!FusionBridgeService.isRunning()) {
            Intent serviceIntent = new Intent(this, FusionBridgeService.class);
            startForegroundService(serviceIntent);
        }
        
        // 初始化数据库和 MQTT 数据存储服务
        initDatabaseServices();

        // 初始化模式管理器
        initModeManager();

        // 启动 LLM 服务
        startLLMService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 停止模式管理器（可选，根据需求决定是否保持运行）
        // if (modeManager != null) {
        //     modeManager.stop();
        // }
    }

    private void updateStatus() {
        boolean notifEnabled = isNotificationListenerEnabled();
        boolean serviceRunning = FusionBridgeService.isRunning();

        StringBuilder sb = new StringBuilder();
        sb.append("通知访问权限：").append(notifEnabled ? "✅ 已开启" : "❌ 未开启").append("\n");
        sb.append("Bridge 服务：").append(serviceRunning ? "✅ 运行中" : "⏹ 已停止").append("\n");

        int port = prefs.getInt("ws_port", 17532);
        sb.append("WebSocket 端口：").append(port).append("\n");

        // 混合模式状态
        FusionWebSocketServer ws = FusionBridgeService.getWebSocketServer();
        if (ws != null) {
            sb.append("运行模式：").append(ws.isHybridMode() ? "🔀 混合 (Termux)" : "📡 独立").append("\n");
        }
        
        // MQTT Broker 状态
        sb.append("MQTT Broker: ").append(FusionBridgeService.isMQTTBrokerRunning() ? "✅ 运行中" : "⏹ 已停止").append("\n");
        
        // 传感器采集状态
        sb.append("传感器采集：").append(FusionBridgeService.isSensorCollectionRunning() ? "✅ 运行中" : "⏹ 已停止").append("\n");

        statusText.setText(sb.toString());
    }

    private boolean isNotificationListenerEnabled() {
        String pkg = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(pkg);
    }
    
    /**
     * 初始化数据库和 MQTT 数据存储服务
     */
    private void initDatabaseServices() {
        // 初始化数据库
        sensorDatabase = SensorDatabase.getInstance(this);
        
        // 获取 MQTT Broker 服务实例
        Intent mqttIntent = new Intent(this, MQTTBrokerService.class);
        startForegroundService(mqttIntent);
        
        // 等待 Broker 启动后初始化数据存储服务 (全部在后台线程)
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 等待 Broker 启动
                
                if (MQTTBrokerService.isRunning()) {
                    mqttDataStorageService = new MQTTDataStorageService(this, null);
                    mqttDataStorageService.start();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "MQTT 数据存储服务已启动", Toast.LENGTH_SHORT).show();
                        updateDbStatus();
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * 更新数据库状态显示
     */
    private void updateDbStatus() {
        if (sensorDatabase == null) {
            dbStatusText.setText("数据库：未初始化\n");
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("数据库：✅ 已初始化\n");
        sb.append("数据库文件：fusion_sensors.db\n");
        
        // 获取设备数量
        List<SensorDatabase.DeviceState> allDevices = sensorDatabase.getAllDeviceStates();
        List<SensorDatabase.DeviceState> onlineDevices = sensorDatabase.getOnlineDevices();
        sb.append("设备总数：").append(allDevices.size()).append("\n");
        sb.append("在线设备：").append(onlineDevices.size()).append("\n");
        
        // 获取所有设备的传感器数据
        if (!allDevices.isEmpty()) {
            for (SensorDatabase.DeviceState device : allDevices) {
                List<String> sensorTypes = sensorDatabase.getSensorTypes(device.deviceId);
                if (!sensorTypes.isEmpty()) {
                    List<SensorDatabase.SensorData> recentData = sensorDatabase.getRecentSensorData(
                        device.deviceId, sensorTypes.get(0), 1);
                    if (!recentData.isEmpty()) {
                        sb.append("最新数据：").append(device.deviceId)
                          .append(" - ").append(sensorTypes.get(0))
                          .append(" = ").append(recentData.get(0).value)
                          .append(" ").append(recentData.get(0).unit)
                          .append("\n");
                    }
                }
            }
        }
        
        dbStatusText.setText(sb.toString());
    }
    
    /**
     * 更新传感器状态显示
     */
    private void updateSensorStatus() {
        if (sensorStatusText == null) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("传感器采集：").append(FusionBridgeService.isSensorCollectionRunning() ? "✅ 运行中" : "⏹ 已停止").append("\n");
        
        // 获取可用传感器列表
        List<String> availableSensors = SensorCollector.getAvailableSensors(this);
        if (!availableSensors.isEmpty()) {
            sb.append("可用传感器：").append(String.join(", ", availableSensors)).append("\n");
        } else {
            sb.append("可用传感器：无\n");
        }
        
        sensorStatusText.setText(sb.toString());
    }
    
    /**
     * 更新模式状态显示
     */
    private void updateModeStatus() {
        if (modeStatusText == null || modeManager == null) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        ModeManager.Mode currentMode = modeManager.getCurrentMode();
        
        sb.append("当前模式：");
        if (currentMode == ModeManager.Mode.ONLINE) {
            sb.append("🌐 在线模式 (PC Qwen3.5-7B)\n");
        } else {
            sb.append("📱 离线模式 (本地 Qwen2.5-3B)\n");
        }
        
        sb.append("PC 状态：").append(modeManager.isPCOnline() ? "✅ 在线" : "❌ 离线").append("\n");
        sb.append("切换状态：").append(modeManager.isSwitching() ? "🔄 切换中" : "⏸ 稳定").append("\n");
        
        // 本地 AI 状态
        ModeManager.LocalAIManager aiManager = modeManager.getLocalAIManager();
        if (aiManager != null) {
            sb.append("本地 AI: ").append(aiManager.isAILoaded() ? "✅ 已加载" : "⏹ 未加载").append("\n");
        }
        
        // 媒体库状态
        ModeManager.MediaLibraryManager mediaManager = modeManager.getMediaLibraryManager();
        if (mediaManager != null) {
            sb.append("音乐库：").append(mediaManager.isUsingLocalLibrary() ? "📱 本地" : "💻 PC").append("\n");
        }
        
        modeStatusText.setText(sb.toString());
    }
    
    /**
     * 初始化模式管理器
     */
    private void initModeManager() {
        modeManager = ModeManager.getInstance(this);
        
        // 添加模式切换监听器
        modeManager.addOnModeSwitchListener(new ModeManager.OnModeSwitchListener() {
            @Override
            public void onModeSwitch(ModeManager.Mode newMode, ModeManager.Mode previousMode, 
                                   ModeManager.SwitchReason reason, long timestamp) {
                runOnUiThread(() -> {
                    String modeStr = newMode == ModeManager.Mode.ONLINE ? "在线模式" : "离线模式";
                    String reasonStr = getSwitchReasonText(reason);
                    Toast.makeText(MainActivity.this, 
                        "模式已切换：" + modeStr + " (" + reasonStr + ")", 
                        Toast.LENGTH_SHORT).show();
                    updateModeStatus();
                });
            }
        });
        
        // 启动模式管理器
        modeManager.start();
        
        Log.d(TAG, "模式管理器已初始化");
    }

    /**
     * 启动 LLM 服务
     */
    private void startLLMService() {
        Intent llmIntent = new Intent(this, LLMService.class);
        startForegroundService(llmIntent);
        Log.d(TAG, "LLM 服务已启动");
    }
    
    /**
     * 获取切换原因的文本描述
     */
    private String getSwitchReasonText(ModeManager.SwitchReason reason) {
        switch (reason) {
            case PC_OFFLINE:
                return "PC 离线";
            case PC_ONLINE:
                return "PC 上线";
            case MANUAL_SWITCH:
                return "手动切换";
            case NETWORK_LOST:
                return "网络丢失";
            case NETWORK_RESTORED:
                return "网络恢复";
            default:
                return "未知";
        }
    }
    
    /**
     * 手动切换模式
     */
    private void manualSwitchMode() {
        if (modeManager == null) {
            Toast.makeText(this, "模式管理器未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (modeManager.isSwitching()) {
            Toast.makeText(this, "正在切换中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        
        ModeManager.Mode currentMode = modeManager.getCurrentMode();
        ModeManager.Mode targetMode = currentMode == ModeManager.Mode.ONLINE 
            ? ModeManager.Mode.OFFLINE 
            : ModeManager.Mode.ONLINE;
        
        String modeName = targetMode == ModeManager.Mode.ONLINE ? "在线模式" : "离线模式";
        Toast.makeText(this, "正在切换到" + modeName, Toast.LENGTH_SHORT).show();
        
        modeManager.manualSwitch(targetMode);
    }
    
    /**
     * 测试传感器采集功能
     */
    private void testSensorCollection() {
        if (!FusionBridgeService.isSensorCollectionRunning()) {
            Toast.makeText(this, "传感器采集未运行，请先启动 Fusion Bridge 服务", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Toast.makeText(this, "✅ 传感器采集正在运行，请查看日志输出", Toast.LENGTH_SHORT).show();
        
        // 显示可用传感器
        List<String> availableSensors = SensorCollector.getAvailableSensors(this);
        StringBuilder sb = new StringBuilder();
        sb.append("可用传感器类型:\n");
        for (String sensor : availableSensors) {
            sb.append("  - ").append(sensor).append("\n");
        }
        
        Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
    }
    
    /**
     * 测试数据库功能
     */
    private void testDatabaseFunction() {
        if (sensorDatabase == null) {
            Toast.makeText(this, "数据库未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new Thread(() -> {
            try {
                // 测试插入传感器数据
                long rowId1 = sensorDatabase.insertSensorData("bedroom-phone-c", "temperature", 25.5, "°C");
                long rowId2 = sensorDatabase.insertSensorData("bedroom-phone-c", "humidity", 60.0, "%");
                long rowId3 = sensorDatabase.insertSensorData("living-room-sensor", "light", 500.0, "lux");
                
                // 测试更新设备状态
                sensorDatabase.updateDeviceState("bedroom-phone-c", true, 85, "normal");
                sensorDatabase.updateDeviceState("living-room-sensor", true, null, "eco");
                
                // 测试查询数据
                List<SensorDatabase.SensorData> recentData = sensorDatabase.getRecentSensorData(
                    "bedroom-phone-c", "temperature", 5);
                
                SensorDatabase.DeviceState deviceState = sensorDatabase.getDeviceState("bedroom-phone-c");
                
                // 显示测试结果
                runOnUiThread(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("✅ 测试成功\n\n");
                    sb.append("插入数据 ID: ").append(rowId1).append(", ").append(rowId2).append(", ").append(rowId3).append("\n");
                    sb.append("查询结果数量：").append(recentData.size()).append("\n");
                    if (!recentData.isEmpty()) {
                        sb.append("最新温度：").append(recentData.get(0).value).append(" °C\n");
                    }
                    if (deviceState != null) {
                        sb.append("设备状态：").append(deviceState.isOnline ? "在线" : "离线");
                        sb.append(", 电量：").append(deviceState.batteryLevel).append("%\n");
                        sb.append("模式：").append(deviceState.mode).append("\n");
                    }
                    
                    Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
                    updateDbStatus();
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "测试失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    /**
     * 清理 7 天前的旧数据
     */
    private void cleanupOldData() {
        if (sensorDatabase == null) {
            Toast.makeText(this, "数据库未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new Thread(() -> {
            sensorDatabase.cleanupOldData();
            int deletedCount = 0;  // cleanupOldData() 无返回值
            runOnUiThread(() -> {
                Toast.makeText(this, "已清理 " + deletedCount + " 条旧数据", Toast.LENGTH_SHORT).show();
                updateDbStatus();
            });
        }).start();
    }
}
