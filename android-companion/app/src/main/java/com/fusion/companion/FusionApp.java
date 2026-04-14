package com.fusion.companion;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Fusion Application - 应用入口
 * 
 * 负责在应用启动时加载 native 库
 */
public class FusionApp extends Application {
    
    private static final String TAG = "FusionApp";
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Fusion Companion 启动");
        
        // 加载 native 库（支持从 files/lib/ 动态加载）
        NativeLoader.loadAll(this);
    }
}
