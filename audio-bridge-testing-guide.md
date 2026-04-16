# Audio Bridge Testing - Complete Implementation Guide

## 📋 Project Overview

This document provides a complete implementation guide for audio bridge testing in the sherpa-onnx Android project. The audio bridge enables real-time microphone audio streaming from Android devices to PC for sherpa-onnx automatic speech recognition (ASR) processing.

## 🎯 Testing Goals

1. **Verify Audio Pipeline**: Ensure audio capture → transmission → ASR processing works end-to-end
2. **Test Integration Points**: Validate Android ↔ PC communication
3. **Performance Validation**: Measure latency and throughput against requirements
4. **Error Handling**: Test robustness under various failure conditions
5. **Feature Coverage**: Test all audio-related features (VAD, speaker ID, ASR)

## 📁 Project Structure

```
audio-bridge-test/
├── audio-test-framework.py      # Main test orchestrator
├── audio-test-scripts.ps1       # PowerShell automation script
├── audio-bridge-test-plan.md    # Detailed test plan
├── test_results.json           # Test results output
├── test_audio.py               # Individual test modules
├── test_vad.py
├── test_asr.py
└── test_performance.py
```

## 🚀 Quick Start

### Prerequisites
- Android device with USB debugging enabled
- PC with Python 3.10+
- sherpa-onnx model files
- ADB in PATH or specified location

### Running Tests

```bash
# Method 1: Python framework
python audio-test-framework.py

# Method 2: PowerShell script
./audio-test-scripts.ps1

# Method 3: Individual test modules
python test_audio.py
python test_vad.py
python test_asr.py
```

## 🧪 Test Scenarios

### 1. Audio Stream Initialization
**File**: `audio-test-framework.py` (check_device_connection, start_audio_stream)

**Purpose**: Verify audio pipeline can be initialized
- Check ADB device connection
- Push model files to device
- Start AudioStreamer service
- Validate WebSocket connection

**Expected**: Service starts successfully, PCM data flows

### 2. PCM Data Transmission
**File**: `test_audio.py`

**Purpose**: Verify audio data transmission integrity
- Start audio streaming
- Capture transmitted PCM frames
- Validate frame format (16kHz, 16-bit, mono)
- Check frame size (~3200 bytes per 200ms)

**Expected**: Consistent PCM stream without gaps or corruption

### 3. ASR Integration
**File**: `test_asr.py`

**Purpose**: Validate PC-side ASR processing
- Transmit test audio through bridge
- Process through sherpa-onnx ASR
- Validate text output quality
- Test with clean and noisy audio

**Expected**: High recognition accuracy (>90% in quiet)

### 4. Voice Activity Detection (VAD)
**File**: `test_vad.py`

**Purpose**: Test VAD functionality
- Test with varying RMS energy levels
- Validate speech/non-speech classification
- Test threshold adjustment

**Expected**: Correct classification with minimal false positives/negatives

### 5. Speaker Identification
**File**: `test_speaker.py`

**Purpose**: Verify speaker recognition
- Load registered speaker profiles
- Test with known speakers
- Validate identification accuracy

**Expected**: Correct speaker matching

### 6. Error Handling
**File**: `test_audio.py` (error handling section)

**Purpose**: Test robustness
- Missing model files
- Insufficient permissions
- WebSocket disconnections

**Expected**: Graceful error handling without crashes

### 7. Performance Testing
**File**: `test_performance.py`

**Purpose**: Validate performance metrics
- Measure end-to-end latency
- Test throughput under load
- Validate resource usage

**Expected**: Latency < 350ms, stable performance

## 🔧 Implementation Details

### AudioStreamer.java
**Location**: `android-companion/app/src/main/java/com/fusion/companion/audio/AudioStreamer.java`

**Key Features**:
- AudioRecord initialization (16kHz, 16-bit, mono)
- PCM frame buffering (~200ms chunks)
- Base64 encoding for transmission
- WebSocket integration
- PCM listener support for VAD and ASR

**Testing Points**:
- Permission checks
- AudioRecord initialization
- Stream loop stability
- Error handling

### StreamingASRService.java
**Location**: `android-companion/app/src/main/java/com/fusion/companion/asr/StreamingASRService.java`

**Integration Points**:
- Receives PCM data from AudioStreamer
- Processes through sherpa-onnx
- Returns text results via WebSocket

**Testing Points**:
- ASR initialization
- Model loading
- Recognition accuracy
- Performance

