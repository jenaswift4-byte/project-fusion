# Project Fusion v2.0 验收检查清单

## Phase 1: 核心串流基础

### 1.1 Sunshine Manager 基础集成
- [ ] `bridge/modules/sunshine_manager.py` 文件已创建
- [ ] `SunshineManager` 类实现完整
- [ ] `start()` 方法能正确检测/启动 Sunshine
- [ ] `_configure_apps()` 能生成正确的 `apps.json`
- [ ] `launch_app(app_id)` 能启动指定应用
- [ ] `list_apps()` 返回正确应用列表
- [ ] `SunshineManager` 能正确处理未安装情况
- [ ] 默认应用列表包含至少 5 个常用应用
- [ ] 单元测试覆盖核心功能

### 1.2 触摸映射引擎基础
- [ ] `bridge/modules/touch_mapper.py` 文件已创建
- [ ] `TouchMapper` 类实现完整
- [ ] `map_coordinates()` 正确映射触摸坐标
- [ ] 点击手势识别正确
- [ ] 双击手势识别正确
- [ ] 长按手势识别正确
- [ ] 滑动方向识别正确
- [ ] `_send_mouse_click()` 鼠标点击发送正确
- [ ] `_send_key()` 键盘按键发送正确
- [ ] 单元测试覆盖手势识别

### 1.3 低延迟传输优化
- [ ] `bridge/modules/stream_optimizer.py` 文件已创建
- [ ] `StreamOptimizer` 类实现完整
- [ ] `detect_connection()` 能识别 USB/WiFi 连接
- [ ] `get_encoding_params()` 返回正确编码参数
- [ ] USB 连接时使用 50M 码率 120fps
- [ ] WiFi6 连接时使用 30M 码率 60fps
- [ ] WiFi5 连接时使用 20M 码率 60fps
- [ ] 自适应调整循环正确运行
- [ ] 单元测试覆盖连接检测

### 1.4 手机省电管理器
- [ ] `bridge/modules/phone_power_manager.py` 文件已创建
- [ ] `PhonePowerManager` 类实现完整
- [ ] `_set_refresh_rate()` 能设置屏幕刷新率
- [ ] `enable_streaming_mode()` 正确启用省电
- [ ] `disable_streaming_mode()` 正确恢复
- [ ] `check_battery_and_adjust()` 根据电量调整
- [ ] 低电量时自动降低帧率
- [ ] 充电时恢复高帧率
- [ ] 单元测试覆盖省电逻辑

## Phase 2: 应用窗口化

### 2.1 AppWindowManager 手机应用窗口管理器
- [ ] `bridge/modules/app_window_manager.py` 文件已创建
- [ ] `AppWindowManager` 类实现完整
- [ ] `create_virtual_display()` 能创建虚拟显示器
- [ ] `launch_app_window()` 能在虚拟显示器启动应用
- [ ] Scrcpy 能捕获特定虚拟显示器
- [ ] 窗口生命周期管理正确
- [ ] 应用配置加载正确
- [ ] 单元测试覆盖虚拟显示创建

### 2.2 触摸映射引擎增强
- [ ] 边缘手势检测正确 (`_detect_edge_action()`)
- [ ] 左边缘触发 Windows 键
- [ ] 右边缘触发 Alt+Tab
- [ ] 上边缘触发 Alt+F4
- [ ] 下边缘触发任务栏显示
- [ ] 应用特定映射正确 (`app_overrides`)
- [ ] 捏合缩放识别正确
- [ ] 游戏模式切换正确
- [ ] 省电模式切换正确
- [ ] 集成测试覆盖边缘手势

### 2.3 托盘应用启动器增强
- [ ] `bridge/dispatchers/tray_icon.py` 已更新
- [ ] PC应用分类菜单正确显示
- [ ] 手机应用分类菜单正确显示
- [ ] 应用启动功能正确
- [ ] 最近使用记录正确
- [ ] 集成测试覆盖启动器

### 2.4 Sunshine 深度集成
- [ ] 虚拟显示器管理完整
- [ ] Sunshine API 调用完整
- [ ] 多应用切换正确
- [ ] 会话暂停/恢复正确
- [ ] 集成测试覆盖完整流程

## Phase 3: AI推理引擎

### 3.1 AI Inference Engine 基础
- [ ] `bridge/modules/ai_inference_engine.py` 文件已创建
- [ ] `ModelInfo` 数据结构正确
- [ ] `InferenceBackend` 枚举正确
- [ ] `_detect_capabilities()` 能检测设备能力
- [ ] `infer()` 统一接口正确
- [ ] 后端路由正确
- [ ] 单元测试覆盖核心功能

### 3.2 Android 端 llama.cpp 集成
- [ ] Termux 环境检测正确
- [ ] llama.cpp 命令行调用正确
- [ ] 量化模型加载正确
- [ ] 推理结果解析正确
- [ ] 错误处理正确
- [ ] 集成测试覆盖 Android 推理

### 3.3 PC 端 Ollama 集成
- [ ] Ollama 服务检测正确
- [ ] Ollama API 调用正确
- [ ] 模型列表获取正确
- [ ] 流式推理支持正确
- [ ] 集成测试覆盖 Ollama 推理

