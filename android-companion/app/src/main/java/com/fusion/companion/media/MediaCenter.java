package com.fusion.companion.media;

import android.content.Context;
import android.util.Log;

import com.fusion.companion.tts.TTSManager;

/**
 * 媒体中心 - 统一集成 TTS、音乐库、播放器和跟随功能
 * 
 * 使用示例：
 * <pre>
 * {@code
 * // 1. 初始化媒体中心
 * MediaCenter mediaCenter = new MediaCenter(context);
 * 
 * // 2. 扫描音乐库
 * mediaCenter.scanMusic(new LocalMusicLibrary.ScanProgressCallback() {
 *     public void onScanComplete(int songCount) {
 *         Log.i("MediaCenter", "扫描完成：" + songCount + "首歌曲");
 *     }
 * });
 * 
 * // 3. 播放音乐
 * LocalMusicLibrary.Playlist playlist = mediaCenter.getMusicLibrary().getPlaylist("favorites");
 * mediaCenter.getMediaManager().loadPlaylist(playlist);
 * mediaCenter.getMediaManager().play();
 * 
 * // 4. 使用 TTS
 * mediaCenter.getTTSManager().speak("音乐开始播放");
 * 
 * // 5. 启用音乐跟随
 * mediaCenter.getMusicFollower().setAutoFollowEnabled(true);
 * }
 * </pre>
 * 
 * @author Fusion
 * @version 1.0
 */
public class MediaCenter {
    
    private static final String TAG = "MediaCenter";
    
    // 上下文
    private Context context;
    
    // TTS 管理器
    private TTSManager ttsManager;
    
    // 音乐库
    private LocalMusicLibrary musicLibrary;
    
    // 媒体管理器
    private MediaManager mediaManager;
    
    // 音乐跟随器
    private MusicFollower musicFollower;
    
    // 是否已初始化
    private boolean isInitialized = false;
    
    /**
     * 构造函数
     * 
     * @param context 上下文
     */
    public MediaCenter(Context context) {
        this.context = context.getApplicationContext();
        init();
    }
    
    /**
     * 初始化
     */
    private void init() {
        Log.i(TAG, "初始化媒体中心");
        
        try {
            // 1. 初始化音乐库
            musicLibrary = new LocalMusicLibrary(context);
            Log.d(TAG, "音乐库初始化完成");
            
            // 2. 初始化 TTS 管理器
            ttsManager = TTSManager.getInstance(context);
            Log.d(TAG, "TTS 管理器初始化完成");
            
            // 3. 初始化媒体管理器
            mediaManager = new MediaManager(context, musicLibrary);
            Log.d(TAG, "媒体管理器初始化完成");
            
            // 4. 初始化音乐跟随器（需要依赖注入）
            // 注意：实际使用时需要提供 SensorDataListener、PlaybackSyncManager 和 DeviceManager 的实现
            musicFollower = createMusicFollower();
            Log.d(TAG, "音乐跟随器初始化完成");
            
            isInitialized = true;
            Log.i(TAG, "媒体中心初始化完成");
            
        } catch (Exception e) {
            Log.e(TAG, "媒体中心初始化失败", e);
            isInitialized = false;
        }
    }
    
