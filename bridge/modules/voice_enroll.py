"""
PC 端声纹录制与分析工具

工作流程:
  1. 用户分别录制自己与陈慧美的 30 秒语音 (WAV, 16kHz 单声道)
  2. 提取声纹嵌入向量 (能量特征 / Vosk / Sherpa-onnx)
  3. 将声纹保存为二进制文件或 Base64 字符串
  4. 通过 MQTT 主题 speaker/enroll 发送给手机端

使用方式:
  python voice_enroll.py --speaker user
  python voice_enroll.py --speaker huimei
  python voice_enroll.py --send-all

@author Fusion
@version 1.0
"""

import argparse
import base64
import json
import os
import struct
import sys
import time
import wave

# 音频参数 (与 Android 端 AudioStreamer 一致)
SAMPLE_RATE = 16000
CHANNELS = 1
SAMPLE_WIDTH = 2  # 16-bit = 2 bytes
RECORD_DURATION = 30  # 秒

# 输出目录
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "speaker_profiles")

# MQTT 配置 (通过 bridge/config.py 获取)
MQTT_HOST = "127.0.0.1"
MQTT_PORT = 1883


def check_audio_libs():
    """检查音频录制库"""
    try:
        import sounddevice as sd
        return True
    except ImportError:
        print("❌ sounddevice 未安装, 请运行: pip install sounddevice")
        return False


def record_wav(speaker_name: str, duration: int = RECORD_DURATION) -> str:
    """
    录制 WAV 文件 (16kHz, 16-bit, mono)

    Args:
        speaker_name: 说话人标签
        duration: 录制时长 (秒)

    Returns:
        保存的 WAV 文件路径
    """
    import sounddevice as sd
    import numpy as np

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    output_path = os.path.join(OUTPUT_DIR, f"{speaker_name}.wav")

    print(f"\n🎤 录制 {speaker_name} 的声纹样本 ({duration}秒)")
    print(f"   请在提示后开始说话，尽量自然...")
    input("   按 Enter 开始录制...")

    total_samples = SAMPLE_RATE * duration

    print(f"   ⏺ 录制中... ", end="", flush=True)
    audio_data = sd.rec(
        total_samples,
        samplerate=SAMPLE_RATE,
        channels=CHANNELS,
        dtype="int16",
    )
    sd.wait()
    print("完成")

    # 保存 WAV
    with wave.open(output_path, "wb") as wf:
        wf.setnchannels(CHANNELS)
        wf.setsampwidth(SAMPLE_WIDTH)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(audio_data.tobytes())

    file_size = os.path.getsize(output_path)
    print(f"   ✅ 已保存: {output_path} ({file_size / 1024:.0f}KB)")

    return output_path


def extract_energy_features(wav_path: str, num_segments: int = 8) -> list:
    """
    提取能量分布特征 (与 Android 端 SpeakerIdentifier 一致)

    将 WAV 分成 N 段，计算每段 RMS，归一化后返回浮点数组

    Args:
        wav_path: WAV 文件路径
        num_segments: 分段数

    Returns:
        归一化的浮点数组
    """
    with wave.open(wav_path, "rb") as wf:
        n_frames = wf.getnframes()
        raw_data = wf.readframes(n_frames)

    # 解析 16-bit PCM
    num_samples = len(raw_data) // 2
    samples = struct.unpack(f"<{num_samples}h", raw_data)

    # 分段计算 RMS
    segment_size = num_samples // num_segments
    features = []

    for i in range(num_segments):
        start = i * segment_size
        end = min(start + segment_size, num_samples)
        segment = samples[start:end]

        sum_sq = sum(s * s for s in segment)
        rms = (sum_sq / len(segment)) ** 0.5
        features.append(rms)

    # 归一化
    max_val = max(abs(v) for v in features) if features else 1
    if max_val > 0:
        features = [v / max_val for v in features]

    return features


def extract_embedding_vosk(wav_path: str) -> list:
    """
    使用 Vosk 提取声纹嵌入向量 (高精度)

    TODO: 需要安装 vosk 库和模型
    """
    try:
        from vosk import Model, SpkModel

        # TODO: 加载模型并提取嵌入向量
        print("⚠️ Vosk 声纹模型未集成, 使用能量特征替代")
    except ImportError:
        print("⚠️ vosk 库未安装, 使用能量特征替代")

    return None


def extract_embedding_sherpa(wav_path: str) -> list:
    """
    使用 Sherpa-onnx 提取声纹嵌入向量 (高精度)

    TODO: 需要安装 sherpa-onnx 库
    """
    try:
        import sherpa_onnx

        # TODO: 加载模型并提取嵌入向量
        print("⚠️ Sherpa-onnx 声纹模型未集成, 使用能量特征替代")
    except ImportError:
        print("⚠️ sherpa-onnx 库未安装, 使用能量特征替代")

    return None


