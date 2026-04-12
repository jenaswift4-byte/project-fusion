# MQTT 客户端模块实现摘要

## 实现概述

已成功为所有手机设备实现完整的 MQTT 客户端模块，包括以下核心功能：

### 1. 核心服务类

**文件位置**: `c:\Users\wang\Desktop\万物互联\android-companion\app\src\main\java\com\fusion\companion\service\MQTTClientService.java`

## 功能特性

### 1. MQTT 连接管理

- **自动连接**: 服务启动时自动连接到卧室手机 C 的 MQTT Broker（默认 1883 端口）
- **自动重连**: 使用指数退避算法（1s → 2s → 4s → 8s → 16s → 32s → 60s）
- **连接状态监控**: 实时监控连接状态并更新通知
- **低功耗设计**: 使用 PARTIAL_WAKE_LOCK，防止 CPU 休眠

### 2. 消息发布和订阅

#### 自动订阅的主题:
- `devices/{deviceId}` - 设备控制命令
- `fusion/broadcast` - 广播消息
- `fusion/mode` - 模式切换通知

#### 定期发布的主题:
- `sensors/{deviceId}/{sensorType}` - 传感器数据（每 5 秒）
- `devices/{deviceId}/heartbeat` - 设备心跳（每 30 秒）

### 3. 传感器数据集成

- **自动采集**: 自动监听设备传感器（温度、湿度、光照、气压等）
- **数据缓存**: 使用 ConcurrentHashMap 缓存最新传感器数据
- **JSON 格式**: 传感器数据自动序列化为 JSON 格式发布

### 4. 心跳机制

心跳消息格式：
```json
{
  "device_id": "bedroom-phone-c",
  "is_online": true,
  "battery_level": 85,
  "timestamp": 1234567890
}
```

## 使用示例

### 1. 启动 MQTT 客户端服务

```java
// 在 Activity 或 Service 中
Intent mqttClientIntent = new Intent(context, MQTTClientService.class);
context.startForegroundService(mqttClientIntent);
```

### 2. 检查服务状态

```java
// 检查服务是否运行
boolean isRunning = MQTTClientService.isRunning();

// 检查是否已连接
// 需要通过绑定服务获取实例后调用 isConnected()
```

### 3. 停止 MQTT 客户端服务

```java
Intent mqttClientIntent = new Intent(context, MQTTClientService.class);
context.stopService(mqttClientIntent);
```

### 4. 更新 Broker 配置

```java
// 获取服务实例（通过绑定服务）
MQTTClientService service = ...; // 通过 ServiceConnection 获取
service.updateBrokerConfig("192.168.1.100", 1883);
```

### 5. 手动发布消息

```java
// 发布文本消息
service.publishTextMessage("test/topic", "Hello MQTT", 1);

// 发布 JSON 消息
Map<String, Object> data = new HashMap<>();
data.put("key", "value");
service.publishJsonMessage("data/topic", data, 1);

// 发布原始字节消息
service.publishMessage("raw/topic", bytes, 0);
```

### 6. 订阅主题

```java
// 订阅主题
service.subscribeTopic("custom/topic", 1);

// 取消订阅
service.unsubscribeTopic("custom/topic");
```

### 7. 设置消息监听器

```java
service.setMessageListener((topic, payload) -> {
    String message = new String(payload);
    Log.d("MQTT", "收到消息：" + topic + " - " + message);
    
    // 处理接收到的消息
    if (topic.startsWith("devices/")) {
        // 处理设备控制命令
    } else if (topic.equals("fusion/broadcast")) {
        // 处理广播消息
    }
});
```

### 8. 获取传感器数据

```java
// 获取特定传感器数据
Float temperature = service.getLatestSensorData("temperature");
Float humidity = service.getLatestSensorData("humidity");

// 获取所有传感器数据
Map<String, Float> allData = service.getAllSensorData();
```

## 完整集成示例

### MainActivity 集成

```java
public class MainActivity extends Activity {
    
    private MQTTClientService mqttClientService;
    private ServiceConnection serviceConnection;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 初始化 ServiceConnection
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // 获取服务实例
                MQTTClientService.LocalBinder binder = 
                    (MQTTClientService.LocalBinder) service;
                mqttClientService = binder.getService();
                
                // 设置消息监听器
                mqttClientService.setMessageListener((topic, payload) -> {
                    handleIncomingMessage(topic, payload);
                });
                
                Log.d("MQTT", "服务已绑定");
            }
            
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mqttClientService = null;
                Log.d("MQTT", "服务已断开");
            }
        };
        
        // 绑定服务
        Intent intent = new Intent(this, MQTTClientService.class);
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        
        // 启动服务
        startForegroundService(intent);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 解绑服务
        if (serviceConnection != null) {
            unbindService(serviceConnection);
        }
    }
    
    private void handleIncomingMessage(String topic, byte[] payload) {
        String message = new String(payload);
        
        switch (topic) {
            case "fusion/broadcast":
                // 处理广播消息
                broadcastMessage(message);
                break;
                
            case "fusion/mode":
                // 处理模式切换
                switchMode(message);
                break;
                
            default:
                if (topic.startsWith("devices/" + mqttClientService.getDeviceId())) {
                    // 处理本设备控制命令
                    handleDeviceCommand(message);
                }
        }
    }
}
```

