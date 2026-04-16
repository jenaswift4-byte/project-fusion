package com.fusion.companion;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

/**
 * Fusion Application - 应用入口
 *
 * Vosk Android AAR 已内置 .so，无需手动复制/加载。
 */
public class FusionApp extends Application {

    private static final String TAG = "FusionApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Fusion Companion 启动 (Vosk ASR)");

        // 启动核心服务
        try {
            Intent bridgeIntent = new Intent(this, FusionBridgeService.class);
            startService(bridgeIntent);
            Log.i(TAG, "FusionBridgeService 已启动");
        } catch (Exception e) {
            Log.e(TAG, "启动 FusionBridgeService 失败: " + e.getMessage());
        }
    }
}
