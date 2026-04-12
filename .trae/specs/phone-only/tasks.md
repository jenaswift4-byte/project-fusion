# Tasks - Phone Only Features

## 阶段 1: 次级中枢（卧室手机 C）

- [x] **Task 1: 实现 MQTT Broker（卧室手机 C）**
  - 在 Companion App 中集成 MQTT Broker
  - 监听 1883 端口
  - 支持发布/订阅
  - 支持多客户端连接

- [x] **Task 2: 实现 SQLite 存储（卧室手机 C）**
  - 创建数据库表结构
  - 存储传感器数据
  - 存储设备状态
  - 支持查询历史数据

## 阶段 2: 传感器数据采集

- [x] **Task 3: 实现传感器采集模块（所有手机）**
  - 光线传感器
  - 距离传感器
  - 温度传感器
  - 湿度传感器
  - 气压计
  - 陀螺仪 + 加速度计

- [x] **Task 4: 实现 MQTT 客户端（所有手机）**
  - 连接到卧室手机 C 的 Broker
  - 每 5 秒上报传感器数据
  - 订阅控制命令

## 阶段 3: 双模式切换

- [x] **Task 5: 实现 PC 在线检测（卧室手机 C）**
  - 每 10 秒 ping PC
  - 检测超时判断离线
  - 触发模式切换

- [x] **Task 6: 实现模式切换逻辑**
  - 离线模式：启动本地 AI
  - 在线模式：切换到 PC
  - 平滑过渡，不中断服务

## 阶段 4: 本地 AI 推理

- [x] **Task 7: 集成 Qwen2.5-3B（卧室手机 C）**
  - 下载 INT4 量化模型
  - 实现推理引擎
  - 优化内存占用
  - 测试推理速度

- [x] **Task 8: 实现语音识别（所有手机）**
  - 集成 Whisper-tiny-zh
  - 本地语音识别
  - 发送到卧室手机 C 处理

- [x] **Task 9: 实现 TTS（所有手机）**
  - 集成 VITS-zh
  - 文本转语音
  - 播放回答

- [x] **Task 10: 实现本地音乐库（卧室手机 C）**
  - 扫描本地音乐文件
  - 建立索引
  - 支持搜索

- [x] **Task 11: 实现媒体播放器（所有手机）**
  - 播放/暂停/切歌
  - 音量控制
  - 播放列表管理

- [x] **Task 12: 实现音乐跟随功能**
  - 检测人位置（距离传感器）
  - 自动切换播放设备
  - 保持播放进度

## 阶段 6: ESP32 接口预留

- [x] **Task 13: 定义 MQTT 设备协议**
  - 窗帘控制主题
  - 灯光控制主题
  - 传感器数据格式
  - 命令格式
  - 已完成文件：DeviceProtocol.java

- [x] **Task 14: 实现设备管理接口**
  - 设备注册
  - 设备状态查询
  - 设备控制 API
  - 留空实现，等待 ESP32
  - 已完成文件：DeviceManager.java

---

# Task Dependencies

- **Task 2** depends on **Task 1** (SQLite 需要 MQTT Broker 先运行)
- **Task 4** depends on **Task 1** (MQTT 客户端需要 Broker)
- **Task 6** depends on **Task 5** (模式切换需要检测)
- **Task 8** depends on **Task 7** (语音识别需要 AI 引擎)
- **Task 9** depends on **Task 7** (TTS 需要 AI 引擎)
- **Task 11** depends on **Task 10** (播放器需要音乐库)
- **Task 12** depends on **Task 11** (音乐跟随需要播放器)
- **Task 13** depends on **Task 1** (协议基于 MQTT)
