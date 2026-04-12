package com.fusion.companion.media;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地音乐库
 * 
 * 功能：
 * 1. 扫描本地音乐文件
 * 2. 建立索引（ID3 标签）
 * 3. 支持搜索（按歌名/歌手/专辑）
 * 4. 支持播放列表
 * 
 * 数据格式：
 * - Song: 歌曲信息（ID、标题、歌手、专辑、文件路径、时长）
 * - Artist: 歌手信息
 * - Album: 专辑信息
 * - Playlist: 播放列表
 * 
 * @author Fusion
 * @version 1.0
 */
public class LocalMusicLibrary {
    
    private static final String TAG = "LocalMusicLibrary";
    
    // 上下文
    private Context context;
    
    // 所有歌曲列表
    private List<Song> allSongs;
    
    // 歌手列表
    private List<Artist> allArtists;
    
    // 专辑列表
    private List<Album> allAlbums;
    
    // 播放列表
    private Map<String, Playlist> playlists;
    
    // 异步扫描线程池
    private ExecutorService scanExecutor;
    
    // 是否正在扫描
    private boolean isScanning = false;
    
    // 扫描进度回调
    private ScanProgressCallback scanCallback;
    
    /**
     * 歌曲类
     */
    public static class Song {
        public String id;              // 歌曲 ID
        public String title;           // 歌名
        public String artist;          // 歌手
        public String album;           // 专辑
        public String filePath;        // 文件路径
        public long duration;          // 时长（毫秒）
        public long size;              // 文件大小（字节）
        public int year;               // 年份
        public String genre;           // 流派
        public byte[] albumArt;        // 专辑封面（可选）
        
        public Song() {
            this.year = 0;
            this.genre = "未知";
        }
        
        @Override
        public String toString() {
            return title + " - " + artist;
        }
    }
    
    /**
     * 歌手类
     */
    public static class Artist {
        public String id;              // 歌手 ID
        public String name;            // 歌手名
        public int songCount;          // 歌曲数量
        public List<Song> songs;       // 歌曲列表
        
        public Artist() {
            this.songs = new ArrayList<>();
        }
        
        public void addSong(Song song) {
            this.songs.add(song);
            this.songCount = this.songs.size();
        }
    }
    
    /**
     * 专辑类
     */
    public static class Album {
        public String id;              // 专辑 ID
        public String title;           // 专辑名
        public String artist;          // 歌手
        public int year;               // 年份
        public int songCount;          // 歌曲数量
        public List<Song> songs;       // 歌曲列表
        public byte[] albumArt;        // 专辑封面
        
        public Album() {
            this.songs = new ArrayList<>();
            this.year = 0;
        }
        
        public void addSong(Song song) {
            this.songs.add(song);
            this.songCount = this.songs.size();
            if (this.albumArt == null && song.albumArt != null) {
                this.albumArt = song.albumArt;
            }
        }
    }
    
    /**
     * 播放列表类
     */
    public static class Playlist {
        public String id;              // 播放列表 ID
        public String name;            // 播放列表名
        public List<Song> songs;       // 歌曲列表
        public long totalDuration;     // 总时长
        public int songCount;          // 歌曲数量
        public boolean isAuto;         // 是否自动生成的播放列表
        
        public Playlist() {
            this.songs = new ArrayList<>();
            this.totalDuration = 0;
            this.songCount = 0;
            this.isAuto = false;
        }
        
        public void addSong(Song song) {
            this.songs.add(song);
            this.totalDuration += song.duration;
            this.songCount = this.songs.size();
        }
        
        public void removeSong(Song song) {
            if (this.songs.remove(song)) {
                this.totalDuration -= song.duration;
                this.songCount = this.songs.size();
            }
        }
        
        public void clear() {
            this.songs.clear();
            this.totalDuration = 0;
            this.songCount = 0;
        }
    }
    
    /**
     * 扫描进度回调接口
     */
    public interface ScanProgressCallback {
        /**
         * 扫描开始
         */
        void onScanStart();
        
