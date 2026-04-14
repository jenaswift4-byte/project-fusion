package com.fusion.companion.log;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志数据库助手 — SQLite 只增不删
 *
 * 表结构: logs
 *   - id        INTEGER PRIMARY KEY AUTOINCREMENT
 *   - type      TEXT    (speech_detected / transcript / daily_summary / speaker_enroll / ...)
 *   - speaker   TEXT    (说话人标签, 可为 null)
 *   - content   TEXT    (日志内容)
 *   - timestamp INTEGER (毫秒时间戳)
 *   - synced    INTEGER (0=未同步, 1=已同步)
 *
 * @author Fusion
 * @version 1.0
 */
public class LogDBHelper extends SQLiteOpenHelper {

    private static final String TAG = "LogDBHelper";

    private static final String DB_NAME = "fusion_logs.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_LOGS = "logs";

    // 单例
    private static LogDBHelper instance;

    // 异步写入线程池
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

    public static synchronized LogDBHelper getInstance(Context context) {
        if (instance == null) {
            instance = new LogDBHelper(context.getApplicationContext());
        }
        return instance;
    }

    private LogDBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_LOGS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "type TEXT NOT NULL, " +
            "speaker TEXT, " +
            "content TEXT, " +
            "timestamp INTEGER NOT NULL, " +
            "synced INTEGER DEFAULT 0)";
        db.execSQL(sql);

        // 索引: 按类型和时间查询
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_type ON " + TABLE_LOGS + "(type)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON " + TABLE_LOGS + "(timestamp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_synced ON " + TABLE_LOGS + "(synced)");

        Log.i(TAG, "日志数据库创建完成");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 只增不删，升级时仅添加新列/表
        Log.i(TAG, "数据库升级: " + oldVersion + " → " + newVersion);
    }

    /**
     * 插入日志 (异步)
     */
    public void insertLog(String type, String speaker, String content) {
        writeExecutor.execute(() -> insertLogSync(type, speaker, content));
    }

    /**
     * 插入日志 (同步)
     */
    public long insertLogSync(String type, String speaker, String content) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("type", type);
        values.put("speaker", speaker);
        values.put("content", content);
        values.put("timestamp", System.currentTimeMillis());
        values.put("synced", 0);

        long id = db.insert(TABLE_LOGS, null, values);
        Log.d(TAG, "日志插入: type=" + type + ", id=" + id);
        return id;
    }

    /**
     * 查询指定类型的日志 (增量同步用)
     *
     * @param type      日志类型 (null = 全部)
     * @param sinceId   起始 ID (0 = 从头查)
     * @param limit     最大条数
     * @return JSON 数组
     */
    public JSONArray queryLogs(String type, long sinceId, int limit) {
        JSONArray result = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();

        String selection = "";
        String[] selectionArgs = null;

        if (type != null && sinceId > 0) {
            selection = "type = ? AND id > ?";
            selectionArgs = new String[]{type, String.valueOf(sinceId)};
        } else if (type != null) {
            selection = "type = ?";
            selectionArgs = new String[]{type};
        } else if (sinceId > 0) {
            selection = "id > ?";
            selectionArgs = new String[]{String.valueOf(sinceId)};
        }

        try (Cursor cursor = db.query(TABLE_LOGS, null, selection, selectionArgs,
                null, null, "id ASC", String.valueOf(limit))) {

            while (cursor.moveToNext()) {
                JSONObject entry = new JSONObject();
                entry.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")));
                entry.put("type", cursor.getString(cursor.getColumnIndexOrThrow("type")));
                entry.put("speaker", cursor.isNull(cursor.getColumnIndexOrThrow("speaker")) ? null :
                    cursor.getString(cursor.getColumnIndexOrThrow("speaker")));
                entry.put("content", cursor.getString(cursor.getColumnIndexOrThrow("content")));
                entry.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")));
                entry.put("synced", cursor.getInt(cursor.getColumnIndexOrThrow("synced")));
                result.put(entry);
            }
        } catch (Exception e) {
            Log.e(TAG, "查询日志失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 查询指定日期范围内的日志 (DailySummary 用)
     *
     * @param type       日志类型
     * @param startMs    起始时间 (毫秒)
     * @param endMs      结束时间 (毫秒)
     * @param limit      最大条数
     * @return JSON 数组
     */
    public JSONArray queryLogsByTimeRange(String type, long startMs, long endMs, int limit) {
        JSONArray result = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();

        String selection = "type = ? AND timestamp >= ? AND timestamp <= ?";
        String[] selectionArgs = new String[]{type, String.valueOf(startMs), String.valueOf(endMs)};

        try (Cursor cursor = db.query(TABLE_LOGS, null, selection, selectionArgs,
                null, null, "timestamp ASC", String.valueOf(limit))) {

            while (cursor.moveToNext()) {
                JSONObject entry = new JSONObject();
                entry.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")));
                entry.put("type", cursor.getString(cursor.getColumnIndexOrThrow("type")));
                entry.put("speaker", cursor.isNull(cursor.getColumnIndexOrThrow("speaker")) ? null :
                    cursor.getString(cursor.getColumnIndexOrThrow("speaker")));
                entry.put("content", cursor.getString(cursor.getColumnIndexOrThrow("content")));
                entry.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")));
                result.put(entry);
            }
        } catch (Exception e) {
            Log.e(TAG, "按时间范围查询日志失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 标记已同步
     */
    public void markSynced(long maxId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE " + TABLE_LOGS + " SET synced = 1 WHERE id <= ?", new Object[]{maxId});
        Log.d(TAG, "标记已同步: maxId=" + maxId);
    }

    /**
     * 获取未同步日志数量
     */
    public int getUnsyncedCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_LOGS + " WHERE synced = 0", null)) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        }
        return 0;
    }

    /**
     * 获取最大 ID
     */
    public long getMaxId() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT MAX(id) FROM " + TABLE_LOGS, null)) {
            if (cursor.moveToFirst()) return cursor.getLong(0);
        }
        return 0;
    }
}
