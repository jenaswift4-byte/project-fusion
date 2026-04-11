package com.fusion.companion.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.fusion.companion.FusionWebSocketServer;

import org.json.JSONObject;

/**
 * 通知监听服务
 * 实时捕获所有应用的通知并通过 WebSocket 推送到 PC
 */
public class FusionNotificationListener extends NotificationListenerService {

    private static final String TAG = "FusionNotification";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();

            // 跳过系统通知
            if (pkg.startsWith("com.android.systemui") ||
                pkg.startsWith("com.android.launcher") ||
                pkg.equals("android") ||
                pkg.startsWith("com.android.packageinstaller")) {
                return;
            }

            // 跳过自己的通知
            if (pkg.equals("com.fusion.companion")) {
                return;
            }

            android.app.Notification notification = sbn.getNotification();
            if (notification == null) return;

            // 提取通知内容
            String title = "";
            String text = "";
            String bigText = "";

            if (notification.extras != null) {
                CharSequence titleSeq = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
                CharSequence textSeq = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
                CharSequence bigTextSeq = notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);

                if (titleSeq != null) title = titleSeq.toString();
                if (textSeq != null) text = textSeq.toString();
                if (bigTextSeq != null) bigText = bigTextSeq.toString();
            }

            // 构建事件
            JSONObject msg = new JSONObject();
            msg.put("type", "notification");
            msg.put("package", pkg);
            msg.put("title", title);
            msg.put("text", bigText.isEmpty() ? text : bigText);
            msg.put("key", sbn.getKey());
            msg.put("isOngoing", sbn.isOngoing());
            msg.put("timestamp", sbn.getPostTime());

            // 通过 WebSocket 广播
            FusionWebSocketServer server = getWebSocketServer();
            if (server != null) {
                server.broadcast(msg.toString());
                Log.d(TAG, "通知推送: [" + pkg + "] " + title);
            }

        } catch (Exception e) {
            Log.e(TAG, "处理通知失败", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 通知被移除，可选: 通知 PC
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "notification_removed");
            msg.put("package", sbn.getPackageName());
            msg.put("key", sbn.getKey());

            FusionWebSocketServer server = getWebSocketServer();
            if (server != null) {
                server.broadcast(msg.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "处理通知移除失败", e);
        }
    }

    private FusionWebSocketServer getWebSocketServer() {
        return FusionBridgeService.getWebSocketServer();
    }
}
