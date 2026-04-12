package com.fusion.companion.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型下载管理器
 * 
 * 负责从 HuggingFace 下载 Qwen2.5-3B INT4 量化模型，支持：
 * - 断点续传
 * - 下载进度监听
 * - 网络状态检测
 * - 多线程下载
 * - 自动重试
 * 
 * 使用示例：
 * <pre>
 * {@code
 * // 1. 创建下载器
 * ModelDownloader downloader = new ModelDownloader(context);
 * 
 * // 2. 设置下载回调
 * downloader.setDownloadCallback(new ModelDownloader.DownloadCallback() {
 *     @Override
 *     public void onProgress(long downloaded, long total, int percent) {
 *         Log.d("Download", "进度：" + percent + "%");
 *     }
 *     
 *     @Override
 *     public void onSuccess(File modelFile) {
 *         Log.d("Download", "下载完成：" + modelFile.getAbsolutePath());
 *     }
 *     
 *     @Override
 *     public void onError(Exception e) {
 *         Log.e("Download", "下载失败", e);
 *     }
 * });
 * 
 * // 3. 开始下载
 * downloader.startDownload();
 * 
 * // 4. 暂停下载
 * downloader.pauseDownload();
 * 
 * // 5. 恢复下载
 * downloader.resumeDownload();
 * 
 * // 6. 取消下载
 * downloader.cancelDownload();
 * }
 * </pre>
 */
public class ModelDownloader {
    
    private static final String TAG = "ModelDownloader";
    
    // 上下文
    private Context context;
    
    // 下载管理器
    private ExecutorService downloadExecutor;
    
    // 主线程 Handler
    private Handler mainHandler;
    
    // 下载配置
    private DownloadConfig config;
    
    // 下载状态
    private DownloadState state;
    
    // 下载进度
    private long downloadedBytes;
    private long totalBytes;
    
    // 下载文件
    private File targetFile;
    private File tempFile;
    
    // 下载任务
    private DownloadTask currentTask;
    
    // 偏好设置（用于保存下载进度）
    private SharedPreferences prefs;
    
    // 网络管理器
    private ConnectivityManager connectivityManager;
    
    // 当前网络是否可用
    private AtomicBoolean networkAvailable;
    
    // 下载回调
    private DownloadCallback callback;
    
    // HuggingFace 模型 URL（Qwen2.5-3B-Instruct-AWQ INT4）
    private static final String MODEL_URL = 
        "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-AWQ/resolve/main/qwen2.5-3b-int4.mnn";
    
    // 备用 URL（如果主 URL 失败）
    private static final String BACKUP_MODEL_URL = 
        "https://modelscope.cn/models/qwen/Qwen2.5-3B-Instruct-AWQ/resolve/master/qwen2.5-3b-int4.mnn";
    
    // 默认下载路径
    private static final String DEFAULT_DOWNLOAD_PATH = "/sdcard/models/";
    
    // 默认文件名
    private static final String DEFAULT_FILENAME = "qwen2.5-3b-int4.mnn";
    
    // 分块大小（8KB）
    private static final int BUFFER_SIZE = 8192;
    
    // 最大重试次数
    private static final int MAX_RETRY_COUNT = 3;
    
    // 重试延迟（毫秒）
    private static final long RETRY_DELAY_MS = 5000;
    
    /**
     * 下载配置类
     */
    public static class DownloadConfig {
        public String url;              // 下载 URL
        public String savePath;         // 保存路径
        public String fileName;         // 文件名
        public int maxRetry;            // 最大重试次数
        public long retryDelay;         // 重试延迟（毫秒）
        public boolean autoResume;      // 自动恢复下载
        
        public DownloadConfig() {
            this.url = MODEL_URL;
            this.savePath = DEFAULT_DOWNLOAD_PATH;
            this.fileName = DEFAULT_FILENAME;
            this.maxRetry = MAX_RETRY_COUNT;
            this.retryDelay = RETRY_DELAY_MS;
            this.autoResume = true;
        }
        
        public DownloadConfig(String url, String savePath, String fileName) {
            this();
            this.url = url;
            this.savePath = savePath;
            this.fileName = fileName;
        }
    }
    
    /**
     * 下载状态枚举
     */
    public enum DownloadState {
        IDLE,           // 空闲
        PENDING,        // 等待中
        DOWNLOADING,    // 下载中
        PAUSED,         // 已暂停
        COMPLETED,      // 已完成
        FAILED,         // 失败
        CANCELLED       // 已取消
    }
    
