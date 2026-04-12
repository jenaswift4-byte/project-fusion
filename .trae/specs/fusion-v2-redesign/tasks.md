# Project Fusion v2.0 实现任务清单

## Phase 1: 核心串流基础

### 任务 1.1: Sunshine Manager 基础集成
- [ ] 创建 `bridge/modules/sunshine_manager.py` 模块骨架
- [ ] 实现 Sunshine 服务检测与启动
- [ ] 实现 Sunshine 应用配置 (`apps.json`)
- [ ] 实现 `launch_app(app_id)` 方法
- [ ] 实现 `list_apps()` 方法
- [ ] 创建默认应用列表 (Chrome, VSCode, Notepad, 文件管理器等)
- [ ] 添加 Sunshine 安装路径自动检测
- [ ] 编写单元测试

### 任务 1.2: 触摸映射引擎基础
- [ ] 创建 `bridge/modules/touch_mapper.py` 模块骨架
- [ ] 实现触摸坐标映射 (`map_coordinates()`)
- [ ] 实现基础手势识别：点击、双击、长按
- [ ] 实现滑动方向识别
- [ ] 实现鼠标事件发送 (点击、移动、拖拽)
- [ ] 实现键盘事件发送
- [ ] 配置屏幕DPI参数
- [ ] 编写单元测试

### 任务 1.3: 低延迟传输优化
- [ ] 创建 `bridge/modules/stream_optimizer.py` 模块骨架
- [ ] 实现连接类型检测 (USB/WiFi6/WiFi5/Ethernet)
- [ ] 实现延迟测量 (`_measure_network_latency()`)
- [ ] 实现编码参数自适应 (`get_encoding_params()`)
- [ ] 实现 Sunshine 低延迟配置生成
- [ ] 实现自适应调整循环
- [ ] 编写单元测试

### 任务 1.4: 手机省电管理器
- [ ] 创建 `bridge/modules/phone_power_manager.py` 模块骨架
- [ ] 实现动态刷新率调整 (`_set_refresh_rate()`)
- [ ] 实现屏幕亮度管理
- [ ] 实现省电模式切换 (`enable_streaming_mode()`)
- [ ] 实现电池监控与自动调整
- [ ] 编写单元测试

## Phase 2: 应用窗口化

### 任务 2.1: AppWindowManager 手机应用窗口管理器
- [ ] 创建 `bridge/modules/app_window_manager.py` 模块骨架
- [ ] 实现 VirtualDisplay 创建 (`create_virtual_display()`)
- [ ] 实现 Scrcpy 单应用捕获 (`launch_app_window()`)
- [ ] 实现窗口生命周期管理
- [ ] 实现应用列表配置管理
- [ ] 编写单元测试

### 任务 2.2: 触摸映射引擎增强
- [ ] 实现边缘手势检测 (`_detect_edge_action()`)
- [ ] 实现边缘动作处理 (Windows键、Alt+Tab等)
- [ ] 实现应用特定快捷键映射 (`app_overrides`)
- [ ] 实现捏合缩放识别
- [ ] 实现游戏模式与省电模式切换
- [ ] 编写集成测试

### 任务 2.3: 托盘应用启动器增强
- [ ] 扩展 `bridge/dispatchers/tray_icon.py` 增加PC应用分类
- [ ] 实现应用启动菜单分组
- [ ] 实现应用搜索功能
- [ ] 实现最近使用记录
- [ ] 编写集成测试

### 任务 2.4: Sunshine 深度集成
- [ ] 实现虚拟显示器管理
- [ ] 实现 Sunshine API 完整调用
- [ ] 实现多应用切换
- [ ] 实现会话暂停/恢复
- [ ] 编写集成测试

## Phase 3: AI推理引擎

### 任务 3.1: AI Inference Engine 基础
- [ ] 创建 `bridge/modules/ai_inference_engine.py` 模块骨架
- [ ] 实现模型信息数据结构 (`ModelInfo`)
- [ ] 实现推理后端枚举 (`InferenceBackend`)
- [ ] 实现设备能力检测 (`_detect_capabilities()`)
- [ ] 实现统一推理接口 (`infer()`)
- [ ] 编写单元测试

### 任务 3.2: Android 端 llama.cpp 集成
- [ ] 实现 Termux 环境检测
- [ ] 实现 llama.cpp 命令行调用
- [ ] 实现量化模型加载
- [ ] 实现推理结果解析
- [ ] 编写集成测试

### 任务 3.3: PC 端 Ollama 集成
- [ ] 实现 Ollama 服务检测
- [ ] 实现 Ollama API 调用
- [ ] 实现模型列表获取
- [ ] 实现流式推理支持
- [ ] 编写集成测试

### 任务 3.4: 智能模型选择
- [ ] 实现任务类型识别 (chat/code/translate/summarize)
- [ ] 实现模型能力匹配
- [ ] 实现设备负载考虑
- [ ] 实现降级策略 (PC→Cloud)
- [ ] 编写集成测试

## Phase 4: 分布式调度

### 任务 4.1: 设备性能监控
- [ ] 创建 `bridge/modules/distributed_scheduler.py` 模块骨架
- [ ] 实现 Android 指标采集 (`_collect_android_metrics()`)
- [ ] 实现 PC 指标采集 (`_collect_pc_metrics()`)
- [ ] 实现 GPU 监控 (NVIDIA-SMI)
- [ ] 实现网络延迟测量
- [ ] 编写单元测试

