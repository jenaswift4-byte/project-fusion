package com.fusion.companion.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模型管理器 — 管理 AI 模型文件的下载和版本控制
 *
 * 管理的模型:
 *   1. Sherpa-onnx ASR 模型 (流式语音识别)
 *   2. Vosk 说话人识别模型 (声纹比对)
 *   3. Qwen 3.5 2.1B GGUF (每日摘要 LLM)
 *
 * 下载源: Hugging Face
 * 存储位置: app 私有目录 models/
 *
 * @author Fusion
 * @version 1.0
 */
public class ModelManager {

    private static final String TAG = "ModelManager";

    private static final String MODELS_DIR = "models";
    private static final String PREFS_NAME = "model_manager_prefs";

    // 模型定义
    private static final String[][] MODEL_DEFS = {
        // {id, 名称, HuggingFace URL, 大小(bytes), 版本}
        {"sherpa_asr", "Sherpa-onnx ASR (中文流式)",
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main/model.onnx",
            "14000000", "1"},
        {"vosk_speaker", "Vosk 说话人模型",
            "https://huggingface.co/vosk-models/vosk-model-spk/resolve/main/model.onnx",
            "5000000", "1"},
        {"qwen_2.1b_q4", "Qwen 3.5 2.1B (4-bit GGUF)",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "1000000000", "1"},
    };

    private final Context appContext;
    private final SharedPreferences prefs;
    private final ExecutorService downloadExecutor;

    // 下载进度回调
    public interface DownloadCallback {
        void onProgress(String modelId, int percent);
        void onComplete(String modelId, boolean success, String error);
    }

    private DownloadCallback callback;

    public ModelManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.downloadExecutor = Executors.newFixedThreadPool(2);
    }

    public void setCallback(DownloadCallback callback) {
        this.callback = callback;
    }

    // ==================== 模型检查 ====================

    /**
     * 检查模型是否已下载
     */
    public boolean isModelDownloaded(String modelId) {
        File modelFile = getModelFile(modelId);
        if (modelFile == null || !modelFile.exists()) return false;

        // 检查文件大小是否合理 (> 1KB)
        return modelFile.length() > 1024;
    }

    /**
     * 获取所有模型的下载状态
     */
    public ConcurrentHashMap<String, Boolean> getAllModelStatus() {
        ConcurrentHashMap<String, Boolean> status = new ConcurrentHashMap<>();
        for (String[] def : MODEL_DEFS) {
            status.put(def[0], isModelDownloaded(def[0]));
        }
        return status;
    }

    /**
     * 获取模型文件路径
     */
    public File getModelFile(String modelId) {
        File dir = new File(appContext.getFilesDir(), MODELS_DIR);
        if (!dir.exists()) dir.mkdirs();

        // 根据模型类型确定文件名
        String fileName;
        switch (modelId) {
            case "sherpa_asr":
                fileName = "sherpa_asr.onnx";
                break;
            case "vosk_speaker":
                fileName = "vosk_speaker.onnx";
                break;
            case "qwen_2.1b_q4":
                fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf";
                break;
            default:
                return null;
        }

        return new File(dir, fileName);
    }

    // ==================== 模型下载 ====================

    /**
     * 下载指定模型 (异步)
     */
    public void downloadModel(String modelId) {
        String[] modelDef = findModelDef(modelId);
        if (modelDef == null) {
            Log.e(TAG, "未知模型: " + modelId);
            return;
        }

        downloadExecutor.execute(() -> {
            String url = modelDef[2];
            String name = modelDef[1];
            long expectedSize = Long.parseLong(modelDef[3]);

            Log.i(TAG, "开始下载模型: " + name);
            Log.i(TAG, "URL: " + url);
            Log.i(TAG, "预期大小: " + (expectedSize / 1024 / 1024) + "MB");

            File targetFile = getModelFile(modelId);
            File tempFile = new File(targetFile.getParent(), targetFile.getName() + ".downloading");

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "FusionCompanion/1.0");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new Exception("HTTP " + responseCode);
                }

                long contentLength = conn.getContentLengthLong();
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(tempFile);

                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;
                int lastPercent = 0;

                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    int percent = contentLength > 0
                        ? (int) (totalRead * 100 / contentLength)
                        : (int) (totalRead * 100 / expectedSize);

                    if (percent != lastPercent && percent % 5 == 0) {
                        lastPercent = percent;
                        Log.d(TAG, "下载进度: " + percent + "% (" + totalRead / 1024 / 1024 + "MB)");
                        if (callback != null) {
                            callback.onProgress(modelId, percent);
                        }
                    }
                }

                fos.close();
                is.close();
                conn.disconnect();

                // 下载完成, 重命名
                if (tempFile.renameTo(targetFile)) {
                    Log.i(TAG, "模型下载完成: " + name + " (" + targetFile.length() / 1024 / 1024 + "MB)");

                    // 记录版本
                    prefs.edit().putString("model_version_" + modelId, modelDef[4]).apply();

                    if (callback != null) {
                        callback.onComplete(modelId, true, null);
                    }
                } else {
                    throw new Exception("重命名临时文件失败");
                }

            } catch (Exception e) {
                Log.e(TAG, "模型下载失败: " + e.getMessage());

                // 清理临时文件
                if (tempFile.exists()) tempFile.delete();

                if (callback != null) {
                    callback.onComplete(modelId, false, e.getMessage());
                }
            }
        });
    }

    /**
     * 下载所有缺失的模型 (首次启动时调用)
     */
    public void downloadMissingModels() {
        for (String[] def : MODEL_DEFS) {
            if (!isModelDownloaded(def[0])) {
                Log.i(TAG, "模型缺失, 开始下载: " + def[1]);
                downloadModel(def[0]);
            }
        }
    }

    /**
     * 删除指定模型文件
     */
    public boolean deleteModel(String modelId) {
        File file = getModelFile(modelId);
        if (file != null && file.exists()) {
            boolean deleted = file.delete();
            Log.i(TAG, "模型删除: " + modelId + " → " + deleted);
            return deleted;
        }
        return false;
    }

    // ==================== NPU 检测 ====================

    /**
     * 检测设备是否支持 NPU 推理
     * 用于决定 llama.cpp 是否启用 GPU 加速
     */
    public boolean isNpuSupported() {
        // TODO: 检测 NNAPI / Hexagon / APU
        // 小米8 (骁龙845) 支持 NNAPI 但性能有限
        // 当前保守返回 false, 后续根据设备型号判断
        return false;
    }

    /**
     * 获取设备 AI 能力描述
     */
    public String getDeviceAiCapability() {
        StringBuilder sb = new StringBuilder();
        sb.append("CPU: ").append(android.os.Build.SOC_MODEL).append("\n");
        sb.append("RAM: ").append(getTotalRamMB()).append("MB\n");
        sb.append("NPU: ").append(isNpuSupported() ? "支持" : "不支持").append("\n");
        sb.append("模型状态:\n");
        for (String[] def : MODEL_DEFS) {
            sb.append("  ").append(def[1]).append(": ")
              .append(isModelDownloaded(def[0]) ? "已下载" : "未下载").append("\n");
        }
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    private String[] findModelDef(String modelId) {
        for (String[] def : MODEL_DEFS) {
            if (def[0].equals(modelId)) return def;
        }
        return null;
    }

    private long getTotalRamMB() {
        android.app.ActivityManager am = (android.app.ActivityManager)
            appContext.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / 1024 / 1024;
        }
        return 0;
    }
}