        /**
         * 扫描进度
         * @param current 当前数量
         * @param total 总数量
         */
        void onScanProgress(int current, int total);
        
        /**
         * 扫描完成
         * @param songCount 歌曲数量
         */
        void onScanComplete(int songCount);
        
        /**
         * 扫描失败
         * @param error 错误信息
         */
        void onScanError(String error);
    }
    
    /**
     * 构造函数
     * 
     * @param context 上下文
     */
    public LocalMusicLibrary(Context context) {
        this.context = context.getApplicationContext();
        this.allSongs = new ArrayList<>();
        this.allArtists = new ArrayList<>();
        this.allAlbums = new ArrayList<>();
        this.playlists = new HashMap<>();
        this.scanExecutor = Executors.newSingleThreadExecutor();
        
        // 创建默认播放列表
        createDefaultPlaylists();
        
        Log.i(TAG, "本地音乐库初始化完成");
    }
    
    /**
     * 创建默认播放列表
     */
    private void createDefaultPlaylists() {
        // 最近添加
        Playlist recent = new Playlist();
        recent.id = "recent";
        recent.name = "最近添加";
        recent.isAuto = true;
        playlists.put("recent", recent);
        
        // 我的收藏
        Playlist favorites = new Playlist();
        favorites.id = "favorites";
        favorites.name = "我的收藏";
        favorites.isAuto = false;
        playlists.put("favorites", favorites);
        
        Log.d(TAG, "默认播放列表已创建");
    }
    
    /**
     * 扫描本地音乐文件（异步）
     */
    public void scanMusicAsync() {
        scanMusicAsync(null);
    }
    
    /**
     * 扫描本地音乐文件（异步，带回调）
     * 
     * @param callback 进度回调
     */
    public void scanMusicAsync(ScanProgressCallback callback) {
        if (isScanning) {
            Log.w(TAG, "正在扫描，跳过");
            return;
        }
        
        this.scanCallback = callback;
        
        scanExecutor.submit(() -> {
            try {
                isScanning = true;
                
                if (scanCallback != null) {
                    postToMain(() -> scanCallback.onScanStart());
                }
                
                Log.i(TAG, "开始扫描本地音乐...");
                
                // 从系统媒体库获取音乐
                List<Song> songs = scanFromMediaStore();
                
                if (songs.isEmpty()) {
                    Log.w(TAG, "未找到音乐文件");
                    if (scanCallback != null) {
                        postToMain(() -> scanCallback.onScanComplete(0));
                    }
                    isScanning = false;
                    return;
                }
                
                // 更新歌曲列表
                allSongs = songs;
                
                // 构建歌手和专辑索引
                buildArtistAndAlbumIndex();
                
                Log.i(TAG, "扫描完成，共 " + songs.size() + " 首歌曲");
                
                if (scanCallback != null) {
                    postToMain(() -> scanCallback.onScanComplete(songs.size()));
                }
                
                isScanning = false;
                
            } catch (Exception e) {
                Log.e(TAG, "扫描失败", e);
                isScanning = false;
                
                if (scanCallback != null) {
                    postToMain(() -> scanCallback.onScanError(e.getMessage()));
                }
            }
        });
    }
    
    /**
     * 从系统媒体库扫描音乐
     * 
     * @return 歌曲列表
     */
    private List<Song> scanFromMediaStore() {
        List<Song> songs = new ArrayList<>();
        
        ContentResolver resolver = context.getContentResolver();
        
        // 查询音频文件
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        
        // 只查询音乐文件
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        
        // 排序（按标题）
        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";
        
        // 查询字段
        String[] projection = new String[] {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        };
        
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, projection, selection, null, sortOrder);
            
            if (cursor == null) {
                Log.e(TAG, "查询返回 null");
                return songs;
            }
            
            int count = cursor.getCount();
            Log.d(TAG, "找到 " + count + " 首歌曲");
            
