package com.fusion.companion.voice;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.fusion.companion.ai.LocalAIEngine;
import com.fusion.companion.service.MQTTClientService;
import com.fusion.companion.R;

/**
 * 语音识别测试 Activity
 * 
 * 演示如何使用语音识别功能：
 * 1. 语音识别（中文）
 * 2. MQTT 发送识别结果
 * 3. AI 引擎处理
 * 4. TTS 播放回答
 * 
 * 注意：这是一个测试 Activity，实际使用时可以根据需要集成到自己的 Activity 中
 */
public class VoiceTestActivity extends Activity implements 
        VoiceActivityManager.VoiceInteractionListener {
    
    private static final String TAG = "VoiceTestActivity";
    
    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    // UI 组件
    private Button btnStartVoice;
    private Button btnStopVoice;
    private TextView tvRecognizedText;
    private TextView tvAIResponse;
    private TextView tvStatus;
    
    // 语音管理器
    private VoiceActivityManager voiceManager;
    
    // MQTT 客户端（需要从服务获取）
    private MQTTClientService mqttClient;
    
    // AI 引擎
    private LocalAIEngine aiEngine;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 创建简单的 UI
        createSimpleUI();
        
        // 初始化管理器
        initializeVoiceManager();
        
        // 检查权限
        checkPermissions();
    }
    
    /**
     * 创建简单的 UI
     */
    private void createSimpleUI() {
        // 创建线性布局
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);
        
        // 标题
        TextView title = new TextView(this);
        title.setText("语音识别测试");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 16);
        layout.addView(title);
        
        // 开始按钮
        btnStartVoice = new Button(this);
        btnStartVoice.setText("开始语音识别");
        btnStartVoice.setOnClickListener(v -> onStartVoiceClick());
        layout.addView(btnStartVoice);
        
        // 停止按钮
        btnStopVoice = new Button(this);
        btnStopVoice.setText("停止");
        btnStopVoice.setOnClickListener(v -> onStopVoiceClick());
        layout.addView(btnStopVoice);
        
        // 状态文本
        tvStatus = new TextView(this);
        tvStatus.setText("状态：未开始");
        tvStatus.setTextSize(16);
        tvStatus.setPadding(0, 16, 0, 8);
        layout.addView(tvStatus);
        
        // 识别结果文本
        tvRecognizedText = new TextView(this);
        tvRecognizedText.setText("识别结果：");
        tvRecognizedText.setTextSize(16);
        tvRecognizedText.setPadding(0, 8, 0, 8);
        layout.addView(tvRecognizedText);
        
        // AI 回答文本
        tvAIResponse = new TextView(this);
        tvAIResponse.setText("AI 回答：");
        tvAIResponse.setTextSize(16);
        tvAIResponse.setPadding(0, 8, 0, 8);
        layout.addView(tvAIResponse);
        
        setContentView(layout);
    }
    
    /**
     * 初始化语音管理器
     */
    private void initializeVoiceManager() {
        Log.i(TAG, "初始化语音管理器");
        
        // 创建管理器
        voiceManager = new VoiceActivityManager(this);
        
        // 获取 MQTT 客户端（如果已启动服务）
        mqttClient = null;  // TODO: 从服务获取
        
        // 获取 AI 引擎
        aiEngine = LocalAIEngine.getInstance(this);
        
        // 初始化（如果 MQTT 和 AI 可用）
        voiceManager.initialize(mqttClient, aiEngine);
        voiceManager.setVoiceInteractionListener(this);
        
        Log.i(TAG, "语音管理器初始化完成");
    }
    
    /**
     * 检查录音权限
     */
    private void checkPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        } else {
            Log.i(TAG, "录音权限已授予");
            updateStatus("状态：权限已授予，可以点击开始");
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                          int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "录音权限已授予");
                updateStatus("状态：权限已授予，可以点击开始");
                Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Log.e(TAG, "录音权限被拒绝");
                updateStatus("状态：权限被拒绝，无法使用语音识别");
                Toast.makeText(this, "需要录音权限才能使用语音识别", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    /**
     * 开始语音识别按钮点击
     */
    private void onStartVoiceClick() {
        Log.i(TAG, "用户点击开始语音识别");
        
        if (!voiceManager.hasRecordPermission()) {
            checkPermissions();
            return;
        }
        
        boolean success = voiceManager.startVoiceInteraction();
        
        if (success) {
            updateStatus("状态：正在识别...");
            btnStartVoice.setEnabled(false);
            btnStopVoice.setEnabled(true);
            Toast.makeText(this, "开始语音识别，请说话", Toast.LENGTH_SHORT).show();
        } else {
            updateStatus("状态：启动失败");
            Toast.makeText(this, "启动语音识别失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 停止语音识别按钮点击
     */
    private void onStopVoiceClick() {
        Log.i(TAG, "用户点击停止语音识别");
        
        voiceManager.stopVoiceInteraction();
        
        updateStatus("状态：已停止");
        btnStartVoice.setEnabled(true);
        btnStopVoice.setEnabled(false);
    }
    
    /**
     * 更新状态文本（主线程）
     */
    private void updateStatus(String status) {
        runOnUiThread(() -> {
            if (tvStatus != null) {
                tvStatus.setText(status);
            }
        });
    }
    
    /**
     * 更新识别结果（主线程）
     */
    private void updateRecognizedText(String text) {
        runOnUiThread(() -> {
            if (tvRecognizedText != null) {
                tvRecognizedText.setText("识别结果：" + text);
            }
        });
    }
    
    /**
     * 更新 AI 回答（主线程）
     */
    private void updateAIResponse(String response) {
        runOnUiThread(() -> {
            if (tvAIResponse != null) {
                tvAIResponse.setText("AI 回答：" + response);
            }
        });
    }
    
    // ==================== VoiceInteractionListener 实现 ====================
    
    @Override
    public void onRecognitionStart() {
        Log.d(TAG, "识别开始");
        updateStatus("状态：正在识别... (请说话)");
    }
    
    @Override
    public void onRecognitionEnd() {
        Log.d(TAG, "识别结束");
        updateStatus("状态：识别结束");
    }
    
    @Override
    public void onTextRecognized(String text) {
        Log.i(TAG, "识别到文本：" + text);
        updateRecognizedText(text);
        Toast.makeText(this, "识别到：" + text, Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onAIResponse(String answer) {
        Log.i(TAG, "AI 回答：" + answer);
        updateAIResponse(answer);
    }
    
    @Override
    public void onTTSStart() {
        Log.d(TAG, "TTS 开始播放");
        updateStatus("状态：正在播放 AI 回答...");
    }
    
    @Override
    public void onTTSEnd() {
        Log.d(TAG, "TTS 播放结束");
        updateStatus("状态：播放完成");
        btnStartVoice.setEnabled(true);
    }
    
    @Override
    public void onError(int errorCode, String message) {
        Log.e(TAG, "错误：" + errorCode + " - " + message);
        updateStatus("状态：错误 - " + message);
        Toast.makeText(this, "错误：" + message, Toast.LENGTH_LONG).show();
        btnStartVoice.setEnabled(true);
        btnStopVoice.setEnabled(false);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        Log.i(TAG, "Activity 销毁，释放语音管理器");
        
        if (voiceManager != null) {
            voiceManager.release();
            voiceManager = null;
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        
        if (voiceManager != null && voiceManager.isRecognizing()) {
            voiceManager.stopVoiceInteraction();
        }
    }
}
