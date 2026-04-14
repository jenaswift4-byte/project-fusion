package com.fusion.companion;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Fusion Application - 应用入口
 * 
 * 负责在应用启动时加载 native 库，并从 /data/local/tmp/sherpa-models/ 复制 .so 到应用私有目录
 */
public class FusionApp extends Application {
    
    private static final String TAG = "FusionApp";
    private static final String TMP_SO_PATH = "/data/local/tmp/sherpa-models/";
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Fusion Companion 启动");
        
        // 复制 .so 文件到应用私有目录（如果还没有）
        copyNativeLibsIfNeeded();
        
        // 加载 native 库（支持从 files/lib/ 动态加载）
        NativeLoader.loadAll(this);
    }
    
    /**
     * 从临时目录复制 .so 文件到应用私有目录
     */
    private void copyNativeLibsIfNeeded() {
        File libDir = new File(getFilesDir(), "lib");
        if (!libDir.exists()) {
            libDir.mkdirs();
        }
        
        File tmpDir = new File(TMP_SO_PATH);
        if (!tmpDir.exists() || !tmpDir.isDirectory()) {
            Log.w(TAG, "临时 .so 目录不存在: " + TMP_SO_PATH);
            return;
        }
        
        File[] soFiles = tmpDir.listFiles((dir, name) -> name.endsWith(".so"));
        if (soFiles == null || soFiles.length == 0) {
            Log.w(TAG, "没有找到 .so 文件");
            return;
        }
        
        for (File soFile : soFiles) {
            File destFile = new File(libDir, soFile.getName());
            if (destFile.exists() && destFile.length() == soFile.length()) {
                continue;
            }
            
            try {
                copyFile(soFile, destFile);
                Log.i(TAG, "已复制 .so: " + soFile.getName() + " -> " + destFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "复制 .so 失败: " + soFile.getName() + ": " + e.getMessage());
            }
        }
        
        Log.i(TAG, ".so 文件复制完成，共 " + libDir.listFiles((d, n) -> n.endsWith(".so")).length + " 个");
    }
    
    private void copyFile(File src, File dest) throws Exception {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }
}
