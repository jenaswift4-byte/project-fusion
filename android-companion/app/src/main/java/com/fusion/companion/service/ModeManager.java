package com.fusion.companion.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fusion.companion.ai.LocalAIEngine;
import com.fusion.companion.ai.ModelDownloader;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 双模式管理器
 * 
 * 负责管理在线模式与离线模式的切换：
 * - 在线模式：PC 在家，有网络，使用 PC 的 Qwen3.5-7B，启用云端功能
 * - 离线模式：PC 不在，无网络，使用本地 Qwen2.5-3B，仅本地功能
 * 
 * 特性：
 * - 自动监听 PC 在线状态
 * - 支持手动切换
 * - 防抖动处理（避免频繁切换）
 * - 延迟确认机制（PC 离线等待 30 秒，PC 上线等待 10 秒）
 * - 发布模式切换通知
 * - 与 AI 引擎、媒体播放集成
 */
public class ModeManager {
    
    private static final String TAG = "ModeManager";
    
    // 模式定义
    public enum Mode {
        ONLINE,   // 在线模式 - 使用 PC 的 Qwen3.5-7B
        OFFLINE   // 离线模式 - 使用本地 Qwen2.5-3B
    }
    
    // 切换原因
    public enum SwitchReason {
        PC_OFFLINE,      // PC 离线
        PC_ONLINE,       // PC 上线
        MANUAL_SWITCH,   // 手动切换
        NETWORK_LOST,    // 网络丢失
        NETWORK_RESTORED // 网络恢复
    }
    
    // 单例实例
    private static ModeManager instance;
    
    // 上下文
    private Context context;
    
    // 当前模式
    private Mode currentMode;
    
    // 上一次模式
    private Mode previousMode;
    
    // PC 在线状态
    private boolean pcOnline;
    
    // 网络管理器
    private ConnectivityManager connectivityManager;
    
    // 网络回调（用于监听网络变化）
    private ConnectivityManager.NetworkCallback networkCallback;
    
    // 防抖动 Handler
    private Handler mainHandler;
    
    // 延迟确认 Runnable
    private Runnable confirmSwitchRunnable;
    
    // 延迟确认时间（毫秒）
    private static final long PC_OFFLINE_DELAY = 30000;  // PC 离线等待 30 秒
    private static final long PC_ONLINE_DELAY = 10000;   // PC 上线等待 10 秒
    
    // 偏好设置
    private SharedPreferences prefs;
    
    // 模式切换监听器列表
    private List<OnModeSwitchListener> listeners;
    
    // AI 引擎管理器（用于加载/卸载本地 AI）
    private LocalAIManager localAIManager;
    
    // 媒体库管理器（用于切换音乐库）
    private MediaLibraryManager mediaLibraryManager;
    
    // 是否正在切换中
    private boolean isSwitching;
    
    /**
     * 模式切换监听器接口
     */
    public interface OnModeSwitchListener {
        /**
         * 当模式切换时调用
         * @param newMode 新模式
         * @param previousMode 上一个模式
         * @param reason 切换原因
         * @param timestamp 切换时间戳
         */
        void onModeSwitch(Mode newMode, Mode previousMode, SwitchReason reason, long timestamp);
    }
    
    /**
     * 私有构造函数（单例模式）
     */
    private ModeManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.prefs = context.getSharedPreferences("fusion_mode", Context.MODE_PRIVATE);
        this.listeners = new ArrayList<>();
        this.isSwitching = false;
        
        // 从偏好设置中读取上次保存的模式
        String savedMode = prefs.getString("current_mode", Mode.ONLINE.name());
        this.currentMode = Mode.valueOf(savedMode);
        this.previousMode = this.currentMode;
        
        // 初始化 PC 在线状态（默认在线）
        this.pcOnline = true;
        
        // 获取网络管理器
        this.connectivityManager = (ConnectivityManager) 
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        // 初始化本地 AI 管理器
        this.localAIManager = new LocalAIManager(context);
        
