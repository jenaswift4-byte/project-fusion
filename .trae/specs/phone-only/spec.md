# Project Fusion - 纯手机功能 Spec

## Why
用户要求先实现仅靠手机就能完成的功能，无需外接 ESP32 等设备。4 台 MI 8 手机已就位，需要先打通核心的传感器数据采集、本地 AI 推理、设备间通信和基础媒体控制功能。

## What Changes
- 实现卧室手机 C 作为次级中枢（MQTT Broker + 本地 AI）
- 实现所有手机的传感器数据采集和上报
- 实现双模式切换（在线/离线）
- 实现本地 AI 推理（Qwen2.5-3B）
- 实现基础媒体播放和控制
- **留好 ESP32 设备接口**（但不实现）

## Impact
- **Affected specs**: dual_mode_smart_home.md（精简版，去掉 ESP32 部分）
- **Affected code**: 
  - 卧室手机 C Companion App（新增 MQTT Broker）
  - PC 端 Bridge（模式检测）
  - 新增本地 AI 推理模块

## Requirements

### Requirement 1: 次级中枢（卧室手机 C）
The system SHALL run a lightweight MQTT broker on bedroom phone C to coordinate all other phones.

#### Scenario: MQTT Broker 启动
- **WHEN** 卧室手机 C 启动
- **THEN** MQTT Broker 自动在 1883 端口监听
- **THEN** 其他手机可以连接并发布/订阅消息

### Requirement 2: 传感器数据采集
The system SHALL collect sensor data from all phones every 5 seconds.

#### Scenario: 传感器上报
- **WHEN** 每 5 秒
- **THEN** 各手机采集光线/距离/温度/湿度等传感器数据
- **THEN** 通过 MQTT 发送到卧室手机 C
- **THEN** 卧室手机 C 存储到 SQLite

### Requirement 3: 双模式切换
The system SHALL automatically switch between online mode (PC present) and offline mode (PC absent).

#### Scenario: PC 离开（切换到离线模式）
- **WHEN** PC 关闭或带走
- **THEN** 卧室手机 C 检测到 ping 失败
- **THEN** 启动本地 AI 模型（Qwen2.5-3B）
- **THEN** 所有功能降级为本地运行

#### Scenario: PC 回家（切换到在线模式）
- **WHEN** PC 启动并连接网络
- **THEN** 卧室手机 C 检测到 ping 成功
- **THEN** 停止本地 AI，切换到 PC 的 Qwen3.5-7B
- **THEN** 启用云端功能

### Requirement 4: 本地 AI 推理
The system SHALL run Qwen2.5-3B (INT4 quantized) on bedroom phone C for text generation tasks.

#### Scenario: 语音问答（离线模式）
- **WHEN** 用户对厨房手机 A 说话
- **THEN** Whisper-tiny 识别语音
- **THEN** 发送到卧室手机 C
- **THEN** Qwen2.5-3B 生成回答
- **THEN** TTS 播放回答

### Requirement 5: 媒体播放控制
The system SHALL provide basic music playback on all phones with local music library.

#### Scenario: 播放音乐
- **WHEN** 用户说"播放周杰伦的歌"
- **THEN** 在当前房间手机播放本地音乐
- **THEN** 支持暂停/切歌/音量控制

#### Scenario: 音乐跟随
- **WHEN** 人从一个房间走到另一个房间
- **THEN** 检测到位置变化
- **THEN** 音乐自动切换到新房间的手机

### Requirement 6: ESP32 接口预留
The system SHALL provide clean interfaces for future ESP32 devices without implementing them now.

#### Scenario: 未来接入 ESP32
- **WHEN** ESP32 设备接入
- **THEN** 可以通过 MQTT 控制电机/继电器
- **THEN** 代码无需修改，只需配置设备

## MODIFIED Requirements
无（新增功能）

## REMOVED Requirements
### ESP32 固件实现
**Reason**: 用户要求先不管外接设备
**Migration**: 保留接口，后续实现

### 智能插座/红外遥控
**Reason**: 用户要求先不管外接设备
**Migration**: 保留接口，后续实现