    /**
     * 下载回调接口
     */
    public interface DownloadCallback {
        /**
         * 下载进度更新
         * @param downloaded 已下载字节数
         * @param total 总字节数
         * @param percent 下载百分比（0-100）
         */
        void onProgress(long downloaded, long total, int percent);
        
        /**
         * 下载成功
         * @param modelFile 模型文件
         */
        void onSuccess(File modelFile);
        
        /**
         * 下载失败
         * @param e 异常
         */
        void onError(Exception e);
        
        /**
         * 下载取消
         */
        default void onCancelled() {
            Log.d(TAG, "下载已取消");
        }
        
        /**
         * 下载暂停
         */
        default void onPaused() {
            Log.d(TAG, "下载已暂停");
        }
        
        /**
         * 下载恢复
         */
        default void onResumed() {
            Log.d(TAG, "下载已恢复");
        }
    }
    
    /**
     * 私有构造函数
     */
    public ModelDownloader(Context context) {
        this(context, new DownloadConfig());
    }
    
    /**
     * 带配置的构造函数
     */
    public ModelDownloader(Context context, DownloadConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.downloadExecutor = Executors.newSingleThreadExecutor();
        this.prefs = context.getSharedPreferences("model_download", Context.MODE_PRIVATE);
        this.connectivityManager = (ConnectivityManager) 
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.networkAvailable = new AtomicBoolean(isNetworkAvailable());
        this.state = DownloadState.IDLE;
        
        // 注册网络监听
        registerNetworkCallback();
        
        Log.i(TAG, "ModelDownloader 初始化完成");
    }
    