        // 初始化媒体库管理器
        this.mediaLibraryManager = new MediaLibraryManager(context);
        
        Log.i(TAG, "ModeManager 初始化完成 - 当前模式：" + currentMode);
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized ModeManager getInstance(Context context) {
        if (instance == null) {
            instance = new ModeManager(context);
        }
        return instance;
    }
    
    /**
     * 启动模式管理器
     * 开始监听网络和 PC 状态
     */
    public void start() {
        Log.i(TAG, "ModeManager 启动");
        
        // 注册网络监听
        registerNetworkCallback();
        
        // 检测初始 PC 在线状态
        detectPCOnlineStatus();
        
        // 根据当前模式初始化服务
        initializeCurrentMode();
    }
    
    /**
     * 停止模式管理器
     */
    public void stop() {
        Log.i(TAG, "ModeManager 停止");
        
        // 取消网络监听
        unregisterNetworkCallback();
        
        // 取消待处理的切换任务
        cancelPendingSwitch();
        
        // 停止本地 AI
        if (localAIManager != null) {
            localAIManager.stop();
        }
    }
    
    /**
     * 注册网络状态监听
     */
    private void registerNetworkCallback() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "网络可用");
                onNetworkChanged(true);
            }
            
            @Override
            public void onLost(Network network) {
                Log.d(TAG, "网络丢失");
                onNetworkChanged(false);
            }
            
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                // 网络能力变化时重新检测
                boolean hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET);
                Log.d(TAG, "网络能力变化 - 有互联网：" + hasInternet);
                onNetworkChanged(hasInternet);
            }
        };
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
        Log.i(TAG, "网络监听已注册");
    }
    
    /**
     * 取消网络状态监听
     */
    private void unregisterNetworkCallback() {
        if (networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
            Log.i(TAG, "网络监听已取消");
        }
    }
    
    /**
     * 网络状态变化处理
     */
    private void onNetworkChanged(boolean hasNetwork) {
        Log.i(TAG, "网络状态变化：" + (hasNetwork ? "有网络" : "无网络"));
        
        if (hasNetwork) {
            // 网络恢复，检测 PC 是否在线
            detectPCOnlineStatus();
        } else {
            // 网络丢失，可能进入离线模式
            onNetworkLost();
        }
    }
    
    /**
     * 检测 PC 在线状态
     * 通过 WebSocket 连接状态判断 PC 是否在家
     */
    private void detectPCOnlineStatus() {
        boolean newPcOnline = isPCOnline();
        Log.d(TAG, "PC 在线状态检测：" + (newPcOnline ? "在线" : "离线"));
        
        if (newPcOnline != pcOnline) {
            pcOnline = newPcOnline;
            
            if (pcOnline) {
                onPCOnline();
            } else {
                onPCOffline();
            }
        }
    }
    
    /**
     * 判断 PC 是否在线
     * 通过检查 WebSocket 服务器连接状态
     */
    private boolean isPCOnline() {
        // 检查 WebSocket 服务器是否有客户端连接
        FusionWebSocketServer wsServer = FusionBridgeService.getWebSocketServer();
        if (wsServer != null) {
            // 如果有客户端连接，认为 PC 在线
            return wsServer.getClientCount() > 0;
        }
        return false;
    }
    
    /**
     * PC 上线处理
     */
    private void onPCOnline() {
        Log.i(TAG, "PC 上线，等待 " + (PC_ONLINE_DELAY / 1000) + " 秒确认后切换模式");
        
        // 取消之前的切换任务
        cancelPendingSwitch();
        
        // 延迟确认（防止网络抖动）
        confirmSwitchRunnable = () -> {
            Log.i(TAG, "PC 上线确认完成，准备切换到在线模式");
            if (currentMode != Mode.ONLINE) {
                switchToMode(Mode.ONLINE, SwitchReason.PC_ONLINE);
            }
        };
        
        mainHandler.postDelayed(confirmSwitchRunnable, PC_ONLINE_DELAY);
    }
    
    /**
     * PC 离线处理
     */
    private void onPCOffline() {
        Log.i(TAG, "PC 离线，等待 " + (PC_OFFLINE_DELAY / 1000) + " 秒确认后切换模式");
        
        // 取消之前的切换任务
        cancelPendingSwitch();
        
        // 延迟确认（防止网络抖动）
        confirmSwitchRunnable = () -> {
            Log.i(TAG, "PC 离线确认完成，准备切换到离线模式");
            if (currentMode != Mode.OFFLINE) {
                switchToMode(Mode.OFFLINE, SwitchReason.PC_OFFLINE);
            }
        };
        
        mainHandler.postDelayed(confirmSwitchRunnable, PC_OFFLINE_DELAY);
    }
    
    /**
     * 网络丢失处理
     */
    private void onNetworkLost() {
        Log.i(TAG, "网络丢失，准备切换到离线模式");
        cancelPendingSwitch();
        
        // 网络丢失时立即切换（不等待）
        if (currentMode != Mode.OFFLINE) {
            switchToMode(Mode.OFFLINE, SwitchReason.NETWORK_LOST);
        }
    }
    
    /**
     * 取消待处理的切换任务
     */
    private void cancelPendingSwitch() {
        if (confirmSwitchRunnable != null) {
            mainHandler.removeCallbacks(confirmSwitchRunnable);
            confirmSwitchRunnable = null;
            Log.d(TAG, "已取消待处理的切换任务");
        }
    }
    
    /**
     * 切换到指定模式
     */
    private void switchToMode(Mode newMode, SwitchReason reason) {
        if (isSwitching) {
            Log.w(TAG, "正在切换中，忽略本次请求");
            return;
        }
        
        if (newMode == currentMode) {
            Log.d(TAG, "已经是 " + newMode + " 模式，无需切换");
            return;
        }
        
        isSwitching = true;
        previousMode = currentMode;
        
        Log.i(TAG, "开始切换模式：" + previousMode + " -> " + newMode + " (原因：" + reason + ")");
        
        // 执行模式切换
        try {
            // 1. 通知监听器（预切换）
            notifyListenersPreSwitch(newMode, previousMode, reason);
            
            // 2. 根据新模式配置服务
            configureMode(newMode);
            
            // 3. 更新当前模式
            currentMode = newMode;
            
            // 4. 保存到偏好设置
            saveMode();
            
            // 5. 发布模式切换通知
            publishModeSwitchNotification(newMode, previousMode, reason);
            
            // 6. 通知监听器（后切换）
            notifyListenersPostSwitch(newMode, previousMode, reason);
            
            Log.i(TAG, "模式切换完成：" + newMode);
            
        } catch (Exception e) {
            Log.e(TAG, "模式切换失败", e);
            // 回滚到之前的模式
            currentMode = previousMode;
        } finally {
            isSwitching = false;
        }
    }
    
    /**
     * 配置指定模式的服务
     */
    private void configureMode(Mode mode) {
        Log.d(TAG, "配置模式：" + mode);
        
        if (mode == Mode.OFFLINE) {
            // 离线模式：启动本地 AI，使用本地音乐库
            localAIManager.start();
            mediaLibraryManager.useLocalLibrary();
            
        } else if (mode == Mode.ONLINE) {
            // 在线模式：停止本地 AI，使用 PC 音乐库
            localAIManager.stop();
            mediaLibraryManager.usePCLibrary();
        }
    }
    
    /**
     * 初始化当前模式的服务
     */
    private void initializeCurrentMode() {
        Log.d(TAG, "初始化当前模式：" + currentMode);
        configureMode(currentMode);
    }
    
    /**
     * 保存模式到偏好设置
     */
    private void saveMode() {
        prefs.edit().putString("current_mode", currentMode.name()).apply();
        Log.d(TAG, "模式已保存：" + currentMode);
    }
    
    /**
     * 发布模式切换通知（MQTT 消息）
     */
    private void publishModeSwitchNotification(Mode newMode, Mode previousMode, SwitchReason reason) {
        try {
            JSONObject message = new JSONObject();
            message.put("mode", newMode.name().toLowerCase());
            message.put("reason", reason.name().toLowerCase());
            message.put("timestamp", System.currentTimeMillis() / 1000);
            message.put("previous_mode", previousMode.name().toLowerCase());
            
            String messageStr = message.toString();
            Log.i(TAG, "发布模式切换通知：" + messageStr);
            
            // 通过 MQTT 发布到 fusion/mode 主题
            // 注意：这里需要获取 MQTTBrokerService 实例
            // 简化实现，实际应该通过服务绑定获取
            // MQTTBrokerService broker = ...;
            // broker.publish("fusion/mode", messageStr);
            
            // TODO: 实现 MQTT 发布
            
        } catch (Exception e) {
            Log.e(TAG, "发布模式切换通知失败", e);
        }
    }
    
    /**
     * 通知监听器（预切换）
     */
    private void notifyListenersPreSwitch(Mode newMode, Mode previousMode, SwitchReason reason) {
        for (OnModeSwitchListener listener : listeners) {
            try {
                listener.onModeSwitch(newMode, previousMode, reason, System.currentTimeMillis());
            } catch (Exception e) {
                Log.e(TAG, "通知监听器失败", e);
            }
        }
    }
    
    /**
     * 通知监听器（后切换）
     */
    private void notifyListenersPostSwitch(Mode newMode, Mode previousMode, SwitchReason reason) {
        // 可以在这里添加后切换通知逻辑
    }
    
    /**
     * 添加模式切换监听器
     */
    public void addOnModeSwitchListener(OnModeSwitchListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            Log.d(TAG, "已添加模式切换监听器");
        }
    }
    
    /**
     * 移除模式切换监听器
     */
    public void removeOnModeSwitchListener(OnModeSwitchListener listener) {
        listeners.remove(listener);
        Log.d(TAG, "已移除模式切换监听器");
    }
    
    /**
     * 手动切换到指定模式
     */
    public void manualSwitch(Mode mode) {
        Log.i(TAG, "手动切换到模式：" + mode);
        cancelPendingSwitch();
        switchToMode(mode, SwitchReason.MANUAL_SWITCH);
    }
    
    /**
     * 获取当前模式
     */
    public Mode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * 获取上一次模式
     */
    public Mode getPreviousMode() {
        return previousMode;
    }
    
    /**
     * 检查是否在线模式
     */
    public boolean isOnlineMode() {
        return currentMode == Mode.ONLINE;
    }
    
    /**
     * 检查是否离线模式
     */
    public boolean isOfflineMode() {
        return currentMode == Mode.OFFLINE;
    }
    
    /**
     * 获取 PC 在线状态
     */
    public boolean isPCOnline() {
        return pcOnline;
    }
    
    /**
     * 检查是否正在切换中
     */
    public boolean isSwitching() {
        return isSwitching;
    }
    
    /**
     * 获取本地 AI 管理器
     */
    public LocalAIManager getLocalAIManager() {
        return localAIManager;
    }
    
    /**
     * 获取媒体库管理器
     */
    public MediaLibraryManager getMediaLibraryManager() {
        return mediaLibraryManager;
    }
    
    // ============================================================
    // 本地 AI 管理器
    // ============================================================
    
    /**
     * 本地 AI 管理器
     * 负责加载和卸载本地 AI 模型（Qwen2.5-3B）
     * 集成了 LocalAIEngine 和 ModelDownloader
     */
    public static class LocalAIManager {
        private Context context;
        private LocalAIEngine aiEngine;
        private ModelDownloader downloader;
        private boolean aiLoaded;
        private boolean modelDownloaded;
        
        // 模型文件路径
        private static final String MODEL_PATH = "/sdcard/models/qwen2.5-3b-int4.mnn";
        
        public LocalAIManager(Context context) {
            this.context = context;
            this.aiLoaded = false;
            this.modelDownloaded = false;
            
            // 初始化 AI 引擎
            this.aiEngine = LocalAIEngine.getInstance(context);
            
            // 初始化下载器
            ModelDownloader.DownloadConfig config = new ModelDownloader.DownloadConfig();
            config.savePath = "/sdcard/models/";
            config.fileName = "qwen2.5-3b-int4.mnn";
            this.downloader = new ModelDownloader(context, config);
            
            // 设置下载回调
            downloader.setDownloadCallback(new ModelDownloader.DownloadCallback() {
                @Override
                public void onProgress(long downloaded, long total, int percent) {
                    Log.i(TAG, String.format("模型下载进度：%d%% (%.2f MB / %.2f MB)", 
                            percent, 
                            downloaded / 1024.0 / 1024.0,
                            total / 1024.0 / 1024.0));
                }
                
                @Override
                public void onSuccess(File modelFile) {
                    Log.i(TAG, "模型下载完成：" + modelFile.getAbsolutePath());
                    modelDownloaded = true;
                    
                    // 下载完成后自动加载模型
                    loadModelInternal();
                }
                
                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "模型下载失败", e);
                    modelDownloaded = false;
                }
            });
            
            Log.i(TAG, "LocalAIManager 初始化完成");
        }
        
        /**
         * 启动本地 AI
         * 检查模型是否存在，不存在则下载，然后加载到内存
         */
        public void start() {
            if (aiLoaded) {
                Log.w(TAG, "本地 AI 已在内存中，跳过加载");
                return;
            }
            
            Log.i(TAG, "启动本地 AI (Qwen2.5-3B)");
            
            // 检查模型文件是否存在
            File modelFile = new File(MODEL_PATH);
            if (modelFile.exists()) {
                Log.i(TAG, "模型文件已存在，直接加载");
                modelDownloaded = true;
                loadModelInternal();
            } else {
                Log.i(TAG, "模型文件不存在，开始下载");
                downloadModel();
            }
        }
        
        /**
         * 下载模型
         */
        private void downloadModel() {
            if (downloader.isDownloading()) {
                Log.w(TAG, "模型正在下载中，请勿重复启动");
                return;
            }
            
            Log.i(TAG, "开始下载 Qwen2.5-3B INT4 模型");
            downloader.startDownload();
        }
        
        /**
         * 加载模型到内存
         */
        private void loadModelInternal() {
            new Thread(() -> {
                try {
                    Log.i(TAG, "开始加载模型到内存");
                    
                    // 使用 AI 引擎加载模型
                    boolean success = aiEngine.loadModel(MODEL_PATH);
                    
                    if (success) {
                        aiLoaded = true;
                        Log.i(TAG, "模型加载完成：" + aiEngine.getModelInfo().toString());
                    } else {
                        Log.e(TAG, "模型加载失败");
                        aiLoaded = false;
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "模型加载失败", e);
                    aiLoaded = false;
                }
            }).start();
        }
        
        /**
         * 停止本地 AI
         * 从内存中卸载模型，释放资源
         */
        public void stop() {
            if (!aiLoaded) {
                Log.w(TAG, "本地 AI 未加载，跳过卸载");
                return;
            }
            
            Log.i(TAG, "停止本地 AI");
            
            try {
                // 卸载模型
                aiEngine.unloadModel();
                aiLoaded = false;
                
                Log.i(TAG, "本地 AI 已停止");
                
            } catch (Exception e) {
                Log.e(TAG, "本地 AI 停止失败", e);
            }
        }
        
        /**
         * 检查 AI 是否已加载
         */
        public boolean isAILoaded() {
            return aiLoaded;
        }
        
        /**
         * 检查模型是否已下载
         */
        public boolean isModelDownloaded() {
            return modelDownloaded;
        }
        
        /**
         * 执行本地 AI 推理（同步）
         * @param input 输入文本
         * @return 推理结果
         */
        public String infer(String input) {
            if (!aiLoaded) {
                Log.w(TAG, "本地 AI 未加载，无法推理");
                return "本地 AI 未加载";
            }
            
            Log.d(TAG, "执行 AI 推理：" + input);
            return aiEngine.generate(input);
        }
        
        /**
         * 执行本地 AI 流式推理
         * @param input 输入文本
         * @param callback 流式回调
         */
        public void inferStream(String input, LocalAIEngine.StreamGenerationCallback callback) {
            if (!aiLoaded) {
                Log.w(TAG, "本地 AI 未加载，无法推理");
                if (callback != null) {
                    callback.onError(new IllegalStateException("本地 AI 未加载"));
                }
                return;
            }
            
            Log.d(TAG, "执行 AI 流式推理：" + input);
            aiEngine.generateStream(input, callback);
        }
        
        /**
         * 获取 AI 引擎实例
         */
        public LocalAIEngine getAIEngine() {
            return aiEngine;
        }
        
        /**
         * 获取下载器实例
         */
        public ModelDownloader getDownloader() {
            return downloader;
        }
        
        /**
         * 获取模型下载进度
         */
        public int getDownloadProgress() {
            if (downloader != null) {
                return downloader.getProgressPercent();
            }
            return 0;
        }
        
        /**
         * 释放资源
         */
        public void release() {
            Log.i(TAG, "释放 LocalAIManager 资源");
            
            stop();
            
            if (downloader != null) {
                downloader.release();
                downloader = null;
            }
        }
    }
    
    // ============================================================
    // 媒体库管理器
    // ============================================================
    
    /**
     * 媒体库管理器
     * 负责切换本地音乐库和 PC 音乐库
     */
    public static class MediaLibraryManager {
        private Context context;
        private String currentLibrary;
        
        public static final String LIBRARY_LOCAL = "local";
        public static final String LIBRARY_PC = "pc";
        
        public MediaLibraryManager(Context context) {
            this.context = context;
            this.currentLibrary = LIBRARY_LOCAL;
        }
        
        /**
         * 使用本地音乐库
         */
        public void useLocalLibrary() {
            if (LIBRARY_LOCAL.equals(currentLibrary)) {
                Log.d(TAG, "已在使用本地音乐库，跳过切换");
                return;
            }
            
            Log.i(TAG, "切换到本地音乐库");
            currentLibrary = LIBRARY_LOCAL;
            
            // TODO: 实现实际的媒体库切换逻辑
            // 1. 断开与 PC 音乐库的连接
            // 2. 连接到本地音乐库
            // 3. 刷新媒体播放器
        }
        
        /**
         * 使用 PC 音乐库
         */
        public void usePCLibrary() {
            if (LIBRARY_PC.equals(currentLibrary)) {
                Log.d(TAG, "已在使用 PC 音乐库，跳过切换");
                return;
            }
            
            Log.i(TAG, "切换到 PC 音乐库");
            currentLibrary = LIBRARY_PC;
            
            // TODO: 实现实际的媒体库切换逻辑
            // 1. 断开与本地音乐库的连接
            // 2. 连接到 PC 音乐库（通过网络）
            // 3. 刷新媒体播放器
        }
        
        /**
         * 获取当前音乐库
         */
        public String getCurrentLibrary() {
            return currentLibrary;
        }
        
        /**
         * 检查是否使用本地音乐库
         */
        public boolean isUsingLocalLibrary() {
            return LIBRARY_LOCAL.equals(currentLibrary);
        }
        
        /**
         * 检查是否使用 PC 音乐库
         */
        public boolean isUsingPCLibrary() {
            return LIBRARY_PC.equals(currentLibrary);
        }
    }
}
