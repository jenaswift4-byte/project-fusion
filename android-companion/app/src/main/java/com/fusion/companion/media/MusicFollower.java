package com.fusion.companion.media;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 音乐跟随器
 * 
 * 功能：
 * 1. 检测人位置（距离传感器）
 * 2. 自动切换播放设备
 * 3. 保持播放进度同步
 * 4. 支持手动切换
 * 
 * 切换逻辑：
 * 检测到人离开当前房间
 *   ↓
 * 检测到人进入新房间
 *   ↓
 * 暂停当前设备播放
 *   ↓
 * 新设备从相同进度开始播放
 *   ↓
 * 完成切换
 * 
 * @author Fusion
 * @version 1.0
 */
public class MusicFollower {
    
    private static final String TAG = "MusicFollower";
    
    // 上下文
    private Context context;
    
    // 当前设备 ID
    private String currentDeviceId;
    
    // 当前房间 ID
    private String currentRoomId;
    
    // 媒体管理器
    private MediaManager mediaManager;
    
    // 音乐库
    private LocalMusicLibrary musicLibrary;
    
    // 传感器数据监听器
    private SensorDataListener sensorListener;
    
    // 播放状态同步器
    private PlaybackSyncManager syncManager;
    
    // 设备管理器
    private DeviceManager deviceManager;
    
    // 是否启用自动跟随
    private boolean autoFollowEnabled = true;
    
    // 检测间隔（毫秒）
    private static final int DETECTION_INTERVAL_MS = 2000;
    
    // 切换延迟（毫秒，确认人真的进入新房间）
    private static final int SWITCH_DELAY_MS = 3000;
    
    // 定时检测任务
    private ScheduledExecutorService detectionExecutor;
    
    // 异步执行线程池
    private ExecutorService executor;
    
    // 主线程 Handler
    private Handler mainHandler;
    
    // 切换状态
    private SwitchState switchState;
    
    // 回调列表
    private CopyOnWriteArrayList<FollowerCallback> callbacks;
    
    /**
     * 切换状态枚举
     */
    public enum SwitchState {
        IDLE,              // 空闲
        DETECTING,         // 检测中
        SWITCHING,         // 切换中
        SWITCHED           // 已切换
    }
    
    /**
     * 设备信息类
     */
    public static class DeviceInfo {
        public String deviceId;      // 设备 ID
        public String deviceName;    // 设备名
        public String roomId;        // 房间 ID
        public String roomName;      // 房间名
        public boolean isOnline;     // 是否在线
        public boolean isActive;     // 是否正在播放
        public boolean hasSpeaker;   // 是否有扬声器（用于音频跟随判断）
        
        public DeviceInfo() {
            this.isOnline = false;
            this.isActive = false;
            this.hasSpeaker = false;
        }
    }
    
    /**
     * 房间信息类
     */
    public static class RoomInfo {
        public String roomId;        // 房间 ID
        public String roomName;      // 房间名
        public String deviceId;      // 设备 ID
        public boolean hasPerson;    // 是否有人
        public int personCount;      // 人数
        // 环境传感器数据（v2.0 新增，由 DeviceManager 填充）
        public double temperature;   // 温度
        public double humidity;      // 湿度
        public float lightLevel;     // 光照强度
        public float noiseLevel;     // 噪音水平 (dB)
        
        public RoomInfo() {
            this.hasPerson = false;
            this.personCount = 0;
            this.temperature = 0;
            this.humidity = 0;
            this.lightLevel = 0;
            this.noiseLevel = 0;
        }
    }
    
    /**
     * 传感器数据监听器接口
     */
    public interface SensorDataListener {
        /**
         * 获取当前房间的人体传感器数据
         * @return 房间信息
         */
        RoomInfo getCurrentRoomSensor();
        
        /**
         * 获取所有房间的人体传感器数据
         * @return 房间信息列表
         */
        RoomInfo[] getAllRoomsSensor();
    }
    
    /**
     * 播放状态同步器接口
     */
    public interface PlaybackSyncManager {
        /**
         * 同步播放状态到其他设备
         * @param deviceId 设备 ID
         * @param songId 歌曲 ID
         * @param position 播放位置（毫秒）
         * @param isPlaying 是否正在播放
         */
        void syncPlaybackState(String deviceId, String songId, int position, boolean isPlaying);
        
