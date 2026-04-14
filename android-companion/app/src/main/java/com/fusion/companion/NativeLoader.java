package com.fusion.companion;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Native 库加载器
 * 
 * 支持从 files/lib/ 目录动态加载 .so 文件（当 APK 不包含 .so 时）
 * 
 * 使用方式：
 * 1. 从 PC 推送 .so 文件到 /data/data/com.fusion.companion/files/lib/
 * 2. 应用启动时自动加载
 */
public class NativeLoader {
    
    private static final String TAG = "NativeLoader";
    
    // 需要加载的 .so 列表（按依赖顺序）
    private static final String[] NATIVE_LIBS = {
        "libonnxruntime.so",
        "libsherpa_onnx_jni.so"
    };
    
    private static volatile boolean loaded = false;
    
    /**
     * 加载所有 native 库
     * 
     * 优先从 files/lib/ 加载（支持动态更新），
     * 如果没有则尝试从 APK 加载（System.loadLibrary）
     */
    public static synchronized void loadAll(Context context) {
        if (loaded) return;
        
        File libDir = new File(context.getFilesDir(), "lib");
        
        if (libDir.exists() && libDir.isDirectory()) {
            // files/lib/ 存在，从文件加载
            Log.i(TAG, "从 files/lib/ 加载 .so: " + libDir.getAbsolutePath());
            
            for (String lib : NATIVE_LIBS) {
                File libFile = new File(libDir, lib);
                if (libFile.exists()) {
                    try {
                        System.load(libFile.getAbsolutePath());
                        Log.i(TAG, "✓ 已加载: " + lib + " (" + (libFile.length() / 1024) + " KB)");
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "✗ 加载失败: " + lib + " - " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, "⚠ 文件不存在: " + lib);
                }
            }
        } else {
            // 没有 files/lib/，尝试从 APK 加载
            Log.i(TAG, "files/lib/ 不存在，尝试从 APK 加载");
            try {
                System.loadLibrary("sherpa_onnx_jni");
                Log.i(TAG, "✓ 从 APK 加载 sherpa_onnx_jni 成功");
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "✗ APK 中也没有 .so: " + e.getMessage());
                Log.e(TAG, "请执行: adb push *.so /data/data/com.fusion.companion/files/lib/");
            }
        }
        
        loaded = true;
    }
    
    /**
     * 检查 native 库是否已加载
     */
    public static boolean isLoaded() {
        return loaded;
    }
    
    /**
     * 重置加载状态（用于测试）
     */
    public static void reset() {
        loaded = false;
    }
}
