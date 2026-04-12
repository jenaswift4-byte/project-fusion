package com.fusion.companion.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 传感器数据库帮助类
 * 使用原生 SQLite 实现传感器数据和设备状态的存储
 * 
 * 功能特性：
 * - 传感器数据存储（sensor_data 表）
 * - 设备状态管理（device_state 表）
 * - 自动清理 7 天前的旧数据
 * - 索引优化查询性能
 * - 完整的增删改查方法
 * 
 * @author Fusion
 * @version 1.0
 */
public class SensorDatabase extends SQLiteOpenHelper {
    
    private static final String TAG = "SensorDatabase";
    
    // 数据库配置
    private static final String DATABASE_NAME = "fusion_sensors.db";
    private static final int DATABASE_VERSION = 1;
    
    // 传感器数据表配置
    private static final String TABLE_SENSOR_DATA = "sensor_data";
    private static final String COL_ID = "id";
    private static final String COL_DEVICE_ID = "device_id";
    private static final String COL_SENSOR_TYPE = "sensor_type";
    private static final String COL_VALUE = "value";
    private static final String COL_UNIT = "unit";
    private static final String COL_TIMESTAMP = "timestamp";
    
    // 设备状态表配置
    private static final String TABLE_DEVICE_STATE = "device_state";
    private static final String COL_IS_ONLINE = "is_online";
    private static final String COL_LAST_SEEN = "last_seen";
    private static final String COL_BATTERY_LEVEL = "battery_level";
    private static final String COL_MODE = "mode";
    
    // 数据保留天数（7 天）
    private static final int DATA_RETENTION_DAYS = 7;
    
    // 单例实例
    private static SensorDatabase instance;
    