### 任务 4.2: 应用注册与发现
- [ ] 实现已知应用配置加载 (`_load_known_apps()`)
- [ ] 实现应用注册 (`register_app()`)
- [ ] 实现应用注销 (`unregister_app()`)
- [ ] 实现应用状态跟踪
- [ ] 编写单元测试

### 任务 4.3: 负载均衡算法
- [ ] 实现设备评分计算 (`compute_score`)
- [ ] 实现最佳设备选择 (`_select_best_device()`)
- [ ] 实现负载检查 (`_check_load_balance()`)
- [ ] 实现迁移阈值判断
- [ ] 编写单元测试

### 任务 4.4: 应用迁移框架
- [ ] 实现 Android→PC 迁移 (`_migrate_android_to_pc()`)
- [ ] 实现 PC→Android 迁移 (`_migrate_pc_to_android()`)
- [ ] 实现计算卸载 (`_enable_compute_offload()`)
- [ ] 实现迁移状态管理
- [ ] 编写集成测试

## Phase 5: 传感器与环境感知

### 任务 5.1: 传感器健康监控
- [ ] 创建 `bridge/modules/sensor_health_monitor.py` 模块骨架
- [ ] 实现传感器可用性检测 (`_check_sensor_available()`)
- [ ] 实现样本数据采集 (`_collect_sensor_samples()`)
- [ ] 实现噪声等级分析 (`_calculate_noise_level()`)
- [ ] 实现漂移检测 (`_calculate_drift()`)
- [ ] 实现健康报告生成 (`_generate_health_report()`)
- [ ] 编写单元测试

### 任务 5.2: 环境监控与智能联动
- [ ] 创建 `bridge/modules/environment_monitor.py` 模块骨架
- [ ] 实现多传感器数据采集
- [ ] 实现智能场景触发 (`_check_smart_scenes()`)
- [ ] 实现场景动作执行 (`_execute_scene()`)
- [ ] 实现异常告警 (`_check_alerts()`)
- [ ] 编写单元测试

### 任务 5.3: Home Assistant 集成
- [ ] 创建 `bridge/modules/home_assistant_connector.py` 模块骨架
- [ ] 实现 MQTT 连接 (`_setup_mqtt()`)
- [ ] 实现设备自动发现 (`_auto_discover_sensor()`)
- [ ] 实现状态发布 (`publish_state()`)
- [ ] 实现灯光/空调/通知控制
- [ ] 编写集成测试

## Phase 6: 网络优化

### 任务 6.1: 混合网络管理器
- [ ] 创建 `bridge/network/mesh_network.py` 模块骨架
- [ ] 创建 `bridge/network/__init__.py`
- [ ] 实现 WireGuard VPN 配置
- [ ] 实现 Tailscale 连接
- [ ] 实现设备发现 (`_discover_lan_devices()`)
- [ ] 实现最佳路由选择 (`get_best_route()`)
- [ ] 编写单元测试

### 任务 6.2: WebSocket 增强
- [ ] 扩展 `bridge/listeners/ws_client.py` 增加PC控制命令
- [ ] 实现鼠标移动命令 (`send_mouse_move()`)
- [ ] 实现鼠标点击命令 (`send_mouse_click()`)
- [ ] 实现键盘命令 (`send_key()`)
- [ ] 实现触摸数据流传输协议
- [ ] 编写集成测试

### 任务 6.3: 设备发现服务
- [ ] 实现 mDNS 服务注册
- [ ] 实现 DNS-SD 服务公告
- [ ] 实现设备在线状态跟踪
- [ ] 实现服务健康检查
- [ ] 编写集成测试

## Phase 7: 配置与文档

### 任务 7.1: 配置系统更新
- [ ] 更新 `bridge/config.py` 支持新配置项
- [ ] 更新 `config.yaml` 添加完整v2.0配置
- [ ] 实现配置热更新机制
- [ ] 实现配置验证
- [ ] 编写配置文档

### 任务 7.2: 文档编写
- [ ] 编写 `docs/architecture.md` 架构文档
- [ ] 编写 `docs/api.md` API接口文档
- [ ] 编写 `docs/user-guide.md` 用户指南
- [ ] 编写 `docs/developer-guide.md` 开发者指南

---

## 任务依赖关系

```
Phase 1:
  [1.1] → [1.2] → [1.3] → [1.4]
  (Sunshine基础后其他模块可并行)

Phase 2:
  [1.1] → [2.1] → [2.4]
  [1.2] → [2.2] → [2.3]

Phase 3:
  [1.4] 可并行
  [3.1] → [3.2], [3.3] → [3.4]

Phase 4:
  [4.1] → [4.2] → [4.3] → [4.4]

Phase 5:
  [5.1] 可单独进行
  [5.1] → [5.2] → [5.3]

Phase 6:
  [6.1] 可单独进行
  [6.2] 依赖 [2.2]
  [6.3] 依赖 [6.1]

Phase 7:
  [7.1] 依赖所有Phase
  [7.2] 依赖 [7.1]
```

---

## 验证标准

每个任务完成后应满足：
1. 单元测试通过
2. 代码符合项目编码规范
3. 集成测试（相关模块联调）通过
4. 文档已更新