def features_to_base64(features: list) -> str:
    """将浮点数组转为 Base64 二进制"""
    raw_bytes = struct.pack(f"<{len(features)}f", *features)
    return base64.b64encode(raw_bytes).decode("ascii")


def save_profile(speaker_name: str, features: list) -> str:
    """
    保存声纹特征到文件

    Args:
        speaker_name: 说话人标签
        features: 特征浮点数组

    Returns:
        保存的文件路径
    """
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    profile_path = os.path.join(OUTPUT_DIR, f"{speaker_name}.bin")

    raw_bytes = struct.pack(f"<{len(features)}f", *features)
    with open(profile_path, "wb") as f:
        f.write(raw_bytes)

    print(f"   💾 声纹文件已保存: {profile_path} ({len(raw_bytes)} bytes)")
    return profile_path


def send_to_phone(speaker_name: str, features: list):
    """
    通过 MQTT 将声纹特征发送给手机端

    主题: speaker/enroll
    消息格式: {"action": "enroll", "label": "user", "format": "vector", "data": "0.1,0.2,..."}
    """
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        print("❌ paho-mqtt 未安装, 请运行: pip install paho-mqtt")
        return False

    # 构建消息
    data_str = ",".join(str(v) for v in features)
    message = json.dumps({
        "action": "enroll",
        "label": speaker_name,
        "format": "vector",
        "data": data_str,
        "timestamp": int(time.time() * 1000),
    })

    print(f"\n📡 发送声纹到手机...")
    print(f"   主题: speaker/enroll")
    print(f"   标签: {speaker_name}")
    print(f"   特征维度: {len(features)}")

    # 连接 MQTT 并发送
    client = mqtt.Client(client_id="voice_enroll_pc")
    try:
        client.connect(MQTT_HOST, MQTT_PORT, 60)
        client.loop_start()
        client.publish("speaker/enroll", message, qos=1)
        client.loop_stop()
        client.disconnect()
        print(f"   ✅ 发送成功")
        return True
    except Exception as e:
        print(f"   ❌ 发送失败: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description="PC 端声纹录制与分析工具")
    parser.add_argument("--speaker", choices=["user", "huimei"], help="录制指定说话人的声纹")
    parser.add_argument("--send-all", action="store_true", help="发送所有已录制的声纹到手机")
    parser.add_argument("--list", action="store_true", help="列出已录制的声纹")
    parser.add_argument("--duration", type=int, default=RECORD_DURATION, help="录制时长(秒)")
    parser.add_argument("--method", choices=["energy", "vosk", "sherpa"], default="energy",
                        help="特征提取方法")
    args = parser.parse_args()

    if not check_audio_libs() and not args.list and not args.send_all:
        sys.exit(1)

    if args.list:
        print("\n📋 已录制的声纹:")
        if not os.path.exists(OUTPUT_DIR):
            print("   (无)")
            return

        for f in sorted(os.listdir(OUTPUT_DIR)):
            if f.endswith((".wav", ".bin")):
                path = os.path.join(OUTPUT_DIR, f)
                size = os.path.getsize(path)
                print(f"   {f:30s} {size:>8,} bytes")
        return

    if args.send_all:
        print("\n📡 发送所有已录制声纹到手机...")
        for f in sorted(os.listdir(OUTPUT_DIR)):
            if f.endswith(".wav"):
                speaker_name = f.replace(".wav", "")
                wav_path = os.path.join(OUTPUT_DIR, f)

                # 提取特征
                if args.method == "energy":
                    features = extract_energy_features(wav_path)
                else:
                    features = None  # TODO

                if features:
                    save_profile(speaker_name, features)
                    send_to_phone(speaker_name, features)
        return

    if args.speaker:
        # 录制
        wav_path = record_wav(args.speaker, args.duration)

        # 提取特征
        print(f"\n🔍 提取声纹特征 (方法: {args.method})...")
        if args.method == "energy":
            features = extract_energy_features(wav_path)
        elif args.method == "vosk":
            features = extract_embedding_vosk(wav_path) or extract_energy_features(wav_path)
        elif args.method == "sherpa":
            features = extract_embedding_sherpa(wav_path) or extract_energy_features(wav_path)
        else:
            features = extract_energy_features(wav_path)

        print(f"   特征: {[f'{v:.4f}' for v in features]}")

        # 保存
        save_profile(args.speaker, features)

        # 发送到手机
        send_to_phone(args.speaker, features)
        return

    parser.print_help()


if __name__ == "__main__":
    main()