## 配置说明

### SharedPreferences 配置

配置文件存储在 `mqtt_client_prefs` 中：

- `device_id`: 设备唯一标识符（自动生成）
- `broker_host`: Broker 主机地址（默认：192.168.1.100）
- `broker_port`: Broker 端口（默认：1883）

### 设备 ID 生成规则

设备 ID 格式：`device-{设备型号后 10 位}-{4 位随机数}`

示例：`device-sm-g998b-1234`

## 通知管理

### 通知渠道

- **渠道 ID**: `mqtt_client_channel`
- **渠道名称**: MQTT 客户端服务
- **重要性**: LOW（低优先级，不干扰用户）

### 通知内容

- 服务运行状态（运行中/连接中）
- 设备 ID
- Broker 地址和端口
- 连接状态

## 低功耗设计

### WakeLock 管理

- 使用 `PARTIAL_WAKE_LOCK`（仅保持 CPU 运行，屏幕可关闭）
- 超时自动释放（10 分钟）
- 服务停止时自动释放

### 传感器监听

- 使用 `SENSOR_DELAY_NORMAL`（普通频率，低功耗）
- 只在服务运行时监听
- 服务停止时自动注销监听

## 错误处理

### 连接失败处理

1. 记录错误日志
2. 调度重连（指数退避）
3. 更新通知状态
4. 停止定时任务

### 降级策略

- MQTT 不可用时，传感器数据仍会缓存
- 连接恢复后自动发布最新数据
- 支持离线运行（数据本地缓存）

## 测试方法

### 1. 启动服务测试

```java
// 在 MainActivity 中点击"启动 MQTT 客户端"按钮
// 查看通知栏是否显示"MQTT 客户端运行中"
// 查看 Logcat 日志，搜索"MQTTClient"标签
```

### 2. 连接测试

```bash
# 在 Termux 或其他 MQTT 客户端工具中
# 订阅设备主题
mosquitto_sub -h 192.168.1.100 -t "devices/#" -v

# 订阅传感器主题
mosquitto_sub -h 192.168.1.100 -t "sensors/#" -v

# 订阅心跳主题
mosquitto_sub -h 192.168.1.100 -t "devices/+/heartbeat" -v
```

### 3. 发布测试

```bash
# 发布控制命令到设备
mosquitto_pub -h 192.168.1.100 -t "devices/device-sm-g998b-1234" \
  -m '{"command":"reboot"}'
```

### 4. 日志查看

```bash
# 查看 MQTT 客户端日志
adb logcat | grep MQTTClient
```

## 依赖配置

### build.gradle 依赖

```gradle
dependencies {
    // MQTT 客户端库
    implementation 'org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5'
    
    // JSON 序列化
    implementation 'com.google.code.gson:gson:2.10.1'
}
```

## 权限要求

### AndroidManifest.xml

```xml
<!-- 网络访问权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 前台服务权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<!-- WakeLock 权限 -->
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- 传感器权限（部分设备需要） -->
<uses-permission android:name="android.permission.BODY_SENSORS" />
```

## 常见问题

### Q1: 服务启动后立即停止

**解决**: 确保调用 `startForegroundService()` 而不是 `startService()`

### Q2: 无法连接到 Broker

**解决**: 
1. 检查 Broker 是否运行（查看 MQTTBrokerService 日志）
2. 检查防火墙设置（1883 端口是否开放）
3. 检查 IP 地址是否正确

### Q3: 传感器数据为空

**解决**: 
1. 检查设备是否有相应传感器
2. 查看 Logcat 日志中的传感器注册信息
3. 等待 5-10 秒让传感器数据采集

## 性能优化建议

1. **调整发布频率**: 根据实际需求调整传感器发布间隔
2. **使用 QoS 0**: 对于非关键数据使用 QoS 0（至多一次）
3. **批量发布**: 多个传感器数据可以合并发布
4. **减少重连频率**: 调整最大重连延迟

## 未来扩展

1. **消息持久化**: 支持离线消息存储和重放
2. **TLS 加密**: 支持 SSL/TLS 安全连接
3. **认证机制**: 支持用户名密码认证
4. **消息压缩**: 大数据量时自动压缩

## 总结

MQTT 客户端模块已完全实现，具备以下特点：

✅ **完整的 MQTT 功能**: 连接、发布、订阅、重连  
✅ **自动传感器采集**: 温度、湿度、光照等  
✅ **定期心跳发布**: 30 秒间隔，包含电量信息  
✅ **低功耗设计**: WakeLock、传感器频率优化  
✅ **自动重连**: 指数退避算法  
✅ **详细日志**: 便于调试和监控  
✅ **易于集成**: 提供完整的使用示例  

可以直接在应用中使用，无需额外配置。