### 3.4 智能模型选择
- [ ] 任务类型识别正确 (chat/code/translate)
- [ ] 模型能力匹配正确
- [ ] 设备负载考虑正确
- [ ] 降级策略正确 (PC→Cloud)
- [ ] 集成测试覆盖智能选择

## Phase 4: 分布式调度

### 4.1 设备性能监控
- [ ] `bridge/modules/distributed_scheduler.py` 文件已创建
- [ ] `_collect_android_metrics()` 正确采集 Android 指标
- [ ] `_collect_pc_metrics()` 正确采集 PC 指标
- [ ] GPU 监控正确 (NVIDIA-SMI)
- [ ] 网络延迟测量正确
- [ ] 指标数据结构完整
- [ ] 单元测试覆盖指标采集

### 4.2 应用注册与发现
- [ ] `_load_known_apps()` 正确加载应用配置
- [ ] `register_app()` 正确注册应用
- [ ] `unregister_app()` 正确注销应用
- [ ] 应用状态跟踪正确
- [ ] 已知应用包含微信、QQ、抖音、B站、Chrome、VSCode
- [ ] 单元测试覆盖注册发现

### 4.3 负载均衡算法
- [ ] `compute_score` 评分计算正确
- [ ] `_select_best_device()` 设备选择正确
- [ ] `_check_load_balance()` 负载检查正确
- [ ] 迁移阈值判断正确
- [ ] 单元测试覆盖负载均衡

### 4.4 应用迁移框架
- [ ] `_migrate_android_to_pc()` 迁移正确
- [ ] `_migrate_pc_to_android()` 迁移正确
- [ ] `_enable_compute_offload()` 卸载正确
- [ ] 迁移状态管理正确
- [ ] 集成测试覆盖迁移流程

## Phase 5: 传感器与环境感知

### 5.1 传感器健康监控
- [ ] `bridge/modules/sensor_health_monitor.py` 文件已创建
- [ ] `_check_sensor_available()` 传感器检测正确
- [ ] `_collect_sensor_samples()` 样本采集正确
- [ ] `_calculate_noise_level()` 噪声分析正确
- [ ] `_calculate_drift()` 漂移检测正确
- [ ] `_generate_health_report()` 报告生成正确
- [ ] 至少支持 5 种传感器检测
- [ ] 单元测试覆盖健康诊断

### 5.2 环境监控与智能联动
- [ ] `bridge/modules/environment_monitor.py` 文件已创建
- [ ] 多传感器数据采集正确
- [ ] `_check_smart_scenes()` 场景判断正确
- [ ] `_execute_scene()` 动作执行正确
- [ ] `_check_alerts()` 告警判断正确
- [ ] 至少 3 种智能场景
- [ ] 单元测试覆盖环境监控

### 5.3 Home Assistant 集成
- [ ] `bridge/modules/home_assistant_connector.py` 文件已创建
- [ ] MQTT 连接正确建立
- [ ] `_auto_discover_sensor()` 自动发现正确
- [ ] `publish_state()` 状态发布正确
- [ ] 灯光/空调/通知控制正确
- [ ] 集成测试覆盖 HA 连接

## Phase 6: 网络优化

### 6.1 混合网络管理器
- [ ] `bridge/network/mesh_network.py` 文件已创建
- [ ] `bridge/network/__init__.py` 已创建
- [ ] WireGuard 配置正确
- [ ] Tailscale 连接正确
- [ ] `_discover_lan_devices()` 设备发现正确
- [ ] `get_best_route()` 路由选择正确
- [ ] 单元测试覆盖网络管理

### 6.2 WebSocket 增强
- [ ] `bridge/listeners/ws_client.py` 已更新
- [ ] `send_mouse_move()` 鼠标移动正确
- [ ] `send_mouse_click()` 鼠标点击正确
- [ ] `send_key()` 键盘按键正确
- [ ] 触摸数据流协议正确
- [ ] 集成测试覆盖增强功能

### 6.3 设备发现服务
- [ ] mDNS 服务注册正确
- [ ] DNS-SD 服务公告正确
- [ ] 设备在线状态跟踪正确
- [ ] 服务健康检查正确
- [ ] 集成测试覆盖服务发现

## Phase 7: 配置与文档

### 7.1 配置系统更新
- [ ] `bridge/config.py` 支持新配置项
- [ ] `config.yaml` 包含完整 v2.0 配置
- [ ] 配置热更新机制正确
- [ ] 配置验证正确
- [ ] 配置文档已更新

### 7.2 文档编写
- [ ] `docs/architecture.md` 架构文档已完成
- [ ] `docs/api.md` API文档已完成
- [ ] `docs/user-guide.md` 用户指南已完成
- [ ] `docs/developer-guide.md` 开发者指南已完成

---

## 最终验收

- [ ] 所有 Phase 1-7 检查清单已完成
- [ ] 代码风格符合项目规范
- [ ] 所有单元测试通过
- [ ] 所有集成测试通过
- [ ] 文档完整且准确
- [ ] Git 提交已创建
