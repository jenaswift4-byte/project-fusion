package com.fusion.companion.voice;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;

import java.util.ArrayList;

/**
 * 透明 Activity，用于调用系统语音识别 Intent
 * 在 Service 无法直接绑定 RecognitionService 时（如 MIUI 限制），
 * 通过 Activity 方式使用 startActivityForResult 调用
 */
public class SpeechRecognitionActivity extends Activity {
    
    private static final String TAG = "SpeechRecogActivity";
    private static final int SPEECH_REQUEST_CODE = 100;
    
    private static SpeechResultCallback callback;
    
    /**
     * 设置回调（在启动 Activity 前调用）
     */
    public static void setCallback(SpeechResultCallback cb) {
        callback = cb;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.i(TAG, "启动语音识别 Activity");
        
        // 直接启动语音识别 Intent
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话...");
        
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "无法启动语音识别 Activity: " + e.getMessage());
            if (callback != null) {
                callback.onError(-1, "无法启动语音识别: " + e.getMessage());
            }
            finish();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == SPEECH_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS);
                
                if (results != null && !results.isEmpty()) {
                    String text = results.get(0);
                    Log.i(TAG, "语音识别结果: " + text);
                    if (callback != null) {
                        callback.onResult(text);
                    }
                } else {
                    Log.w(TAG, "语音识别无结果");
                    if (callback != null) {
                        callback.onError(-1, "无识别结果");
                    }
                }
            } else {
                Log.w(TAG, "语音识别取消或失败: resultCode=" + resultCode);
                if (callback != null) {
                    callback.onError(resultCode, "识别取消或失败");
                }
            }
        }
        
        // 立即结束 Activity
        finish();
    }
    
    /**
     * 语音识别结果回调
     */
    public interface SpeechResultCallback {
        void onResult(String text);
        void onError(int errorCode, String message);
    }
}