            int index = 0;
            while (cursor.moveToNext()) {
                Song song = new Song();
                
                // 获取歌曲信息
                song.id = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                song.title = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                song.artist = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                song.album = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                song.duration = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                song.size = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE));
                song.year = cursor.getInt(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR));
                song.filePath = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                
                // 获取专辑封面
                long albumId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                song.albumArt = loadAlbumArt(albumId);
                
                // 处理空值
                if (song.artist == null || song.artist.trim().isEmpty()) {
                    song.artist = "未知歌手";
                }
                if (song.album == null || song.album.trim().isEmpty()) {
                    song.album = "未知专辑";
                }
                
                // 过滤时长过短的文件（< 10 秒）
                if (song.duration < 10000) {
                    continue;
                }
                
                songs.add(song);
                index++;
                
                // 报告进度
                if (scanCallback != null && index % 10 == 0) {
                    final int currentIndex = index;
                    final int totalCount = count;
                    postToMain(() -> scanCallback.onScanProgress(currentIndex, totalCount));
                }
            }
            
            cursor.close();
            
        } catch (Exception e) {
            Log.e(TAG, "扫描音乐失败", e);
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        
        return songs;
    }
    
    /**
     * 加载专辑封面
     * 
     * @param albumId 专辑 ID
     * @return 封面图片字节数组
     */
    private byte[] loadAlbumArt(long albumId) {
        try {
            Uri sArtworkUri = Uri.parse("content://media/external/audio/albumart");
            Uri uri = ContentUris.withAppendedId(sArtworkUri, albumId);
            
            // 简化实现：返回 null
            // 实际应加载图片并转换为字节数组
            return null;
            
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 构建歌手和专辑索引
     */
    private void buildArtistAndAlbumIndex() {
        Map<String, Artist> artistMap = new HashMap<>();
        Map<String, Album> albumMap = new HashMap<>();
        
        for (Song song : allSongs) {
            // 添加到歌手
            String artistKey = song.artist.toLowerCase();
            Artist artist = artistMap.get(artistKey);
            if (artist == null) {
                artist = new Artist();
                artist.id = "artist_" + artistKey;
                artist.name = song.artist;
                artistMap.put(artistKey, artist);
            }
            artist.addSong(song);
            
            // 添加到专辑
            String albumKey = (song.album + "_" + song.artist).toLowerCase();
            Album album = albumMap.get(albumKey);
            if (album == null) {
                album = new Album();
                album.id = "album_" + albumKey;
                album.title = song.album;
                album.artist = song.artist;
                album.year = song.year;
                albumMap.put(albumKey, album);
            }
            album.addSong(song);
        }
        
        allArtists = new ArrayList<>(artistMap.values());
        allAlbums = new ArrayList<>(albumMap.values());
        
        // 排序
        Collections.sort(allArtists, (a, b) -> a.name.compareTo(b.name));
        Collections.sort(allAlbums, (a, b) -> a.title.compareTo(b.title));
        
        Log.d(TAG, "索引构建完成：" + allArtists.size() + " 位歌手，" + allAlbums.size() + " 张专辑");
    }
    
    /**
     * 获取所有歌曲
     * 
     * @return 歌曲列表
     */
    public List<Song> getAllSongs() {
        return allSongs;
    }
    
    /**
     * 获取所有歌手
     * 
     * @return 歌手列表
     */
    public List<Artist> getAllArtists() {
        return allArtists;
    }
    
    /**
     * 获取所有专辑
     * 
     * @return 专辑列表
     */
    public List<Album> getAllAlbums() {
        return allAlbums;
    }
    
    /**
     * 搜索歌曲（按关键词）
     * 
     * @param keyword 关键词
     * @return 匹配的歌曲列表
     */
    public List<Song> searchSongs(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return allSongs;
        }
        
        String lowerKeyword = keyword.toLowerCase();
        List<Song> results = new ArrayList<>();
        
        for (Song song : allSongs) {
            if (song.title.toLowerCase().contains(lowerKeyword) ||
                song.artist.toLowerCase().contains(lowerKeyword) ||
                song.album.toLowerCase().contains(lowerKeyword)) {
                results.add(song);
            }
        }
        
        Log.d(TAG, "搜索 '" + keyword + "' 找到 " + results.size() + " 首歌曲");
        return results;
    }
    
    /**
     * 获取歌手的歌曲列表
     * 
     * @param artistName 歌手名
     * @return 歌曲列表
     */
    public List<Song> getSongsByArtist(String artistName) {
        for (Artist artist : allArtists) {
            if (artist.name.equalsIgnoreCase(artistName)) {
                return artist.songs;
            }
        }
        return new ArrayList<>();
    }
    
    /**
     * 获取专辑的歌曲列表
     * 
     * @param albumTitle 专辑名
     * @return 歌曲列表
     */
    public List<Song> getSongsByAlbum(String albumTitle) {
        for (Album album : allAlbums) {
            if (album.title.equalsIgnoreCase(albumTitle)) {
                return album.songs;
            }
        }
        return new ArrayList<>();
    }
    
    /**
     * 创建播放列表
     * 
     * @param name 播放列表名
     * @return 播放列表
     */
    public Playlist createPlaylist(String name) {
        String id = "playlist_" + System.currentTimeMillis();
        Playlist playlist = new Playlist();
        playlist.id = id;
        playlist.name = name;
        playlist.isAuto = false;
        playlists.put(id, playlist);
        Log.d(TAG, "创建播放列表：" + name);
        return playlist;
    }
    
    /**
     * 获取播放列表
     * 
     * @param id 播放列表 ID
     * @return 播放列表
     */
    public Playlist getPlaylist(String id) {
        return playlists.get(id);
    }
    
    /**
     * 获取所有播放列表
     * 
     * @return 播放列表映射
     */
    public Map<String, Playlist> getAllPlaylists() {
        return playlists;
    }
    
    /**
     * 删除播放列表
     * 
     * @param id 播放列表 ID
     */
    public void deletePlaylist(String id) {
        if (playlists.containsKey(id)) {
            playlists.remove(id);
            Log.d(TAG, "删除播放列表：" + id);
        }
    }
    
    /**
     * 添加到收藏
     * 
     * @param song 歌曲
     */
    public void addToFavorites(Song song) {
        Playlist favorites = playlists.get("favorites");
        if (favorites != null) {
            // 检查是否已存在
            for (Song s : favorites.songs) {
                if (s.id.equals(song.id)) {
                    return;
                }
            }
            favorites.addSong(song);
            Log.d(TAG, "添加到收藏：" + song.title);
        }
    }
    
    /**
     * 从收藏中移除
     * 
     * @param song 歌曲
     */
    public void removeFromFavorites(Song song) {
        Playlist favorites = playlists.get("favorites");
        if (favorites != null) {
            favorites.removeSong(song);
            Log.d(TAG, "从收藏中移除：" + song.title);
        }
    }
    
    /**
     * 获取歌曲数量
     * 
     * @return 歌曲数量
     */
    public int getSongCount() {
        return allSongs.size();
    }
    
    /**
     * 获取歌手数量
     * 
     * @return 歌手数量
     */
    public int getArtistCount() {
        return allArtists.size();
    }
    
    /**
     * 获取专辑数量
     * 
     * @return 专辑数量
     */
    public int getAlbumCount() {
        return allAlbums.size();
    }
    
    /**
     * 是否正在扫描
     * 
     * @return 是否正在扫描
     */
    public boolean isScanning() {
        return isScanning;
    }
    
    /**
     * 清空音乐库
     */
    public void clear() {
        allSongs.clear();
        allArtists.clear();
        allAlbums.clear();
        for (Playlist playlist : playlists.values()) {
            playlist.clear();
        }
        Log.d(TAG, "音乐库已清空");
    }
    
    /**
     * 释放资源
     */
    public void release() {
        Log.i(TAG, "释放音乐库资源");
        
        clear();
        
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        
        Log.i(TAG, "音乐库资源已释放");
    }
    
    /**
     * 在主线程执行
     * 
     * @param runnable 任务
     */
    private void postToMain(Runnable runnable) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(runnable);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(runnable);
        }
    }
}