### SpeakerIdentifier.java
**Location**: `android-companion/app/src/main/java/com/fusion/companion/speaker/SpeakerIdentifier.java`

**Integration Points**:
- Receives PCM data for speaker recognition
- Compares against registered profiles
- Returns speaker ID

**Testing Points**:
- Profile loading
- Recognition accuracy
- Performance

## 📊 Performance Metrics

### Latency Requirements
| Component | Requirement | Test Method |
|-----------|-------------|-------------|
| Audio capture | < 100ms | Frame timestamp analysis |
| Network transmission | < 50ms | Round-trip timing |
| ASR processing | < 200ms | End-to-end measurement |
| **Total** | **< 350ms** | Full pipeline test |

### Accuracy Requirements
| Scenario | Requirement | Test Method |
|----------|-------------|-------------|
| Quiet environment | >90% | Controlled audio |
| Noisy environment | >70% | Noise injection |
| VAD detection | >85% | Signal analysis |

## 🛠️ Test Automation

### Python Framework (audio-test-framework.py)
- Orchestrates all test scenarios
- Collects performance metrics
- Generates test reports
- Saves results to JSON

### PowerShell Script (audio-test-scripts.ps1)
- Device connectivity checks
- Model file deployment
- Log capture and filtering
- Automated test execution

## 📝 Test Data

### Required Audio Files
- `test_quiet.wav` - Clean speech (baseline)
- `test_noise.wav` - Noisy environment
- `test_long.wav` - Long duration (>30s)

### Model Files (sherpa-onnx-model/)
- `encoder-epoch-99-avg-1.onnx`
- `decoder-epoch-99-avg-1.onnx`
- `joiner-epoch-99-avg-1.onnx`
- `tokens.txt`

## 🔍 Debugging Guide

### Common Issues

**1. Permission Errors**
```bash
# Check permissions
adb shell pm list permissions -d
# Grant permission if needed
adb shell pm grant com.fusion.companion android.permission.RECORD_AUDIO
```

**2. Model Loading Failures**
```bash
# Verify files exist
adb shell ls /sdcard/Android/data/com.fusion.companion/files/models/
# Check logs
adb logcat -s StreamingASR
```

**3. WebSocket Connection Issues**
```bash
# Check connectivity
adb shell netstat -an | grep 17532
# Test connection
adb logcat -s WebSocket
```

**4. High Latency**
```bash
# Monitor performance
adb logcat -s AudioStreamer
# Check frame processing times
```

## 📈 Test Results Analysis

### Result Categories
- **PASS**: All requirements met
- **FAIL**: Functional issues detected
- **ERROR**: Test execution failed
- **SKIP**: Test not applicable

### Metrics Collection
- Duration tracking for each test
- Performance metrics (latency, throughput)
- Accuracy measurements
- Resource usage (CPU, memory)

### Report Generation
```json
{
  "name": "Test Name",
  "status": "PASS/FAIL/ERROR",
  "duration": 1.23,
  "details": "Additional information",
  "metrics": {
    "latency_ms": 123,
    "accuracy": 0.95
  }
}
```

## 🎯 Success Criteria

All tests pass when:
1. ✅ No crashes or exceptions
2. ✅ Performance metrics within requirements
3. ✅ Accuracy above threshold levels
4. ✅ Proper error handling
5. ✅ All features functional

## 🔄 Continuous Testing

### Integration with CI/CD
```yaml
# Example GitHub Actions
- name: Run Audio Bridge Tests
  run: |
    python audio-test-framework.py
    cat test_results.json
```

### Scheduled Testing
- Daily regression tests
- Performance baseline tracking
- Feature validation

## 📚 Additional Resources

- [AudioStreamer Documentation](android-companion/app/src/main/java/com/fusion/companion/audio/AudioStreamer.java)
- [StreamingASRService Documentation](android-companion/app/src/main/java/com/fusion/companion/asr/StreamingASRService.java)
- [SpeakerIdentifier Documentation](android-companion/app/src/main/java/com/fusion/companion/speaker/SpeakerIdentifier.java)
- [Test Plan](audio-bridge-test-plan.md)

## 🆘 Support

For issues or questions:
1. Check test logs in `test_results.json`
2. Review Android logcat output
3. Verify model files are correctly deployed
4. Check ADB connection status

---

**Last Updated**: 2026-04-15
**Version**: 1.0
**Status**: Production Ready