        /**
         * 从其他设备获取播放状态
         * @param deviceId 设备 ID
         * @return 播放状态
         */
        PlaybackState getPlaybackState(String deviceId);
        
        /**
         * 清除指定设备的播放状态
         * @param deviceId 设备 ID
         */
        void clearPlaybackState(String deviceId);
    }
    
    /**
     * 播放状态类
     */
    public static class PlaybackState {
        public String songId;        // 歌曲 ID
        public String songTitle;     // 歌曲名
        public int position;         // 播放位置（毫秒）
        public int duration;         // 总时长（毫秒）
        public boolean isPlaying;    // 是否正在播放
        public long timestamp;       // 时间戳
        
        public PlaybackState() {
            this.position = 0;
            this.isPlaying = false;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * 设备管理器接口
     */
    public interface DeviceManager {
        /**
         * 获取当前设备 ID
         * @return 设备 ID
         */
        String getCurrentDeviceId();
        
        /**
         * 获取所有设备列表
         * @return 设备信息列表
         */
        DeviceInfo[] getAllDevices();
        
        /**
         * 获取指定设备信息
         * @param deviceId 设备 ID
         * @return 设备信息
         */
        DeviceInfo getDevice(String deviceId);
        
        /**
         * 切换到指定设备播放
         * @param deviceId 设备 ID
         * @param state 播放状态
         */
        void switchToDevice(String deviceId, PlaybackState state);
    }
    
    /**
     * 跟随器回调接口
     */
    public interface FollowerCallback {
        /**
         * 检测到人员变化
         * @param fromRoom 离开的房间
         * @param toRoom 进入的房间
         */
        void onPersonDetected(String fromRoom, String toRoom);
        
        /**
         * 开始切换
         * @param fromDevice 原设备
         * @param toDevice 目标设备
         */
        void onSwitchStart(String fromDevice, String toDevice);
        
        /**
         * 切换完成
         * @param fromDevice 原设备
         * @param toDevice 目标设备
         * @param success 是否成功
         */
        void onSwitchComplete(String fromDevice, String toDevice, boolean success);
        
        /**
         * 切换失败
         * @param error 错误信息
         */
        void onSwitchError(String error);
    }
    
    /**
     * 构造函数
     * 
     * @param context 上下文
     * @param mediaManager 媒体管理器
     * @param musicLibrary 音乐库
     * @param sensorListener 传感器监听器
     * @param syncManager 同步管理器
     * @param deviceManager 设备管理器
     */
    public MusicFollower(Context context, 
                         MediaManager mediaManager,
                         LocalMusicLibrary musicLibrary,
                         SensorDataListener sensorListener,
                         PlaybackSyncManager syncManager,
                         DeviceManager deviceManager) {
        this.context = context.getApplicationContext();
        this.mediaManager = mediaManager;
        this.musicLibrary = musicLibrary;
        this.sensorListener = sensorListener;
        this.syncManager = syncManager;
        this.deviceManager = deviceManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.callbacks = new CopyOnWriteArrayList<>();
        this.switchState = SwitchState.IDLE;
        this.executor = Executors.newSingleThreadExecutor();
        this.detectionExecutor = Executors.newScheduledThreadPool(1);
        
        // 初始化设备 ID
        this.currentDeviceId = deviceManager.getCurrentDeviceId();
        
        Log.i(TAG, "音乐跟随器初始化完成，设备 ID: " + currentDeviceId);
    }
    
    /**
     * 启用/禁用自动跟随
     * 
     * @param enabled 是否启用
     */
    public void setAutoFollowEnabled(boolean enabled) {
        this.autoFollowEnabled = enabled;
        Log.i(TAG, "自动跟随已" + (enabled ? "启用" : "禁用"));
        
        if (enabled) {
            startDetection();
        } else {
            stopDetection();
        }
    }
    
    /**
     * 是否启用自动跟随
     * 
     * @return 是否启用
     */
    public boolean isAutoFollowEnabled() {
        return autoFollowEnabled;
    }
    
    /**
     * 添加回调
     * 
     * @param callback 回调接口
     */
    public void addCallback(FollowerCallback callback) {
        if (callback != null && !callbacks.contains(callback)) {
            callbacks.add(callback);
            Log.d(TAG, "添加跟随回调");
        }
    }
    
    /**
     * 移除回调
     * 
     * @param callback 回调接口
     */
    public void removeCallback(FollowerCallback callback) {
        callbacks.remove(callback);
        Log.d(TAG, "移除跟随回调");
    }
    
    /**
     * 开始检测
     */
    public void startDetection() {
        if (detectionExecutor.isShutdown()) {
            detectionExecutor = Executors.newScheduledThreadPool(1);
        }
        
        detectionExecutor.scheduleAtFixedRate(() -> {
            try {
                detectAndSwitch();
            } catch (Exception e) {
                Log.e(TAG, "检测失败", e);
            }
        }, 0, DETECTION_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        Log.d(TAG, "开始人员检测");
    }
    
    /**
     * 停止检测
     */
    public void stopDetection() {
        if (detectionExecutor != null && !detectionExecutor.isShutdown()) {
            detectionExecutor.shutdown();
            Log.d(TAG, "停止人员检测");
        }
    }
    
    /**
     * 检测并切换
     */
    private void detectAndSwitch() {
        if (!autoFollowEnabled || switchState == SwitchState.SWITCHING) {
            return;
        }
        
        switchState = SwitchState.DETECTING;
        
        executor.submit(() -> {
            try {
                // 获取所有房间传感器数据
                RoomInfo[] rooms = sensorListener.getAllRoomsSensor();
                
                if (rooms == null || rooms.length == 0) {
                    return;
                }
                
                // 查找有人的房间
                final RoomInfo[] occupiedRoom = {null};
                for (RoomInfo room : rooms) {
                    if (room.hasPerson && room.personCount > 0) {
                        occupiedRoom[0] = room;
                        break;
                    }
                }
                
                if (occupiedRoom[0] == null) {
                    // 所有房间都没人，不切换
                    return;
                }
                
                // 检查是否是当前房间
                if (occupiedRoom[0].roomId.equals(currentRoomId)) {
                    // 人还在当前房间，不切换
                    return;
                }
                
                // 检测到人员移动
                Log.i(TAG, "检测到人从 " + currentRoomId + " 移动到 " + occupiedRoom[0].roomId);
                notifyPersonDetected(currentRoomId, occupiedRoom[0].roomId);
                
                // 延迟切换，确认人真的进入新房间
                mainHandler.postDelayed(() -> {
                    // 再次检查
                    RoomInfo currentSensor = sensorListener.getCurrentRoomSensor();
                    if (currentSensor.hasPerson && currentSensor.personCount > 0) {
                        // 确认进入新房间，执行切换
                        switchToDevice(occupiedRoom[0]);
                    }
                }, SWITCH_DELAY_MS);
                
            } catch (Exception e) {
                Log.e(TAG, "检测失败", e);
                notifySwitchError(e.getMessage());
            }
        });
    }
    
    /**
     * 切换到目标设备
     * 
     * @param targetRoom 目标房间
     */
    private void switchToDevice(RoomInfo targetRoom) {
        if (switchState == SwitchState.SWITCHING) {
            Log.w(TAG, "正在切换，跳过");
            return;
        }
        
        switchState = SwitchState.SWITCHING;
        
        executor.submit(() -> {
            try {
                String fromDevice = currentDeviceId;
                String toDevice = targetRoom.deviceId;
                
                if (toDevice == null || toDevice.equals(fromDevice)) {
                    Log.w(TAG, "目标设备无效");
                    switchState = SwitchState.IDLE;
                    return;
                }
                
                Log.i(TAG, "开始切换：从 " + fromDevice + " 到 " + toDevice);
                notifySwitchStart(fromDevice, toDevice);
                
                // 1. 获取当前播放状态
                LocalMusicLibrary.Song currentSong = mediaManager.getCurrentSong();
                int currentPosition = mediaManager.getCurrentPosition();
                boolean isPlaying = mediaManager.isPlaying();
                
                if (currentSong == null) {
                    Log.w(TAG, "当前没有播放歌曲");
                    switchState = SwitchState.IDLE;
                    return;
                }
                
                // 2. 创建播放状态
                PlaybackState state = new PlaybackState();
                state.songId = currentSong.id;
                state.songTitle = currentSong.title;
                state.position = currentPosition;
                state.duration = (int) currentSong.duration;
                state.isPlaying = isPlaying;
                
                // 3. 同步到其他设备
                syncManager.syncPlaybackState(toDevice, state.songId, state.position, state.isPlaying);
                
                // 4. 暂停当前设备
                mediaManager.pause();
                
                // 5. 切换设备
                deviceManager.switchToDevice(toDevice, state);
                
                // 6. 更新当前房间
                currentRoomId = targetRoom.roomId;
                currentDeviceId = toDevice;
                
                Log.i(TAG, "切换完成：新设备 " + toDevice + ", 新房间 " + currentRoomId);
                switchState = SwitchState.SWITCHED;
                
                notifySwitchComplete(fromDevice, toDevice, true);
                
                // 延迟重置状态
                mainHandler.postDelayed(() -> {
                    switchState = SwitchState.IDLE;
                }, 5000);
                
            } catch (Exception e) {
                Log.e(TAG, "切换失败", e);
                switchState = SwitchState.IDLE;
                notifySwitchError(e.getMessage());
            }
        });
    }
    
    /**
     * 手动切换到指定设备
     * 
     * @param deviceId 设备 ID
     */
    public void manualSwitch(String deviceId) {
        Log.i(TAG, "手动切换到设备：" + deviceId);
        
        executor.submit(() -> {
            try {
                DeviceInfo device = deviceManager.getDevice(deviceId);
                if (device == null) {
                    notifySwitchError("设备不存在：" + deviceId);
                    return;
                }
                
                RoomInfo room = new RoomInfo();
                room.roomId = device.roomId;
                room.deviceId = deviceId;
                
                switchToDevice(room);
                
            } catch (Exception e) {
                Log.e(TAG, "手动切换失败", e);
                notifySwitchError(e.getMessage());
            }
        });
    }
    
    /**
     * 获取当前设备 ID
     * 
     * @return 设备 ID
     */
    public String getCurrentDeviceId() {
        return currentDeviceId;
    }
    
    /**
     * 获取当前房间 ID
     * 
     * @return 房间 ID
     */
    public String getCurrentRoomId() {
        return currentRoomId;
    }
    
    /**
     * 获取切换状态
     * 
     * @return 切换状态
     */
    public SwitchState getSwitchState() {
        return switchState;
    }
    
    /**
     * 通知人员检测
     * 
     * @param fromRoom 离开的房间
     * @param toRoom 进入的房间
     */
    private void notifyPersonDetected(final String fromRoom, final String toRoom) {
        for (FollowerCallback callback : callbacks) {
            try {
                callback.onPersonDetected(fromRoom, toRoom);
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 通知切换开始
     * 
     * @param fromDevice 原设备
     * @param toDevice 目标设备
     */
    private void notifySwitchStart(final String fromDevice, final String toDevice) {
        for (FollowerCallback callback : callbacks) {
            try {
                callback.onSwitchStart(fromDevice, toDevice);
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 通知切换完成
     * 
     * @param fromDevice 原设备
     * @param toDevice 目标设备
     * @param success 是否成功
     */
    private void notifySwitchComplete(final String fromDevice, final String toDevice, 
                                      final boolean success) {
        for (FollowerCallback callback : callbacks) {
            try {
                callback.onSwitchComplete(fromDevice, toDevice, success);
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 通知切换错误
     * 
     * @param error 错误信息
     */
    private void notifySwitchError(final String error) {
        for (FollowerCallback callback : callbacks) {
            try {
                callback.onSwitchError(error);
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        Log.i(TAG, "释放音乐跟随器资源");
        
        stopDetection();
        
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
        }
        
        callbacks.clear();
        
        Log.i(TAG, "音乐跟随器资源已释放");
    }
}
