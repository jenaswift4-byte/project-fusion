package com.fusion.companion.media;

import android.content.Context;
import android.util.Log;

import com.fusion.companion.device.Device;
import com.fusion.companion.device.DeviceManager;
import com.fusion.companion.device.DeviceStatus;
import com.fusion.companion.tts.TTSManager;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 媒体中心 - 统一集成 TTS、音乐库、播放器和跟随功能
 * 
 * v2.0: 接入真实 DeviceManager + MQTT 跨设备同步
 * 
 * @author Fusion
 * @version 2.0
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
    
    // 全局实例
    private static volatile MediaCenter sInstance;
    
    /**
     * 获取全局 MediaCenter 实例（需先调用 initInstance）
     */
    public static MediaCenter getInstance() {
        return sInstance;
    }
    
    /**
     * 初始化全局实例
     */
    public static MediaCenter initInstance(Context context) {
        if (sInstance == null) {
            synchronized (MediaCenter.class) {
                if (sInstance == null) {
                    sInstance = new MediaCenter(context);
                }
            }
        }
        return sInstance;
    }
    
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
            
            // 4. 初始化音乐跟随器（接入真实 DeviceManager + MQTT 同步）
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
     * 接入真实 DeviceManager 获取设备/传感器数据，通过 MQTT 实现跨设备同步
     */
    private MusicFollower createMusicFollower() {
        // ========== 传感器数据监听器：从 DeviceManager 获取设备传感器数据 ==========
        MusicFollower.SensorDataListener sensorListener = new MusicFollower.SensorDataListener() {
            @Override
            public MusicFollower.RoomInfo getCurrentRoomSensor() {
                MusicFollower.RoomInfo room = new MusicFollower.RoomInfo();
                room.roomId = "room_0";
                room.roomName = "当前房间";
                
                try {
                    DeviceManager dm = DeviceManager.getInstance(context);
                    if (dm != null && dm.isInitialized()) {
                        // 遍历已注册设备，找到 sensor 类型的设备获取数据
                        List<Device> devices = dm.listDevices();
                        for (Device d : devices) {
                            if ("sensor".equals(d.deviceType) && d.isOnline) {
                                room.roomName = d.location != null && !d.location.isEmpty() 
                                    ? d.location : d.name;
                                // 从 DeviceStatus 获取传感器值
                                DeviceStatus status = dm.getDeviceStatus(d.deviceId);
                                if (status != null) {
                                    room.temperature = status.temperature;
                                    room.humidity = status.humidity;
                                    // 光线和噪音从 data JSON 中读取
                                    if (status.data != null) {
                                        room.lightLevel = (float) status.data.optDouble("light", 0);
                                        room.noiseLevel = (float) status.data.optDouble("noise", 0);
                                    }
                                    Log.d(TAG, "传感器数据: temp=" + room.temperature 
                                        + ", hum=" + room.humidity + ", light=" + room.lightLevel);
                                }
                                break; // 用第一个在线传感器
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "从 DeviceManager 获取传感器数据失败", e);
                }
                
                return room;
            }
            
            @Override
            public MusicFollower.RoomInfo[] getAllRoomsSensor() {
                List<MusicFollower.RoomInfo> rooms = new ArrayList<>();
                
                try {
                    DeviceManager dm = DeviceManager.getInstance(context);
                    if (dm != null && dm.isInitialized()) {
                        List<Device> devices = dm.listDevices();
                        int roomIndex = 0;
                        for (Device d : devices) {
                            if ("sensor".equals(d.deviceType)) {
                                MusicFollower.RoomInfo room = new MusicFollower.RoomInfo();
                                room.roomId = "room_" + roomIndex;
                                room.roomName = d.location != null && !d.location.isEmpty() 
                                    ? d.location : d.name;
                                
                                DeviceStatus status = dm.getDeviceStatus(d.deviceId);
                                if (status != null) {
                                    room.temperature = status.temperature;
                                    room.humidity = status.humidity;
                                    if (status.data != null) {
                                        room.lightLevel = (float) status.data.optDouble("light", 0);
                                        room.noiseLevel = (float) status.data.optDouble("noise", 0);
                                    }
                                }
                                rooms.add(room);
                                roomIndex++;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "获取所有房间传感器数据失败", e);
                }
                
                return rooms.toArray(new MusicFollower.RoomInfo[0]);
            }
        };
        
        // ========== 播放同步管理器：通过 MQTT 跨设备同步播放状态 ==========
        MusicFollower.PlaybackSyncManager syncManager = new MusicFollower.PlaybackSyncManager() {
            @Override
            public void syncPlaybackState(String deviceId, String songId, int position, boolean isPlaying) {
                try {
                    MqttClient client = createQuickMQTT("media-sync");
                    if (client != null) {
                        JSONObject msg = new JSONObject();
                        msg.put("type", "sync_playback");
                        msg.put("song_id", songId);
                        msg.put("position", position);
                        msg.put("is_playing", isPlaying);
                        msg.put("source_device", android.os.Build.SERIAL);
                        client.publish("devices/" + deviceId + "/playback", 
                            msg.toString().getBytes(), 1, false);
                        client.disconnect();
                        client.close();
                        Log.d(TAG, "同步播放状态到 " + deviceId + ": " + songId + " @" + position);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "同步播放状态失败: " + deviceId, e);
                }
            }
            
            @Override
            public MusicFollower.PlaybackState getPlaybackState(String deviceId) {
                MusicFollower.PlaybackState state = new MusicFollower.PlaybackState();
                try {
                    DeviceManager dm = DeviceManager.getInstance(context);
                    if (dm != null && dm.isInitialized()) {
                        DeviceStatus status = dm.getDeviceStatus(deviceId);
                        if (status != null && status.data != null) {
                            state.isPlaying = status.data.optBoolean("is_playing", false);
                            state.songId = status.data.optString("song_id", "");
                            state.songTitle = status.data.optString("song_title", "未知");
                            state.position = status.data.optInt("position", 0);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "获取远程播放状态失败: " + deviceId, e);
                }
                return state;
            }
            
            @Override
            public void clearPlaybackState(String deviceId) {
                try {
                    MqttClient client = createQuickMQTT("media-clear");
                    if (client != null) {
                        JSONObject msg = new JSONObject();
                        msg.put("type", "clear_playback");
                        msg.put("source_device", android.os.Build.SERIAL);
                        client.publish("devices/" + deviceId + "/playback", 
                            msg.toString().getBytes(), 1, false);
                        client.disconnect();
                        client.close();
                        Log.d(TAG, "清除播放状态: " + deviceId);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "清除播放状态失败: " + deviceId, e);
                }
            }
        };
        
        // ========== 设备管理器：从 DeviceManager 获取设备列表 ==========
        MusicFollower.DeviceManager deviceManager = new MusicFollower.DeviceManager() {
            @Override
            public String getCurrentDeviceId() {
                return "device_" + android.os.Build.SERIAL;
            }
            
            @Override
            public MusicFollower.DeviceInfo[] getAllDevices() {
                List<MusicFollower.DeviceInfo> devices = new ArrayList<>();
                try {
                    DeviceManager dm = DeviceManager.getInstance(context);
                    if (dm != null && dm.isInitialized()) {
                        List<Device> allDevices = dm.listDevices();
                        for (Device d : allDevices) {
                            MusicFollower.DeviceInfo info = new MusicFollower.DeviceInfo();
                            info.deviceId = d.deviceId;
                            info.deviceName = d.name;
                            info.isOnline = d.isOnline;
                            info.hasSpeaker = d.hasCapability("speaker");
                            devices.add(info);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "获取设备列表失败", e);
                }
                return devices.toArray(new MusicFollower.DeviceInfo[0]);
            }
            
            @Override
            public MusicFollower.DeviceInfo getDevice(String deviceId) {
                MusicFollower.DeviceInfo info = new MusicFollower.DeviceInfo();
                try {
                    DeviceManager dm = DeviceManager.getInstance(context);
                    if (dm != null && dm.isInitialized()) {
                        Device d = dm.getDevice(deviceId);
                        if (d != null) {
                            info.deviceId = d.deviceId;
                            info.deviceName = d.name;
                            info.isOnline = d.isOnline;
                            info.hasSpeaker = d.hasCapability("speaker");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "获取设备信息失败: " + deviceId, e);
                }
                return info;
            }
            
            @Override
            public void switchToDevice(String deviceId, MusicFollower.PlaybackState state) {
                try {
                    // 通过 MQTT 发送切换播放命令
                    MqttClient client = createQuickMQTT("media-switch");
                    if (client != null) {
                        JSONObject cmd = new JSONObject();
                        cmd.put("command", "switch_playback");
                        cmd.put("song_id", state.songId);
                        cmd.put("position", state.position);
                        cmd.put("is_playing", state.isPlaying);
                        cmd.put("source_device", android.os.Build.SERIAL);
                        client.publish("devices/" + deviceId + "/command", 
                            cmd.toString().getBytes(), 1, false);
                        client.disconnect();
                        client.close();
                        Log.d(TAG, "切换播放到 " + deviceId + ": " + state.songTitle);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "切换设备失败: " + deviceId, e);
                }
            }
        };
        
        return new MusicFollower(context, mediaManager, musicLibrary, 
                                sensorListener, syncManager, deviceManager);
    }
    
    /**
     * 快速创建一次性 MQTT 客户端
     */
    private MqttClient createQuickMQTT(String suffix) {
        try {
            String brokerUrl = context.getSharedPreferences("fusion_prefs", Context.MODE_PRIVATE)
                .getString("mqtt_broker_url", "tcp://127.0.0.1:1883");
            String clientId = suffix + "-" + android.os.Build.SERIAL;
            MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(5);
            client.connect(opts);
            return client;
        } catch (Exception e) {
            Log.w(TAG, "快速 MQTT 连接失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 扫描音乐库
     */
    public void scanMusic() {
        scanMusic(null);
    }
    
    /**
     * 扫描音乐库（带回调）
     */
    public void scanMusic(LocalMusicLibrary.ScanProgressCallback callback) {
        if (!isInitialized) {
            Log.e(TAG, "媒体中心未初始化");
            return;
        }
        musicLibrary.scanMusicAsync(callback);
    }
    
    public TTSManager getTTSManager() {
        if (!isInitialized) return null;
        return ttsManager;
    }
    
    public LocalMusicLibrary getMusicLibrary() {
        if (!isInitialized) return null;
        return musicLibrary;
    }
    
    public MediaManager getMediaManager() {
        if (!isInitialized) return null;
        return mediaManager;
    }
    
    public MusicFollower getMusicFollower() {
        if (!isInitialized) return null;
        return musicFollower;
    }
    
    public boolean isInitialized() {
        return isInitialized;
    }
    
    public void release() {
        Log.i(TAG, "释放媒体中心资源");
        if (ttsManager != null) { ttsManager.release(); ttsManager = null; }
        if (musicLibrary != null) { musicLibrary.release(); musicLibrary = null; }
        if (mediaManager != null) { mediaManager.release(); mediaManager = null; }
        if (musicFollower != null) { musicFollower.release(); musicFollower = null; }
        isInitialized = false;
        Log.i(TAG, "媒体中心资源已释放");
    }
}