    /**
     * 获取数据库实例（单例模式）
     * @param context 应用上下文
     * @return SensorDatabase 实例
     */
    public static synchronized SensorDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new SensorDatabase(context.getApplicationContext());
        }
        return instance;
    }
    
    /**
     * 私有构造函数
     * @param context 应用上下文
     */
    private SensorDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.i(TAG, "创建数据库表");
        
        // 创建传感器数据表
        String createSensorTable = "CREATE TABLE " + TABLE_SENSOR_DATA + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_DEVICE_ID + " TEXT NOT NULL, " +
                COL_SENSOR_TYPE + " TEXT NOT NULL, " +
                COL_VALUE + " REAL NOT NULL, " +
                COL_UNIT + " TEXT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL" +
                ")";
        db.execSQL(createSensorTable);
        
        // 创建传感器数据表索引（优化查询性能）
        // 按设备 ID 和传感器类型索引
        String indexDeviceSensor = "CREATE INDEX idx_device_sensor ON " + TABLE_SENSOR_DATA + 
                "(" + COL_DEVICE_ID + ", " + COL_SENSOR_TYPE + ")";
        db.execSQL(indexDeviceSensor);
        
        // 按时间戳索引（优化时间范围查询）
        String indexTimestamp = "CREATE INDEX idx_timestamp ON " + TABLE_SENSOR_DATA + 
                "(" + COL_TIMESTAMP + ")";
        db.execSQL(indexTimestamp);
        
        // 按设备 ID 和时间戳复合索引（优化历史数据查询）
        String indexDeviceTime = "CREATE INDEX idx_device_time ON " + TABLE_SENSOR_DATA + 
                "(" + COL_DEVICE_ID + ", " + COL_TIMESTAMP + " DESC)";
        db.execSQL(indexDeviceTime);
        
        // 创建设备状态表
        String createDeviceTable = "CREATE TABLE " + TABLE_DEVICE_STATE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_DEVICE_ID + " TEXT UNIQUE NOT NULL, " +
                COL_IS_ONLINE + " INTEGER NOT NULL, " +
                COL_LAST_SEEN + " INTEGER NOT NULL, " +
                COL_BATTERY_LEVEL + " INTEGER, " +
                COL_MODE + " TEXT" +
                ")";
        db.execSQL(createDeviceTable);
        
        // 创建设备状态表索引（优化设备 ID 查询）
        String indexDeviceId = "CREATE INDEX idx_device_id ON " + TABLE_DEVICE_STATE + 
                "(" + COL_DEVICE_ID + ")";
        db.execSQL(indexDeviceId);
        
        Log.i(TAG, "数据库表创建完成");
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "数据库升级：版本 " + oldVersion + " -> " + newVersion);
        
        // 删除旧表并重新创建（简单策略，生产环境应该迁移数据）
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SENSOR_DATA);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DEVICE_STATE);
        onCreate(db);
    }
    
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "数据库降级：版本 " + oldVersion + " -> " + newVersion);
        onUpgrade(db, oldVersion, newVersion);
    }
    
    // ==================== 传感器数据操作方法 ====================
    
    /**
     * 插入传感器数据
     * @param deviceId 设备 ID
     * @param sensorType 传感器类型（如：temperature, humidity, light 等）
     * @param value 传感器数值
     * @param unit 单位（如：°C, %, lux 等）
     * @return 新插入行的 ID，失败返回 -1
     */
    public long insertSensorData(String deviceId, String sensorType, double value, String unit) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(COL_DEVICE_ID, deviceId);
        values.put(COL_SENSOR_TYPE, sensorType);
        values.put(COL_VALUE, value);
        values.put(COL_UNIT, unit);
        values.put(COL_TIMESTAMP, System.currentTimeMillis());
        
        try {
            long rowId = db.insertOrThrow(TABLE_SENSOR_DATA, null, values);
            Log.d(TAG, "插入传感器数据：" + deviceId + " - " + sensorType + " = " + value + " " + unit);
            
            // 定期清理旧数据（每插入 100 条清理一次）
            if (rowId % 100 == 0) {
                cleanupOldData();
            }
            
            return rowId;
        } catch (Exception e) {
            Log.e(TAG, "插入传感器数据失败", e);
            return -1;
        }
    }
    
    /**
     * 获取最近的传感器数据
     * @param deviceId 设备 ID
     * @param sensorType 传感器类型
     * @param limit 返回数量限制
     * @return 传感器数据列表（按时间倒序）
     */
    public List<SensorData> getRecentSensorData(String deviceId, String sensorType, int limit) {
        SQLiteDatabase db = this.getReadableDatabase();
        List<SensorData> dataList = new ArrayList<>();
        
        String query = "SELECT * FROM " + TABLE_SENSOR_DATA + 
                " WHERE " + COL_DEVICE_ID + " = ? AND " + COL_SENSOR_TYPE + " = ?" +
                " ORDER BY " + COL_TIMESTAMP + " DESC LIMIT ?";
        
        try (Cursor cursor = db.rawQuery(query, new String[]{deviceId, sensorType, String.valueOf(limit)})) {
            if (cursor.moveToFirst()) {
                do {
                    SensorData data = cursorToSensorData(cursor);
                    dataList.add(data);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "查询传感器数据失败", e);
        }
        
        Log.d(TAG, "查询最近传感器数据：" + deviceId + " - " + sensorType + ", 数量：" + dataList.size());
        return dataList;
    }
    
    /**
     * 获取历史数据（指定时间范围）
     * @param deviceId 设备 ID
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime 结束时间（毫秒时间戳）
     * @return 传感器数据列表（按时间正序）
     */
    public List<SensorData> getHistoryData(String deviceId, long startTime, long endTime) {
        SQLiteDatabase db = this.getReadableDatabase();
        List<SensorData> dataList = new ArrayList<>();
        
        String query = "SELECT * FROM " + TABLE_SENSOR_DATA + 
                " WHERE " + COL_DEVICE_ID + " = ? AND " + 
                COL_TIMESTAMP + " BETWEEN ? AND ?" +
                " ORDER BY " + COL_TIMESTAMP + " ASC";
        
        try (Cursor cursor = db.rawQuery(query, new String[]{deviceId, String.valueOf(startTime), String.valueOf(endTime)})) {
            if (cursor.moveToFirst()) {
                do {
                    SensorData data = cursorToSensorData(cursor);
                    dataList.add(data);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "查询历史数据失败", e);
        }
        
        Log.d(TAG, "查询历史数据：" + deviceId + ", 时间范围：" + startTime + " - " + endTime + ", 数量：" + dataList.size());
        return dataList;
    }
    
    /**
     * 获取所有设备的传感器类型列表
     * @param deviceId 设备 ID
     * @return 传感器类型列表
     */
    public List<String> getSensorTypes(String deviceId) {
        SQLiteDatabase db = this.getReadableDatabase();
        List<String> sensorTypes = new ArrayList<>();
        
        String query = "SELECT DISTINCT " + COL_SENSOR_TYPE + " FROM " + TABLE_SENSOR_DATA + 
                " WHERE " + COL_DEVICE_ID + " = ?";
        
        try (Cursor cursor = db.rawQuery(query, new String[]{deviceId})) {
            if (cursor.moveToFirst()) {
                do {
                    String sensorType = cursor.getString(0);
                    sensorTypes.add(sensorType);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "查询传感器类型失败", e);
        }
        
        Log.d(TAG, "查询设备传感器类型：" + deviceId + ", 数量：" + sensorTypes.size());
        return sensorTypes;
    }
    
    /**
     * 删除指定设备的传感器数据
     * @param deviceId 设备 ID
     * @return 删除的行数
     */
    public int deleteSensorData(String deviceId) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        int rowsDeleted = db.delete(TABLE_SENSOR_DATA, COL_DEVICE_ID + " = ?", new String[]{deviceId});
        Log.d(TAG, "删除传感器数据：" + deviceId + ", 删除行数：" + rowsDeleted);
        
        return rowsDeleted;
    }
    
    // ==================== 设备状态操作方法 ====================
    
    /**
     * 更新设备状态（不存在则插入）
     * @param deviceId 设备 ID
     * @param isOnline 是否在线
     * @param batteryLevel 电池电量（0-100，null 表示不支持电池）
     * @param mode 设备模式（如：normal, eco, performance 等）
     * @return 行 ID
     */
    public long updateDeviceState(String deviceId, boolean isOnline, Integer batteryLevel, String mode) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(COL_DEVICE_ID, deviceId);
        values.put(COL_IS_ONLINE, isOnline ? 1 : 0);
        values.put(COL_LAST_SEEN, System.currentTimeMillis());
        
        if (batteryLevel != null) {
            values.put(COL_BATTERY_LEVEL, batteryLevel);
        }
        
        if (mode != null) {
            values.put(COL_MODE, mode);
        }
        
        try {
            // 尝试更新，如果不存在则插入
            int rowsUpdated = db.update(TABLE_DEVICE_STATE, values, COL_DEVICE_ID + " = ?", new String[]{deviceId});
            
            if (rowsUpdated == 0) {
                // 没有更新任何行，说明设备不存在，执行插入
                long rowId = db.insertOrThrow(TABLE_DEVICE_STATE, null, values);
                Log.d(TAG, "插入设备状态：" + deviceId + ", 在线：" + isOnline);
                return rowId;
            } else {
                Log.d(TAG, "更新设备状态：" + deviceId + ", 在线：" + isOnline);
                return -1; // 更新操作不返回具体行 ID
            }
        } catch (Exception e) {
            Log.e(TAG, "更新设备状态失败", e);
            return -1;
        }
    }
    
    /**
     * 获取设备状态
     * @param deviceId 设备 ID
     * @return 设备状态对象，不存在返回 null
     */
    public DeviceState getDeviceState(String deviceId) {
        SQLiteDatabase db = this.getReadableDatabase();
        
        String query = "SELECT * FROM " + TABLE_DEVICE_STATE + " WHERE " + COL_DEVICE_ID + " = ?";
        
        try (Cursor cursor = db.rawQuery(query, new String[]{deviceId})) {
            if (cursor.moveToFirst()) {
                DeviceState state = cursorToDeviceState(cursor);
                Log.d(TAG, "查询设备状态：" + deviceId + ", 在线：" + state.isOnline);
                return state;
            }
        } catch (Exception e) {
            Log.e(TAG, "查询设备状态失败", e);
        }
        
        Log.d(TAG, "设备状态不存在：" + deviceId);
        return null;
    }
    
    /**
     * 获取所有在线设备
     * @return 在线设备状态列表
     */
    public List<DeviceState> getOnlineDevices() {
        SQLiteDatabase db = this.getReadableDatabase();
        List<DeviceState> devices = new ArrayList<>();
        
        String query = "SELECT * FROM " + TABLE_DEVICE_STATE + 
                " WHERE " + COL_IS_ONLINE + " = 1";
        
        try (Cursor cursor = db.rawQuery(query, null)) {
            if (cursor.moveToFirst()) {
                do {
                    DeviceState state = cursorToDeviceState(cursor);
                    devices.add(state);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "查询在线设备失败", e);
        }
        
        Log.d(TAG, "查询在线设备，数量：" + devices.size());
        return devices;
    }
    
    /**
     * 获取所有设备状态
     * @return 所有设备状态列表
     */
    public List<DeviceState> getAllDeviceStates() {
        SQLiteDatabase db = this.getReadableDatabase();
        List<DeviceState> devices = new ArrayList<>();
        
        String query = "SELECT * FROM " + TABLE_DEVICE_STATE;
        
        try (Cursor cursor = db.rawQuery(query, null)) {
            if (cursor.moveToFirst()) {
                do {
                    DeviceState state = cursorToDeviceState(cursor);
                    devices.add(state);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "查询所有设备状态失败", e);
        }
        
        Log.d(TAG, "查询所有设备状态，数量：" + devices.size());
        return devices;
    }
    
    /**
     * 设置设备离线状态
     * @param deviceId 设备 ID
     */
    public void setDeviceOffline(String deviceId) {
        updateDeviceState(deviceId, false, null, null);
    }
    
    // ==================== 数据清理方法 ====================
    
    /**
     * 清理 7 天前的旧数据
     * 自动删除超过保留期限的传感器数据
     */
    public void cleanupOldData() {
        SQLiteDatabase db = this.getWritableDatabase();
        
        long cutoffTime = System.currentTimeMillis() - (DATA_RETENTION_DAYS * 24 * 60 * 60 * 1000L);
        
        int rowsDeleted = db.delete(TABLE_SENSOR_DATA, COL_TIMESTAMP + " < ?", 
                new String[]{String.valueOf(cutoffTime)});
        
        if (rowsDeleted > 0) {
            Log.i(TAG, "清理旧数据完成：删除 " + rowsDeleted + " 条记录（>" + DATA_RETENTION_DAYS + "天前）");
        }
    }
    
    /**
     * 手动清理指定天数前的数据
     * @param days 天数
     * @return 删除的行数
     */
    public int cleanupOldData(int days) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        long cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L);
        
        int rowsDeleted = db.delete(TABLE_SENSOR_DATA, COL_TIMESTAMP + " < ?", 
                new String[]{String.valueOf(cutoffTime)});
        
        Log.i(TAG, "清理旧数据完成：删除 " + rowsDeleted + " 条记录（>" + days + "天前）");
        return rowsDeleted;
    }
    
    /**
     * 清空所有数据（用于调试或重置）
     */
    public void clearAllData() {
        SQLiteDatabase db = this.getWritableDatabase();
        
        db.delete(TABLE_SENSOR_DATA, null, null);
        db.delete(TABLE_DEVICE_STATE, null, null);
        
        Log.w(TAG, "所有数据已清空");
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 将 Cursor 转换为 SensorData 对象
     * @param cursor 数据库游标
     * @return SensorData 对象
     */
    private SensorData cursorToSensorData(Cursor cursor) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
        String deviceId = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEVICE_ID));
        String sensorType = cursor.getString(cursor.getColumnIndexOrThrow(COL_SENSOR_TYPE));
        double value = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_VALUE));
        String unit = cursor.getString(cursor.getColumnIndexOrThrow(COL_UNIT));
        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP));
        
        return new SensorData(id, deviceId, sensorType, value, unit, timestamp);
    }
    
    /**
     * 将 Cursor 转换为 DeviceState 对象
     * @param cursor 数据库游标
     * @return DeviceState 对象
     */
    private DeviceState cursorToDeviceState(Cursor cursor) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
        String deviceId = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEVICE_ID));
        int isOnlineInt = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_ONLINE));
        long lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_SEEN));
        
        int batteryLevelIndex = cursor.getColumnIndex(COL_BATTERY_LEVEL);
        Integer batteryLevel = cursor.isNull(batteryLevelIndex) ? null : cursor.getInt(batteryLevelIndex);
        
        int modeIndex = cursor.getColumnIndex(COL_MODE);
        String mode = cursor.isNull(modeIndex) ? null : cursor.getString(modeIndex);
        
        return new DeviceState(id, deviceId, isOnlineInt == 1, lastSeen, batteryLevel, mode);
    }
    
    // ==================== 数据模型类 ====================
    
    /**
     * 传感器数据模型
     */
    public static class SensorData {
        public long id;
        public String deviceId;
        public String sensorType;
        public double value;
        public String unit;
        public long timestamp;
        
        public SensorData(long id, String deviceId, String sensorType, double value, String unit, long timestamp) {
            this.id = id;
            this.deviceId = deviceId;
            this.sensorType = sensorType;
            this.value = value;
            this.unit = unit;
            this.timestamp = timestamp;
        }
        
        /**
         * 获取格式化的时间戳
         * @return 格式化后的时间字符串
         */
        public String getFormattedTimestamp() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            return sdf.format(new java.util.Date(timestamp));
        }
        
        @Override
        public String toString() {
            return "SensorData{" +
                    "id=" + id +
                    ", deviceId='" + deviceId + '\'' +
                    ", sensorType='" + sensorType + '\'' +
                    ", value=" + value +
                    ", unit='" + unit + '\'' +
                    ", timestamp=" + timestamp +
                    ", formattedTime='" + getFormattedTimestamp() + '\'' +
                    '}';
        }
    }
    
    /**
     * 设备状态模型
     */
    public static class DeviceState {
        public long id;
        public String deviceId;
        public boolean isOnline;
        public long lastSeen;
        public Integer batteryLevel;
        public String mode;
        
        public DeviceState(long id, String deviceId, boolean isOnline, long lastSeen, Integer batteryLevel, String mode) {
            this.id = id;
            this.deviceId = deviceId;
            this.isOnline = isOnline;
            this.lastSeen = lastSeen;
            this.batteryLevel = batteryLevel;
            this.mode = mode;
        }
        
        /**
         * 获取格式化的最后在线时间
         * @return 格式化后的时间字符串
         */
        public String getFormattedLastSeen() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            return sdf.format(new java.util.Date(lastSeen));
        }
        
        /**
         * 获取电池电量百分比描述
         * @return 电量描述字符串
         */
        public String getBatteryDescription() {
            if (batteryLevel == null) {
                return "不支持电池";
            } else if (batteryLevel >= 80) {
                return "电量充足 (" + batteryLevel + "%)";
            } else if (batteryLevel >= 50) {
                return "电量中等 (" + batteryLevel + "%)";
            } else if (batteryLevel >= 20) {
                return "电量较低 (" + batteryLevel + "%)";
            } else {
                return "电量不足 (" + batteryLevel + "%)";
            }
        }
        
        @Override
        public String toString() {
            return "DeviceState{" +
                    "id=" + id +
                    ", deviceId='" + deviceId + '\'' +
                    ", isOnline=" + isOnline +
                    ", lastSeen=" + lastSeen +
                    ", formattedLastSeen='" + getFormattedLastSeen() + '\'' +
                    ", batteryLevel=" + batteryLevel +
                    ", mode='" + mode + '\'' +
                    '}';
        }
    }
}
