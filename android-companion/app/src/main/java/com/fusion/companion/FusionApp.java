package com.fusion.companion;

import android.app.Application;
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
    }
}