    /**
     * 注册网络状态监听
     */
    private void registerNetworkCallback() {
        try {
            android.net.NetworkRequest networkRequest = new android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            
            ConnectivityManager.NetworkCallback networkCallback = 
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        networkAvailable.set(true);
                        Log.d(TAG, "网络可用");
                        
                        // 如果之前暂停，自动恢复下载
                        if (state == DownloadState.PAUSED && config.autoResume) {
                            Log.i(TAG, "网络恢复，自动继续下载");
                            resumeDownload();
                        }
                    }
                    
                    @Override
                    public void onLost(Network network) {
                        networkAvailable.set(false);
                        Log.d(TAG, "网络丢失");
                        
                        // 如果正在下载，自动暂停
                        if (state == DownloadState.DOWNLOADING) {
                            Log.i(TAG, "网络丢失，自动暂停下载");
                            pauseDownload();
                        }
                    }
                };
            
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
            Log.i(TAG, "网络监听已注册");
            
        } catch (Exception e) {
            Log.e(TAG, "注册网络监听失败", e);
        }
    }
    
    /**
     * 检查网络是否可用
     */
    private boolean isNetworkAvailable() {
        if (connectivityManager == null) {
            return false;
        }
        
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    
    /**
     * 设置下载回调
     */
    public void setDownloadCallback(DownloadCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 开始下载
     */
    public void startDownload() {
        startDownload(config.url);
    }
    
    /**
     * 开始下载（指定 URL）
     */
    public void startDownload(String url) {
        if (state == DownloadState.DOWNLOADING) {
            Log.w(TAG, "下载正在进行中，请勿重复启动");
            return;
        }
        
        Log.i(TAG, "开始下载：" + url);
        
        // 创建目标目录
        File saveDir = new File(config.savePath);
        if (!saveDir.exists()) {
            if (!saveDir.mkdirs()) {
                Log.e(TAG, "创建目录失败：" + config.savePath);
                if (callback != null) {
                    mainHandler.post(() -> 
                        callback.onError(new IOException("创建目录失败")));
                }
                return;
            }
        }
        
        // 设置目标文件
        targetFile = new File(saveDir, config.fileName);
        tempFile = new File(saveDir, config.fileName + ".tmp");
        
        // 检查是否已下载完成
        if (targetFile.exists()) {
            Log.i(TAG, "模型文件已存在，跳过下载：" + targetFile.getAbsolutePath());
            if (callback != null) {
                mainHandler.post(() -> callback.onSuccess(targetFile));
            }
            state = DownloadState.COMPLETED;
            return;
        }
        
        // 从断点续传
        downloadedBytes = getDownloadProgress();
        Log.d(TAG, "已下载字节数：" + downloadedBytes);
        
        // 创建下载任务
        currentTask = new DownloadTask(url, downloadedBytes);
        
        // 提交下载任务
        state = DownloadState.PENDING;
        downloadExecutor.submit(currentTask);
    }
    
    /**
     * 暂停下载
     */
    public void pauseDownload() {
        if (state != DownloadState.DOWNLOADING) {
            Log.w(TAG, "下载未进行，无法暂停");
            return;
        }
        
        Log.i(TAG, "暂停下载");
        
        if (currentTask != null) {
            currentTask.pause();
        }
        
        state = DownloadState.PAUSED;
        
        if (callback != null) {
            mainHandler.post(() -> callback.onPaused());
        }
    }
    
    /**
     * 恢复下载
     */
    public void resumeDownload() {
        if (state != DownloadState.PAUSED) {
            Log.w(TAG, "下载未暂停，无法恢复");
            return;
        }
        
        Log.i(TAG, "恢复下载");
        
        if (currentTask != null) {
            currentTask.resume();
        }
        
        state = DownloadState.DOWNLOADING;
        
        if (callback != null) {
            mainHandler.post(() -> callback.onResumed());
        }
    }
    
    /**
     * 取消下载
     */
    public void cancelDownload() {
        Log.i(TAG, "取消下载");
        
        if (currentTask != null) {
            currentTask.cancel();
            currentTask = null;
        }
        
        state = DownloadState.CANCELLED;
        
        // 删除临时文件
        if (tempFile != null && tempFile.exists()) {
            if (tempFile.delete()) {
                Log.d(TAG, "临时文件已删除");
            }
        }
        
        // 清除下载进度
        clearDownloadProgress();
        
        if (callback != null) {
            mainHandler.post(() -> callback.onCancelled());
        }
    }
    
    /**
     * 获取下载状态
     */
    public DownloadState getState() {
        return state;
    }
    
    /**
     * 获取下载进度（字节数）
     */
    public long getDownloadedBytes() {
        return downloadedBytes;
    }
    
    /**
     * 获取总字节数
     */
    public long getTotalBytes() {
        return totalBytes;
    }
    
    /**
     * 获取下载百分比
     */
    public int getProgressPercent() {
        if (totalBytes == 0) {
            return 0;
        }
        return (int) ((downloadedBytes * 100) / totalBytes);
    }
    
    /**
     * 获取目标文件
     */
    public File getTargetFile() {
        return targetFile;
    }
    
    /**
     * 检查是否正在下载
     */
    public boolean isDownloading() {
        return state == DownloadState.DOWNLOADING;
    }
    
    /**
     * 检查下载是否完成
     */
    public boolean isDownloadComplete() {
        return state == DownloadState.COMPLETED;
    }
    
    /**
     * 保存下载进度
     */
    private void saveDownloadProgress(long bytes) {
        prefs.edit().putLong("download_progress_" + config.fileName, bytes).apply();
    }
    
    /**
     * 获取已保存的下载进度
     */
    private long getDownloadProgress() {
        return prefs.getLong("download_progress_" + config.fileName, 0);
    }
    
    /**
     * 清除下载进度
     */
    private void clearDownloadProgress() {
        prefs.edit().remove("download_progress_" + config.fileName).apply();
    }
    
    /**
     * 下载任务实现
     */
    private class DownloadTask implements Runnable {
        private String url;
        private long startOffset;
        private volatile boolean cancelled;
        private volatile boolean paused;
        private int retryCount;
        private HttpURLConnection connection;
        
        public DownloadTask(String url, long startOffset) {
            this.url = url;
            this.startOffset = startOffset;
            this.cancelled = false;
            this.paused = false;
            this.retryCount = 0;
        }
        
        @Override
        public void run() {
            downloadWithRetry();
        }
        
        /**
         * 带重试的下载
         */
        private void downloadWithRetry() {
            while (!cancelled && retryCount < config.maxRetry) {
                try {
                    downloadFile();
                    return; // 下载成功，退出重试循环
                    
                } catch (Exception e) {
                    retryCount++;
                    Log.e(TAG, "下载失败，第 " + retryCount + " 次重试", e);
                    
                    if (retryCount >= config.maxRetry) {
                        Log.e(TAG, "达到最大重试次数，下载失败");
                        if (callback != null) {
                            mainHandler.post(() -> callback.onError(e));
                        }
                        state = DownloadState.FAILED;
                        
                        // 尝试备用 URL
                        if (!url.equals(BACKUP_MODEL_URL)) {
                            Log.i(TAG, "切换到备用 URL 重试");
                            url = BACKUP_MODEL_URL;
                            retryCount = 0;
                            continue;
                        }
                    } else {
                        // 等待延迟后重试
                        try {
                            Thread.sleep(config.retryDelay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            cancelled = true;
                        }
                    }
                }
            }
        }
        
        /**
         * 下载文件
         */
        private void downloadFile() throws IOException {
            state = DownloadState.DOWNLOADING;
            
            // 创建 HTTP 连接
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            
            // 支持断点续传
            if (startOffset > 0) {
                connection.setRequestProperty("Range", "bytes=" + startOffset + "-");
                Log.d(TAG, "断点续传，起始位置：" + startOffset);
            }
            
            // 连接服务器
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "响应码：" + responseCode);
            
            // 检查响应码（200 表示完整下载，206 表示部分下载）
            if (responseCode != HttpURLConnection.HTTP_OK && 
                responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP 错误：" + responseCode);
            }
            
            // 获取文件大小
            totalBytes = connection.getContentLengthLong();
            if (totalBytes <= 0) {
                Log.w(TAG, "无法获取文件大小");
            }
            
            Log.i(TAG, "文件大小：" + totalBytes + " 字节 (" + 
                String.format("%.2f", totalBytes / 1024.0 / 1024.0) + " MB)");
            
            // 创建输入流
            InputStream input = connection.getInputStream();
            
            // 创建输出流（支持追加）
            RandomAccessFile output = new RandomAccessFile(tempFile, "rw");
            output.seek(startOffset);
            
            // 下载缓冲区
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            long totalDownloaded = startOffset;
            long lastProgressTime = System.currentTimeMillis();
            
            // 开始下载
            while (!cancelled && !paused && 
                   (count = input.read(buffer)) != -1) {
                
                // 写入文件
                output.write(buffer, 0, count);
                totalDownloaded += count;
                downloadedBytes = totalDownloaded;
                
                // 定期保存进度（每 500ms）
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastProgressTime > 500) {
                    saveDownloadProgress(totalDownloaded);
                    reportProgress(totalDownloaded, totalBytes);
                    lastProgressTime = currentTime;
                }
            }
            
            // 关闭流
            output.close();
            input.close();
            connection.disconnect();
            
            // 检查是否被取消
            if (cancelled) {
                Log.i(TAG, "下载被取消");
                return;
            }
            
            // 检查是否被暂停
            if (paused) {
                Log.i(TAG, "下载被暂停");
                return;
            }
            
            // 检查下载是否完整
            if (totalBytes > 0 && totalDownloaded < totalBytes) {
                throw new IOException("下载不完整：已下载 " + totalDownloaded + 
                    " / 总计 " + totalBytes);
            }
            
            // 重命名临时文件为最终文件
            if (tempFile.renameTo(targetFile)) {
                Log.i(TAG, "下载完成：" + targetFile.getAbsolutePath());
                
                // 清除进度记录
                clearDownloadProgress();
                
                // 更新状态
                state = DownloadState.COMPLETED;
                
                // 通知回调
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess(targetFile));
                }
                
            } else {
                throw new IOException("文件重命名失败");
            }
        }
        
        /**
         * 报告进度
         */
        private void reportProgress(long downloaded, long total) {
            if (callback != null) {
                int percent = total > 0 ? (int) ((downloaded * 100) / total) : 0;
                
                mainHandler.post(() -> {
                    try {
                        callback.onProgress(downloaded, total, percent);
                    } catch (Exception e) {
                        Log.e(TAG, "回调进度失败", e);
                    }
                });
            }
        }
        
        /**
         * 暂停下载
         */
        public void pause() {
            paused = true;
        }
        
        /**
         * 恢复下载
         */
        public void resume() {
            paused = false;
            // 继续执行 run 方法
        }
        
        /**
         * 取消下载
         */
        public void cancel() {
            cancelled = true;
            paused = false;
            
            // 关闭连接
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        Log.i(TAG, "释放下载器资源");
        
        cancelDownload();
        
        if (downloadExecutor != null && !downloadExecutor.isShutdown()) {
            downloadExecutor.shutdownNow();
        }
        
        downloadExecutor = null;
        callback = null;
        currentTask = null;
    }
}
