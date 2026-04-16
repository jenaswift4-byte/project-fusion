package com.fusion.companion;

import android.content.Context;

/**
 * Native 库加载器 (已弃用)
 *
 * Vosk Android AAR 已包含所有 native 库 (.so)，无需手动加载。
 * 此文件保留用于历史参考。
 */
public class NativeLoader {

    private static volatile boolean loaded = false;

    public static synchronized void loadAll(Context context) {
        if (loaded) return;
        // Vosk AAR 内置 .so，System.loadLibrary 自动加载
        loaded = true;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static void reset() {
        loaded = false;
    }
}
