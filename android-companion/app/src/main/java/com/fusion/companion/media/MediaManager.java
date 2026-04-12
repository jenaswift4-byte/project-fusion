package com.fusion.companion.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 媒体管理器
 * 
 * 功能：
 * 1. 播放/暂停/停止
 * 2. 上一首/下一首
 * 3. 音量控制
 * 4. 播放进度
 * 5. 播放状态回调
 * 6. 与 LocalMusicLibrary 集成
 * 7. 支持多种播放顺序（顺序/随机/单曲循环）
 * 
 * @author Fusion
 * @version 1.0
 */
public class MediaManager implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    
    private static final String TAG = "MediaManager";
    
    // 上下文
    private Context context;
    
    // 音频管理器
    private AudioManager audioManager;
    
    // 媒体播放器
    private MediaPlayer mediaPlayer;
    
    // 音乐库
    private LocalMusicLibrary musicLibrary;
    
    // 当前播放列表
    private List<LocalMusicLibrary.Song> currentPlaylist;
    
    // 当前播放索引
    private int current_index = -1;
    
    // 播放模式
    private PlayMode playMode;
    
    // 是否正在播放
    private boolean isPlaying = false;
    
    // 播放进度更新间隔（毫秒）
    private static final int PROGRESS_UPDATE_INTERVAL = 500;
    
    // 进度更新处理器
    private Handler progressHandler;
    
    // 进度更新任务
    private Runnable progressRunnable;
    
    // 音频焦点请求（Android 8.0+）
    private AudioFocusRequest audioFocusRequest;
    
    // 播放状态回调
    private CopyOnWriteArrayList<PlaybackCallback> callbacks;
    
    /**
     * 播放模式枚举
     */
    public enum PlayMode {
        SEQUENCE,      // 顺序播放
        SHUFFLE,       // 随机播放
        SINGLE_LOOP    // 单曲循环
    }
    
    /**
     * 播放状态枚举
     */
    public enum PlaybackState {
        IDLE,          // 空闲
        PREPARING,     // 加载中
        PLAYING,       // 播放中
        PAUSED,        // 暂停
        STOPPED,       // 停止
        COMPLETED      // 完成
    }
    
    /**
     * 播放状态回调接口
     */
    public interface PlaybackCallback {
        /**
         * 播放状态变化
         * @param state 播放状态
         */
        void onStateChanged(PlaybackState state);
        
        /**
         * 播放进度变化
         * @param position 当前位置（毫秒）
         * @param duration 总时长（毫秒）
         */
        void onProgressUpdate(int position, int duration);
        
        /**
         * 歌曲切换
         * @param song 当前歌曲
         * @param index 索引
         */
        void onSongChanged(LocalMusicLibrary.Song song, int index);
        
        /**
         * 播放完成
         */
        void onPlaybackComplete();
        
        /**
         * 播放错误
         * @param error 错误信息
         */
        void onError(String error);
    }
    
    /**
     * 构造函数
     * 
     * @param context 上下文
     * @param musicLibrary 音乐库
     */
    public MediaManager(Context context, LocalMusicLibrary musicLibrary) {
        this.context = context.getApplicationContext();
        this.musicLibrary = musicLibrary;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.currentPlaylist = new CopyOnWriteArrayList<>();
        this.playMode = PlayMode.SEQUENCE;
        this.callbacks = new CopyOnWriteArrayList<>();
        this.progressHandler = new Handler(Looper.getMainLooper());
        
        initMediaPlayer();
        setupAudioFocus();
        
        Log.i(TAG, "媒体管理器初始化完成");
    }
    
    /**
     * 初始化媒体播放器
     */
    private void initMediaPlayer() {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.setOnErrorListener(this);
            
            Log.d(TAG, "媒体播放器初始化完成");
        } catch (Exception e) {
            Log.e(TAG, "媒体播放器初始化失败", e);
        }
    }
    
    /**
     * 设置音频焦点（Android 8.0+）
     */
    private void setupAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .build();
        }
    }
    
    /**
     * 请求音频焦点
     * 
     * @return 是否获得焦点
     */
    private boolean requestAudioFocus() {
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, 
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        
        boolean granted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        Log.d(TAG, "音频焦点请求：" + (granted ? "成功" : "失败"));
        return granted;
    }
    
    /**
     * 放弃音频焦点
     */
    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(null);
        }
        Log.d(TAG, "音频焦点已放弃");
    }
    
    /**
     * 添加回调
     * 
     * @param callback 回调接口
     */
    public void addCallback(PlaybackCallback callback) {
        if (callback != null && !callbacks.contains(callback)) {
            callbacks.add(callback);
            Log.d(TAG, "添加播放回调");
        }
    }
    
    /**
     * 移除回调
     * 
     * @param callback 回调接口
     */
    public void removeCallback(PlaybackCallback callback) {
        callbacks.remove(callback);
        Log.d(TAG, "移除播放回调");
    }
    
    /**
     * 通知状态变化
     * 
     * @param state 播放状态
     */
    private void notifyStateChanged(final PlaybackState state) {
        for (PlaybackCallback callback : callbacks) {
            try {
                callback.onStateChanged(state);
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 通知进度变化
     * 
     * @param position 当前位置
     * @param duration 总时长
     */
    private void notifyProgressUpdate(final int position, final int duration) {
        for (PlaybackCallback callback : callbacks) {
            try {
                callback.onProgressUpdate(position, duration);
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 通知歌曲变化
     * 
     * @param song 当前歌曲
     * @param index 索引
     */
    private void notifySongChanged(final LocalMusicLibrary.Song song, final int index) {
        for (PlaybackCallback callback : callbacks) {
            try {
                callback.onSongChanged(song, index);
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 通知播放完成
     */
    private void notifyPlaybackComplete() {
        for (PlaybackCallback callback : callbacks) {
            try {
                callback.onPlaybackComplete();
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 通知错误
     * 
     * @param error 错误信息
     */
    private void notifyError(final String error) {
        for (PlaybackCallback callback : callbacks) {
            try {
                callback.onError(error);
            } catch (Exception e) {
                Log.e(TAG, "回调通知失败", e);
            }
        }
    }
    
    /**
     * 加载播放列表
     * 
     * @param songs 歌曲列表
     */
    public void loadPlaylist(List<LocalMusicLibrary.Song> songs) {
        if (songs == null || songs.isEmpty()) {
            Log.w(TAG, "播放列表为空");
            return;
        }
        
        currentPlaylist.clear();
        currentPlaylist.addAll(songs);
        current_index = 0;
        
        Log.d(TAG, "加载播放列表，共 " + songs.size() + " 首歌曲");
    }
    
    /**
     * 从音乐库加载播放列表
     * 
     * @param playlist 播放列表
     */
    public void loadPlaylist(LocalMusicLibrary.Playlist playlist) {
        if (playlist == null || playlist.songs.isEmpty()) {
            Log.w(TAG, "播放列表为空");
            return;
        }
        
        loadPlaylist(playlist.songs);
        Log.d(TAG, "从音乐库加载播放列表：" + playlist.name);
    }
    
    /**
     * 播放指定索引的歌曲
     * 
     * @param index 歌曲索引
     */
    public void playAt(int index) {
        if (index < 0 || index >= currentPlaylist.size()) {
            Log.e(TAG, "索引越界：" + index);
            notifyError("索引越界");
            return;
        }
        
        current_index = index;
        LocalMusicLibrary.Song song = currentPlaylist.get(index);
        
        Log.d(TAG, "播放第 " + index + " 首：" + song.title);
        
        try {
            // 请求音频焦点
            if (!requestAudioFocus()) {
                Log.e(TAG, "音频焦点请求失败");
            }
            
            // 重置播放器
            mediaPlayer.reset();
            
            // 设置数据源
            mediaPlayer.setDataSource(context, Uri.parse(song.filePath));
            
            // 准备播放
            mediaPlayer.prepareAsync();
            notifyStateChanged(PlaybackState.PREPARING);
            
        } catch (IOException e) {
            Log.e(TAG, "加载歌曲失败", e);
            notifyError("加载失败：" + e.getMessage());
            notifyStateChanged(PlaybackState.STOPPED);
        }
    }
    
    /**
     * 播放当前歌曲
     */
    public void play() {
        if (currentPlaylist.isEmpty()) {
            Log.w(TAG, "播放列表为空");
            return;
        }
        
        if (current_index < 0) {
            current_index = 0;
        }
        
        if (mediaPlayer != null) {
            try {
                // 请求音频焦点
                if (!requestAudioFocus()) {
                    Log.e(TAG, "音频焦点请求失败");
                }
                
                mediaPlayer.start();
                isPlaying = true;
                
                Log.d(TAG, "播放开始");
                notifyStateChanged(PlaybackState.PLAYING);
                
                // 开始进度更新
                startProgressUpdate();
                
            } catch (Exception e) {
                Log.e(TAG, "播放失败", e);
                notifyError("播放失败：" + e.getMessage());
            }
        }
    }
    
    /**
     * 暂停播放
     */
    public void pause() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.pause();
                isPlaying = false;
                
                Log.d(TAG, "播放暂停");
                notifyStateChanged(PlaybackState.PAUSED);
                
                // 停止进度更新
                stopProgressUpdate();
                
            } catch (Exception e) {
                Log.e(TAG, "暂停失败", e);
            }
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.reset();
                isPlaying = false;
                current_index = -1;
                
                Log.d(TAG, "播放停止");
                notifyStateChanged(PlaybackState.STOPPED);
                
                // 停止进度更新
                stopProgressUpdate();
                
                // 放弃音频焦点
                abandonAudioFocus();
                
            } catch (Exception e) {
                Log.e(TAG, "停止失败", e);
            }
        }
    }
    
    /**
     * 播放下一首
     */
    public void playNext() {
        if (currentPlaylist.isEmpty()) {
            Log.w(TAG, "播放列表为空");
            return;
        }
        
        int nextIndex;
        
        switch (playMode) {
            case SHUFFLE:
                // 随机播放
                nextIndex = new Random().nextInt(currentPlaylist.size());
                break;
                
            case SINGLE_LOOP:
                // 单曲循环
                nextIndex = current_index;
                break;
                
            case SEQUENCE:
            default:
                // 顺序播放
                nextIndex = current_index + 1;
                if (nextIndex >= currentPlaylist.size()) {
                    nextIndex = 0;  // 循环到第一首
                }
                break;
        }
        
        Log.d(TAG, "播放下一首，索引：" + nextIndex + ", 模式：" + playMode);
        playAt(nextIndex);
    }
    
    /**
     * 播放上一首
     */
    public void playPrevious() {
        if (currentPlaylist.isEmpty()) {
            Log.w(TAG, "播放列表为空");
            return;
        }
        
        int prevIndex;
        
        // 如果播放超过 3 秒，从头开始
        if (getCurrentPosition() > 3000) {
            prevIndex = current_index;
        } else {
            switch (playMode) {
                case SHUFFLE:
                    prevIndex = new Random().nextInt(currentPlaylist.size());
                    break;
                    
                case SINGLE_LOOP:
                    prevIndex = current_index;
                    break;
                    
                case SEQUENCE:
                default:
                    prevIndex = current_index - 1;
                    if (prevIndex < 0) {
                        prevIndex = currentPlaylist.size() - 1;
                    }
                    break;
            }
        }
        
        Log.d(TAG, "播放上一首，索引：" + prevIndex);
        playAt(prevIndex);
    }
    
    /**
     * 设置播放模式
     * 
     * @param mode 播放模式
     */
    public void setPlayMode(PlayMode mode) {
        this.playMode = mode;
        Log.d(TAG, "播放模式已设置：" + mode);
    }
    
    /**
     * 获取播放模式
     * 
     * @return 播放模式
     */
    public PlayMode getPlayMode() {
        return playMode;
    }
    
    /**
     * 获取当前歌曲
     * 
     * @return 当前歌曲
     */
    public LocalMusicLibrary.Song getCurrentSong() {
        if (current_index >= 0 && current_index < currentPlaylist.size()) {
            return currentPlaylist.get(current_index);
        }
        return null;
    }
    
    /**
     * 获取当前播放索引
     * 
     * @return 索引
     */
    public int getCurrentIndex() {
        return current_index;
    }
    
    /**
     * 获取当前播放位置（毫秒）
     * 
     * @return 位置
     */
    public int getCurrentPosition() {
        if (mediaPlayer != null && isPlaying) {
            return mediaPlayer.getCurrentPosition();
        }
        return 0;
    }
    
    /**
     * 获取歌曲总时长（毫秒）
     * 
     * @return 时长
     */
    public int getDuration() {
        if (mediaPlayer != null) {
            return mediaPlayer.getDuration();
        }
        return 0;
    }
    
    /**
     * 设置播放位置
     * 
     * @param position 位置（毫秒）
     */
    public void seekTo(int position) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(position);
            Log.d(TAG, "跳转到位置：" + position);
        }
    }
    
    /**
     * 设置音量
     * 
     * @param leftVolume 左声道音量 (0.0 - 1.0)
     * @param rightVolume 右声道音量 (0.0 - 1.0)
     */
    public void setVolume(float leftVolume, float rightVolume) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setVolume(leftVolume, rightVolume);
                Log.d(TAG, "音量已设置：左=" + leftVolume + ", 右=" + rightVolume);
            } catch (Exception e) {
                Log.e(TAG, "设置音量失败", e);
            }
        }
    }
    
    /**
     * 是否正在播放
     * 
     * @return 是否正在播放
     */
    public boolean isPlaying() {
        return isPlaying;
    }
    
    /**
     * 获取播放列表大小
     * 
     * @return 大小
     */
    public int getPlaylistSize() {
        return currentPlaylist.size();
    }
    
    /**
     * 开始进度更新
     */
    private void startProgressUpdate() {
        stopProgressUpdate();  // 先停止之前的更新
        
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && mediaPlayer != null) {
                    int position = mediaPlayer.getCurrentPosition();
                    int duration = mediaPlayer.getDuration();
                    notifyProgressUpdate(position, duration);
                    
                    // 每 500ms 更新一次
                    progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
                }
            }
        };
        
        progressHandler.post(progressRunnable);
    }
    
    /**
     * 停止进度更新
     */
    private void stopProgressUpdate() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }
    
    /**
     * MediaPlayer 准备完成回调
     */
    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.d(TAG, "媒体准备完成");
        
        // 自动开始播放
        play();
        
        // 通知歌曲变化
        LocalMusicLibrary.Song song = getCurrentSong();
        if (song != null) {
            notifySongChanged(song, current_index);
        }
    }
    
    /**
     * MediaPlayer 播放完成回调
     */
    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "播放完成");
        
        notifyPlaybackComplete();
        
        // 自动播放下一首
        if (playMode != PlayMode.SINGLE_LOOP) {
            playNext();
        } else {
            // 单曲循环，重新播放当前歌曲
            play();
        }
    }
    
    /**
     * MediaPlayer 错误回调
     */
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "播放错误：what=" + what + ", extra=" + extra);
        
        String errorMsg = "播放错误：" + what;
        notifyError(errorMsg);
        notifyStateChanged(PlaybackState.STOPPED);
        
        isPlaying = false;
        
        return true;  // 表示已处理错误
    }
    
    /**
     * 释放资源
     */
    public void release() {
        Log.i(TAG, "释放媒体管理器资源");
        
        // 停止播放
        stop();
        
        // 停止进度更新
        stopProgressUpdate();
        
        // 放弃音频焦点
        abandonAudioFocus();
        
        // 释放播放器
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        
        // 清空回调
        callbacks.clear();
        
        // 清空播放列表
        currentPlaylist.clear();
        
        Log.i(TAG, "媒体管理器资源已释放");
    }
}
