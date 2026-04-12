package com.fusion.companion.media;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * 媒体功能使用示例
 * 
 * 本文件展示如何使用 TTS、音乐库、媒体播放器和音乐跟随功能
 * 
 * @author Fusion
 * @version 1.0
 */
public class MediaUsageExample extends Activity {
    
    private static final String TAG = "MediaUsageExample";
    
    // 媒体中心实例
    private MediaCenter mediaCenter;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. 初始化媒体中心
        mediaCenter = new MediaCenter(this);
        
        if (!mediaCenter.isInitialized()) {
            Log.e(TAG, "媒体中心初始化失败");
            return;
        }
        
        Log.i(TAG, "媒体中心初始化成功");
        
        // 2. 扫描音乐库
        scanMusicLibrary();
        
        // 3. 配置 TTS
        setupTTS();
        
        // 4. 配置媒体播放器
        setupMediaPlayer();
        
        // 5. 配置音乐跟随
        setupMusicFollower();
    }
    
    /**
     * 示例 1: 扫描音乐库
     */
    private void scanMusicLibrary() {
        LocalMusicLibrary library = mediaCenter.getMusicLibrary();
        
        library.scanMusicAsync(new LocalMusicLibrary.ScanProgressCallback() {
            @Override
            public void onScanStart() {
                Log.i(TAG, "开始扫描音乐库...");
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "开始扫描音乐库", Toast.LENGTH_SHORT).show());
            }
            
            @Override
            public void onScanProgress(int current, int total) {
                Log.d(TAG, "扫描进度：" + current + "/" + total);
            }
            
            @Override
            public void onScanComplete(int songCount) {
                Log.i(TAG, "扫描完成，共 " + songCount + " 首歌曲");
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "扫描完成：" + songCount + " 首歌曲", Toast.LENGTH_SHORT).show());
            }
            
            @Override
            public void onScanError(String error) {
                Log.e(TAG, "扫描失败：" + error);
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "扫描失败：" + error, Toast.LENGTH_SHORT).show());
            }
        });
    }
    
    /**
     * 示例 2: 配置 TTS
     */
    private void setupTTS() {
        TTSManager tts = mediaCenter.getTTSManager();
        
        // 设置语速（1.0 为正常语速）
        tts.setSpeechRate(1.0f);
        
        // 设置音调（1.0 为正常音调）
        tts.setPitch(1.0f);
        
        // 播放欢迎语
        tts.speak("欢迎使用媒体中心");
        
        Log.i(TAG, "TTS 配置完成");
    }
    
    /**
     * 示例 3: 配置媒体播放器
     */
    private void setupMediaPlayer() {
        MediaManager player = mediaCenter.getMediaManager();
        
        // 添加播放状态回调
        player.addCallback(new MediaManager.PlaybackCallback() {
            @Override
            public void onStateChanged(MediaManager.PlaybackState state) {
                Log.d(TAG, "播放状态变化：" + state);
            }
            
            @Override
            public void onProgressUpdate(int position, int duration) {
                // 更新进度条
                // progressSeekBar.setProgress(position);
                // progressSeekBar.setMax(duration);
            }
            
            @Override
            public void onSongChanged(LocalMusicLibrary.Song song, int index) {
                Log.i(TAG, "歌曲切换：" + song.title + " (第 " + (index + 1) + " 首)");
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "正在播放：" + song.title, Toast.LENGTH_SHORT).show());
            }
            
            @Override
            public void onPlaybackComplete() {
                Log.d(TAG, "播放完成");
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "播放错误：" + error);
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "播放错误：" + error, Toast.LENGTH_SHORT).show());
            }
        });
        
        Log.i(TAG, "媒体播放器配置完成");
    }
    
    /**
     * 示例 4: 配置音乐跟随
     */
    private void setupMusicFollower() {
        MusicFollower follower = mediaCenter.getMusicFollower();
        
        // 添加跟随回调
        follower.addCallback(new MusicFollower.FollowerCallback() {
            @Override
            public void onPersonDetected(String fromRoom, String toRoom) {
                Log.i(TAG, "检测到人从 " + fromRoom + " 移动到 " + toRoom);
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "检测到人移动", Toast.LENGTH_SHORT).show());
            }
            
            @Override
            public void onSwitchStart(String fromDevice, String toDevice) {
                Log.i(TAG, "开始切换：从 " + fromDevice + " 到 " + toDevice);
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "开始切换设备", Toast.LENGTH_SHORT).show());
            }
            
            @Override
            public void onSwitchComplete(String fromDevice, String toDevice, boolean success) {
                Log.i(TAG, "切换完成：" + (success ? "成功" : "失败"));
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "切换完成", Toast.LENGTH_SHORT).show());
            }
            
            @Override
            public void onSwitchError(String error) {
                Log.e(TAG, "切换失败：" + error);
                runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                    "切换失败：" + error, Toast.LENGTH_SHORT).show());
            }
        });
        
        // 启用自动跟随
        follower.setAutoFollowEnabled(true);
        
        Log.i(TAG, "音乐跟随配置完成");
    }
    
    /**
     * 示例 5: 播放音乐
     */
    private void playMusic() {
        LocalMusicLibrary library = mediaCenter.getMusicLibrary();
        MediaManager player = mediaCenter.getMediaManager();
        
        // 获取收藏播放列表
        LocalMusicLibrary.Playlist favorites = library.getPlaylist("favorites");
        
        if (favorites != null && !favorites.songs.isEmpty()) {
            // 加载播放列表
            player.loadPlaylist(favorites);
            
            // 设置播放模式（顺序/随机/单曲循环）
            player.setPlayMode(MediaManager.PlayMode.SEQUENCE);
            
            // 开始播放
            player.play();
            
            Log.i(TAG, "开始播放收藏列表");
        } else {
            Log.w(TAG, "收藏列表为空");
        }
    }
    
    /**
     * 示例 6: 使用 AI 回答自动播放 TTS
     */
    private void playAIResponse(String question) {
        TTSManager tts = mediaCenter.getTTSManager();
        
        // 使用 TTS 播放 AI 回答（流式）
        tts.playAIResponseStream(question);
        
        Log.i(TAG, "开始流式播放 AI 回答");
    }
    
    /**
     * 示例 7: 手动切换设备
     */
    private void switchToDevice(String deviceId) {
        MusicFollower follower = mediaCenter.getMusicFollower();
        
        // 手动切换到指定设备
        follower.manualSwitch(deviceId);
        
        Log.i(TAG, "手动切换到设备：" + deviceId);
    }
    
    /**
     * 示例 8: 搜索并播放歌曲
     */
    private void searchAndPlay(String keyword) {
        LocalMusicLibrary library = mediaCenter.getMusicLibrary();
        MediaManager player = mediaCenter.getMediaManager();
        
        // 搜索歌曲
        java.util.List<LocalMusicLibrary.Song> results = library.searchSongs(keyword);
        
        if (!results.isEmpty()) {
            Log.i(TAG, "找到 " + results.size() + " 首歌曲");
            
            // 创建临时播放列表
            LocalMusicLibrary.Playlist tempPlaylist = library.createPlaylist("临时列表");
            for (LocalMusicLibrary.Song song : results) {
                tempPlaylist.addSong(song);
            }
            
            // 加载并播放
            player.loadPlaylist(tempPlaylist);
            player.play();
            
            runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                "找到 " + results.size() + " 首歌曲，开始播放", Toast.LENGTH_SHORT).show());
        } else {
            runOnUiThread(() -> Toast.makeText(MediaUsageExample.this, 
                "未找到歌曲", Toast.LENGTH_SHORT).show());
        }
    }
    
    /**
     * 示例 9: 控制播放
     */
    private void controlPlayback() {
        MediaManager player = mediaCenter.getMediaManager();
        
        // 播放/暂停
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
        
        // 上一首/下一首
        // player.playPrevious();
        // player.playNext();
        
        // 跳转位置
        // player.seekTo(30000);  // 跳转到 30 秒
        
        // 设置音量
        // player.setVolume(1.0f, 1.0f);  // 左右声道均为 100%
        
        // 获取当前播放进度
        int position = player.getCurrentPosition();
        int duration = player.getDuration();
        Log.d(TAG, "播放进度：" + position + "/" + duration);
    }
    
    /**
     * 示例 10: 获取音乐库信息
     */
    private void getLibraryInfo() {
        LocalMusicLibrary library = mediaCenter.getMusicLibrary();
        
        Log.i(TAG, "歌曲数量：" + library.getSongCount());
        Log.i(TAG, "歌手数量：" + library.getArtistCount());
        Log.i(TAG, "专辑数量：" + library.getAlbumCount());
        
        // 获取所有歌曲
        java.util.List<LocalMusicLibrary.Song> allSongs = library.getAllSongs();
        for (LocalMusicLibrary.Song song : allSongs) {
            Log.d(TAG, "歌曲：" + song.title + " - " + song.artist);
        }
        
        // 获取所有歌手
        java.util.List<LocalMusicLibrary.Artist> allArtists = library.getAllArtists();
        for (LocalMusicLibrary.Artist artist : allArtists) {
            Log.d(TAG, "歌手：" + artist.name + " (" + artist.songCount + " 首)");
        }
        
        // 获取所有专辑
        java.util.List<LocalMusicLibrary.Album> allAlbums = library.getAllAlbums();
        for (LocalMusicLibrary.Album album : allAlbums) {
            Log.d(TAG, "专辑：" + album.title + " - " + album.artist);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 释放资源
        if (mediaCenter != null) {
            mediaCenter.release();
            mediaCenter = null;
        }
        
        Log.i(TAG, "资源已释放");
    }
}
