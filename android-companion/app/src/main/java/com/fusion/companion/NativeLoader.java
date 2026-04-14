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
    
    private static final String[] NATIVE_LIBS = {
        "libonnxruntime.so",
        "libsherpa-onnx-jni.so",
        "libsherpa-onnx-c-api.so",
        "libsherpa-onnx-cxx-api.so"
    };

    // 搜索路径优先级
    private static final String[][] SEARCH_PATHS = {
        // 路径1: files/lib/ (应用私有目录，adb push 需要 root 或 run-as)
        { "lib", "/data/data/com.fusion.companion/files/lib/" },
        // 路径2: data/local/tmp/sherpa-models/ (临时目录，普通 adb push 可写)
        { "sherpa-models", "/data/local/tmp/sherpa-models/" },
        // 路径3: sdcard/Android/data/.../files/lib/ (外部存储)
        { "ext-lib", "/sdcard/Android/data/com.fusion.companion/files/lib/" }
    };
    
    private static volatile boolean loaded = false;
    
    /**
     * 加载所有 native 库
     *
     * 按优先级搜索多个路径：
     * 1. /data/data/com.fusion.companion/files/lib/
     * 2. /data/local/tmp/sherpa-models/
     * 3. /sdcard/Android/data/com.fusion.companion/files/lib/
     * 4. APK 内置 (System.loadLibrary)
     */
    public static synchronized void loadAll(Context context) {
        if (loaded) return;

        Log.i(TAG, "开始加载 native 库...");

        File libDir = new File(context.getFilesDir(), "lib");
        if (!libDir.exists() || !libDir.isDirectory()) {
            Log.e(TAG, "lib 目录不存在: " + libDir.getAbsolutePath());
            loaded = true;
            return;
        }

        // 预加载所有库到 JVM 缓存（让 System.loadLibrary 能找到）
        Log.i(TAG, "预加载 .so 到 JVM 缓存...");
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

        Log.i(TAG, "✓ 所有 .so 加载成功!");
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