    /**
     * 创建音乐跟随器
     * 
     * @return 音乐跟随器
     */
    private MusicFollower createMusicFollower() {
        // 创建默认的传感器监听器（需要实际实现）
        MusicFollower.SensorDataListener sensorListener = new MusicFollower.SensorDataListener() {
            @Override
            public MusicFollower.RoomInfo getCurrentRoomSensor() {
                // TODO: 实现传感器数据获取
                MusicFollower.RoomInfo room = new MusicFollower.RoomInfo();
                room.roomId = "room_0";
                room.roomName = "未知房间";
                return room;
            }
            
            @Override
            public MusicFollower.RoomInfo[] getAllRoomsSensor() {
                // TODO: 实现所有房间传感器数据获取
                return new MusicFollower.RoomInfo[0];
            }
        };
        
        // 创建默认的同步管理器（需要实际实现）
        MusicFollower.PlaybackSyncManager syncManager = new MusicFollower.PlaybackSyncManager() {
            @Override
            public void syncPlaybackState(String deviceId, String songId, int position, boolean isPlaying) {
                // TODO: 实现跨设备同步
                Log.d(TAG, "同步播放状态到设备：" + deviceId);
            }
            
            @Override
            public MusicFollower.PlaybackState getPlaybackState(String deviceId) {
                // TODO: 实现获取远程播放状态
                return new MusicFollower.PlaybackState();
            }
            
            @Override
            public void clearPlaybackState(String deviceId) {
                // TODO: 实现清除远程播放状态
                Log.d(TAG, "清除设备播放状态：" + deviceId);
            }
        };
        
        // 创建默认的设备管理器（需要实际实现）
        MusicFollower.DeviceManager deviceManager = new MusicFollower.DeviceManager() {
            @Override
            public String getCurrentDeviceId() {
                // TODO: 实现获取当前设备 ID
                return "device_" + android.os.Build.SERIAL;
            }
            
            @Override
            public MusicFollower.DeviceInfo[] getAllDevices() {
                // TODO: 实现获取所有设备列表
                return new MusicFollower.DeviceInfo[0];
            }
            
            @Override
            public MusicFollower.DeviceInfo getDevice(String deviceId) {
                // TODO: 实现获取设备信息
                MusicFollower.DeviceInfo info = new MusicFollower.DeviceInfo();
                info.deviceId = deviceId;
                info.deviceName = "未知设备";
                info.isOnline = true;
                return info;
            }
            
            @Override
            public void switchToDevice(String deviceId, MusicFollower.PlaybackState state) {
                // TODO: 实现设备切换
                Log.d(TAG, "切换到设备：" + deviceId + ", 播放状态：" + state.songTitle);
            }
        };
        
        return new MusicFollower(context, mediaManager, musicLibrary, 
                                sensorListener, syncManager, deviceManager);
    }
    
    /**
     * 扫描音乐库
     */
    public void scanMusic() {
        scanMusic(null);
    }
    
    /**
     * 扫描音乐库（带回调）
     * 
     * @param callback 进度回调
     */
    public void scanMusic(LocalMusicLibrary.ScanProgressCallback callback) {
        if (!isInitialized) {
            Log.e(TAG, "媒体中心未初始化");
            return;
        }
        
        musicLibrary.scanMusicAsync(callback);
    }
    
    /**
     * 获取 TTS 管理器
     * 
     * @return TTS 管理器
     */
    public TTSManager getTTSManager() {
        if (!isInitialized) {
            Log.e(TAG, "媒体中心未初始化");
            return null;
        }
        return ttsManager;
    }
    
    /**
     * 获取音乐库
     * 
     * @return 音乐库
     */
    public LocalMusicLibrary getMusicLibrary() {
        if (!isInitialized) {
            Log.e(TAG, "媒体中心未初始化");
            return null;
        }
        return musicLibrary;
    }
    
    /**
     * 获取媒体管理器
     * 
     * @return 媒体管理器
     */
    public MediaManager getMediaManager() {
        if (!isInitialized) {
            Log.e(TAG, "媒体中心未初始化");
            return null;
        }
        return mediaManager;
    }
    
    /**
     * 获取音乐跟随器
     * 
     * @return 音乐跟随器
     */
    public MusicFollower getMusicFollower() {
        if (!isInitialized) {
            Log.e(TAG, "媒体中心未初始化");
            return null;
        }
        return musicFollower;
    }
    
    /**
     * 是否已初始化
     * 
     * @return 是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }
    
    /**
     * 释放所有资源
     */
    public void release() {
        Log.i(TAG, "释放媒体中心资源");
        
        if (ttsManager != null) {
            ttsManager.release();
            ttsManager = null;
        }
        
        if (musicLibrary != null) {
            musicLibrary.release();
            musicLibrary = null;
        }
        
        if (mediaManager != null) {
            mediaManager.release();
            mediaManager = null;
        }
        
        if (musicFollower != null) {
            musicFollower.release();
            musicFollower = null;
        }
        
        isInitialized = false;
        
        Log.i(TAG, "媒体中心资源已释放");
    }
}
