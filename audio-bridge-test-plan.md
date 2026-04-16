# Audio Bridge Testing Plan for sherpa-onnx Android Project

## Overview
This document outlines the comprehensive testing strategy for the audio bridge functionality in the Android companion app, which enables real-time audio streaming from Android device microphones to PC for sherpa-onnx ASR processing.

## Test Objectives
1. Verify audio capture pipeline (Android AudioRecord → PCM data)
2. Test WebSocket transmission of PCM audio data
3. Validate sherpa-onnx ASR integration on PC side
4. Test VAD (Voice Activity Detection) functionality
5. Verify speaker identification integration
6. Test error handling and edge cases
7. Validate performance metrics (latency, throughput)

## Test Environment Setup

### Android Device Requirements
- Android device with USB debugging enabled
- Microphone with good quality
- Network connectivity (WiFi or USB tethering)

### PC Requirements
- Windows PC with Python 3.10+
- sherpa-onnx model files (encoder, decoder, joiner, tokens.txt)
- WebSocket client capability
- Audio playback capability

### Required Files and Tools
- `audio-test-scripts.ps1` - PowerShell test automation script
- `test_all_scenarios.py` - Python automated test framework
- `AudioStreamer.java` - Android audio streaming implementation
- `StreamingASRService.java` - ASR service implementation
- `SpeakerIdentifier.java` - Speaker identification implementation

## Test Scenarios

### Scenario 1: Audio Stream Initialization Test
**Purpose**: Verify audio stream can be initialized and started
- Start AudioStreamer service
- Verify microphone permission check
- Test AudioRecord initialization with 16kHz, 16-bit, mono
- Validate WebSocket connection establishment
- Expected: Stream starts successfully, PCM data transmitted

### Scenario 2: PCM Data Transmission Test
**Purpose**: Verify PCM audio data is correctly transmitted
- Start audio streaming
- Capture transmitted PCM frames
- Validate frame format (16kHz, 16-bit, mono)
- Check frame size (~3200 bytes per 200ms)
- Expected: Consistent PCM data stream without gaps

### Scenario 3: ASR Integration Test
**Purpose**: Verify PC-side ASR processing works
- Transmit test audio through audio bridge
- Process PCM data through sherpa-onnx ASR
- Validate text output quality
- Test with clean speech and noisy environments
- Expected: High recognition accuracy (>90% in quiet)

### Scenario 4: VAD Testing
**Purpose**: Verify Voice Activity Detection
- Test with varying RMS energy levels
- Validate speech/non-speech classification
- Test threshold adjustment functionality
- Expected: Correct classification with minimal false positives/negatives

### Scenario 5: Speaker Identification Test
**Purpose**: Verify speaker recognition functionality
- Load registered speaker profiles
- Test with known speakers
- Validate identification accuracy
- Expected: Correct speaker matching

### Scenario 6: Error Handling Tests
**Purpose**: Verify robust error handling
- Test with missing model files
- Test with insufficient permissions
- Test WebSocket disconnections
- Expected: Graceful error handling without crashes

### Scenario 7: Performance Testing
**Purpose**: Validate performance metrics
- Measure end-to-end latency
- Test throughput under load
- Validate resource usage
- Expected: Latency < 350ms, stable performance

## Test Execution Steps

### Step 1: Android Device Setup
```bash
# Enable USB debugging
adb devices

# Push model files
adb push sherpa-onnx-model/ /sdcard/Android/data/com.fusion.companion/files/models/

# Start the companion app
adb shell am start -n com.fusion.companion/.MainActivity
```

### Step 2: PC Setup
```bash
# Navigate to bridge directory
cd /path/to/project-fusion/bridge

# Install dependencies
pip install -r requirements.txt

# Start the bridge service
python main.py
```

### Step 3: Run Automated Tests
```bash
# Run PowerShell test script
./audio-test-scripts.ps1

# Or run Python test framework
python test_all_scenarios.py
```

### Step 4: Manual Testing
- Use test audio files: quiet.wav, noise.wav, long.wav
- Monitor logs in real-time
- Verify ASR output quality
- Test various edge cases

## Logging and Monitoring

### Android Logs
```bash
# Capture Android logs
adb logcat -c && adb logcat -v time > test_logs.txt

# Filter ASR-related logs
adb logcat -d | grep -E "(StreamingASR|sherpa|VADHelper|AudioStreamer)"
```

### PC Logs
- Monitor console output from bridge service
- Check WebSocket server logs
- Review ASR processing logs

## Performance Metrics

### Latency Requirements
- Audio capture delay: < 100ms
- Network transmission: < 50ms  
- ASR processing: < 200ms
- End-to-end: < 350ms

### Accuracy Requirements
- Quiet environment: >90%
- Noisy environment: >70%
- VAD detection: >85%

## Test Data Files

### Audio Test Files
- `test_quiet.wav` - Clean speech for baseline testing
- `test_noise.wav` - Noisy environment testing
- `test_long.wav` - Long duration testing (>30 seconds)

### Model Files Required
- `encoder-epoch-99-avg-1.onnx`
- `decoder-epoch-99-avg-1.onnx`
- `joiner-epoch-99-avg-1.onnx`
- `tokens.txt`

## Troubleshooting

### Common Issues
1. **Permission errors**: Ensure RECORD_AUDIO permission granted
2. **Model loading failures**: Verify model files are pushed correctly
3. **WebSocket connection issues**: Check network connectivity
4. **High latency**: Optimize buffer sizes and processing pipeline

### Debug Commands
```bash
# Check ADB connection
adb devices

# Monitor specific log tags
adb logcat -s StreamingASR

# Test audio file transmission
adb push test_audio.wav /sdcard/test.wav
```

## Test Automation

The testing framework includes:
- Automated scenario execution
- Result validation and reporting
- Performance metric collection
- Error detection and logging

## Success Criteria
All test scenarios pass with:
- No crashes or exceptions
- Performance metrics within requirements
- Accuracy above threshold levels
- Proper error handling