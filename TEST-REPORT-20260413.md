# Project Fusion 实际场景测试报告

**测试时间**: 2026-04-13 12:53 ~ 12:58  
**测试设备**: Xiaomi MI 8 (Android 10, SDK 29)  
**测试环境**: 自习室 (手机勿扰模式 zen_mode=1)  
**连接方式**: USB ADB  
**PC**: LAPTOP-7MKK6190 (192.168.42.244)  
**手机 IP**: 192.168.40.84  

---

## 测试结果总览

| # | 场景 | 状态 | 说明 |
|---|------|------|------|
| 1 | 设备连接 + ADB 通信 | ✅ 通过 | ADB USB 连接正常，设备信息读取正常 |
| 2 | 电池状态查询 | ✅ 通过 | 100% USB充电, 4.304V, 36.0°C, 锂聚合物 |
| 3 | 剪贴板双向同步 | ✅ 通过 | Clipper broadcast 写入/读取正常 |
| 4 | 截图功能 | ✅ 通过 | screencap 219KB PNG, pull 18.9MB/s |
| 5 | 短信读取 | ✅ 通过 | content://sms/inbox 正常读取验证码等 |
| 6 | 网络连通性 | ✅ 通过 | PC↔手机 ping 2.15ms, 0%丢包 |
| 7 | 传感器硬件检测 | ✅ 通过 | 39个硬件传感器, SensorCollector活跃注册7个 |
| 8 | Fusion App运行状态 | ✅ 通过 | v2.1 (targetSdk=30), 3个服务运行中 |
| 9 | MQTT端到端传感器数据 | ⚠️ 部分 | 手机本地Broker正常, 但PC远程Broker未连通 |

---

## 详细结果

### ✅ 场景1: 设备连接 + ADB 基础通信
- ADB devices: `7254adb4 device` (MI 8, USB)
- 设备型号: Xiaomi MI 8
- Android版本: 10 (SDK 29)
- 屏幕分辨率: 1080x2248
- WiFi IP: 192.168.40.84

### ✅ 场景2: 电池状态查询
- 电量: 100%
- 充电状态: USB充电 (status=2)
- 电压: 4.304V
- 温度: 36.0°C (360/10)
- 健康: Good (health=2)
- 技术: Li-poly (锂聚合物)
- 充电电流上限: 500mA
- 电量计数: 3000414 (约3.0Ah)

### ✅ 场景3: 剪贴板双向同步 (ADB通道)
- PC→手机: `am broadcast -a clipper.set` 成功 (result=0)
- 手机→PC: `am broadcast -a clipper.get` 成功
- **注**: 使用 Clipper App 作为中间件, 需要 com.caoccao.android.clipper 已安装

### ✅ 场景4: 截图功能
- screencap 路径: `/sdcard/Download/fusion_test_screenshot.png`
- 文件大小: 219,039 bytes (219KB)
- Pull速度: 18.9 MB/s (0.011s)
- 格式: PNG

### ✅ 场景5: 短信读取
- 读取方式: `content query --uri content://sms/inbox`
- 成功读取最近短信 (验证码、运营商通知等)
- 示例数据:
  - 【深度求索】验证码: 353760
  - 【硅基流动】验证码: 142108
  - 【学习通】验证码: 716892
  - 【阿里云】ECS到期提醒

### ✅ 场景6: 网络连通性
- PC→手机 ping: 2.15~2.57ms
- 丢包率: 0%
- PC和手机在同一局域网 (不同子网: .42.x vs .40.x, 通过路由器互通)

### ✅ 场景7: 传感器硬件检测
MI 8 共 39 个硬件传感器, 主要包括:
- **加速度计** (ICM20690, android.sensor.accelerometer)
- **陀螺仪** (ICM20690, android.sensor.gyroscope)
- **光线传感器** (tmd2725, android.sensor.light)
- **接近传感器** (tmd2725, android.sensor.proximity)
- **磁力计** (ak0991x, android.sensor.magnetic_field)
- **气压计** (bosch_bmp285, android.sensor.pressure)
- **重力传感器** (qualcomm, android.sensor.gravity)
- **线性加速度** (qualcomm, android.sensor.linear_acceleration)
- **旋转向量** (xiaomi, android.sensor.rotation_vector)
- **计步器** (qualcomm, android.sensor.step_counter/step_detector)
- **设备方向** (xiaomi, android.sensor.device_orientation)
- 小米专有: 非UI传感器、抬起检测、AOD、心跳检测等

**SensorCollector 活跃状态** (PID 18481):
- 采样周期: 200ms (5Hz)
- 注册传感器: proximity, step_counter, pressure, device_orientation, light (wakeup + non-wakeup), gyroscope, accelerometer
- 数据存储: 通过 MQTT PUBLISH 到本地 Broker (127.0.0.1)

### ✅ 场景8: Fusion App 运行状态
- **版本**: v2.1 (versionCode=2, targetSdk=30, minSdk=26)
- **安装时间**: 2026-04-13 12:41:11
- **debuggable**: ✅ 是 (可通过 run-as 访问)
- **运行中服务** (3个):
  1. `MQTTBrokerService` - 手机本地 MQTT Broker (Stub)
  2. `FusionNotificationListener` - 通知监听 (bind FGS)
  3. `FusionBridgeService` - 主桥接服务
- **勿扰模式**: zen_mode=1 (已静音)

### ⚠️ 场景9: MQTT 端到端传感器数据流

**预期**: 手机 SensorCollector → MQTT publish → PC MQTT Broker → Dashboard 显示

**实际结果**:
- ✅ SensorCollector 正在采集 7 个传感器 (200ms 间隔)
- ✅ 传感器数据通过 MQTT PUBLISH 到手机本地 Broker (127.0.0.1:1883)
- ✅ 手机本地 MQTTBrokerService 正确响应 CONNACK/SUBACK/PINGREQ (v3.3.1 修复)
- ❌ **传感器数据未转发到 PC Broker (192.168.42.244:1883)**

**根本原因**:
1. 当前 APK (v2.1) 的 SensorCollector 只连接到手机本地 Broker
2. 代码中虽有 PC Broker URL 读取逻辑 (mqtt_client_prefs → broker_host), 但:
   - App 启动时 SharedPreferences 中 broker_host 为空
   - 即使通过 run-as 写入了 `192.168.42.244`, App 未重新加载配置 (Android SharedPreferences 缓存)
   - 需要 App 真正重启才能读取新配置
3. MIUI 的 deviceidle whitelist 阻止了 force-stop (kill: Operation not permitted)
4. **缺少 MQTTClientService** 的 PC→手机数据转发通道

**解决方案**:
- 需要推送最新代码到 GitHub → CI 编译新 APK → 安装新版本
- 新版本应包含: MQTTClientService 连接 PC Broker + Broker Discovery 自动机制

---

## 总结

**9 项测试中 8 项通过**，ADB 基础通信层全部正常工作。唯一未通过的是 MQTT 端到端传感器数据流，原因是**手机端 APK 版本过旧 (v2.1)，缺少 PC Broker 远程连接和数据转发功能**。

**下一步行动**:
1. 推送代码到 GitHub 触发 CI 编译新 APK
2. 安装新 APK 后重新测试 MQTT 端到端
3. 启动 PC Bridge Daemon 验证 Dashboard 实时显示传感器数据
