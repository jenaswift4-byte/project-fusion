package com.fusion.companion.log;

import android.content.Context;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fusion.companion.service.MQTTClientService;

/**
 * 日志同步服务 (Android 端)
 *
 * 订阅 MQTT log/sync 主题, 收到 PC 端同步请求后,
 * 查询增量日志并通过 MQTT 返回 JSON。
 *
 * 协议:
 *   PC → 手机: fusion/log/{deviceId}/sync  {"action": "sync", "since_id": 123}
 *   手机 → PC: fusion/log/{deviceId}/data   {"logs": [...], "max_id": 456}
 *
 * @author Fusion
 * @version 1.0
 */
public class LogSyncService {

    private static final String TAG = "LogSync";

    private final Context appContext;
    private final LogDBHelper logHelper;
    private MQTTClientService mqttService;

    public LogSyncService(Context context) {
        this.appContext = context.getApplicationContext();
        this.logHelper = LogDBHelper.getInstance(context);
    }

    /**
     * 设置 MQTT 客户端引用
     */
    public void setMqttService(MQTTClientService mqttService) {
        this.mqttService = mqttService;
    }

    /**
     * 处理同步请求 (由 MQTTClientService 回调)
     *
     * @param payload MQTT 消息内容
     */
    public void handleSyncRequest(String payload) {
        try {
            JSONObject req = new JSONObject(payload);
            String action = req.optString("action", "");
            long sinceId = req.optLong("since_id", 0);
            int limit = req.optInt("limit", 100);

            if (!"sync".equals(action)) {
                Log.w(TAG, "未知日志同步动作: " + action);
                return;
            }

            Log.i(TAG, "日志同步请求: since_id=" + sinceId + ", limit=" + limit);

            // 查询增量日志
            JSONArray logs = logHelper.queryLogs(null, sinceId, limit);
            long maxId = logHelper.getMaxId();

            // 构建响应
            JSONObject response = new JSONObject();
            response.put("logs", logs);
            response.put("max_id", maxId);
            response.put("count", logs.length());
            response.put("timestamp", System.currentTimeMillis());

            // 通过 MQTT 返回
            String deviceId = mqttService != null ? mqttService.getMqttDeviceId() : "unknown";
            String topic = "fusion/log/" + deviceId + "/data";

            if (mqttService != null && mqttService.isConnected()) {
                mqttService.publishTextMessage(topic, response.toString(), 1);
                Log.i(TAG, "日志同步响应已发送: " + logs.length() + " 条");

                // 标记已同步
                if (maxId > 0) {
                    logHelper.markSynced(maxId);
                }
            } else {
                Log.w(TAG, "MQTT 未连接, 无法发送日志同步响应");
            }

        } catch (Exception e) {
            Log.e(TAG, "处理日志同步请求失败: " + e.getMessage());
        }
    }
}
