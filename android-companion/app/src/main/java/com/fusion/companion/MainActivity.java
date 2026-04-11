package com.fusion.companion;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView statusText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("fusion", Context.MODE_PRIVATE);

        // 构建简易 UI
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("🔀 Project Fusion Companion");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 32);
        layout.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setPadding(0, 0, 0, 24);
        layout.addView(statusText);

        // 通知权限按钮
        Button notifBtn = new Button(this);
        notifBtn.setText("开启通知访问权限");
        notifBtn.setOnClickListener(v -> {
            if (!isNotificationListenerEnabled()) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } else {
                Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(notifBtn);

        // 启动服务按钮
        Button startBtn = new Button(this);
        startBtn.setText("启动 Fusion Bridge 服务");
        startBtn.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, com.fusion.companion.service.FusionBridgeService.class);
            startForegroundService(serviceIntent);
            updateStatus();
        });
        layout.addView(startBtn);

        // 停止服务按钮
        Button stopBtn = new Button(this);
        stopBtn.setText("停止服务");
        stopBtn.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, com.fusion.companion.service.FusionBridgeService.class);
            stopService(serviceIntent);
            updateStatus();
        });
        layout.addView(stopBtn);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean notifEnabled = isNotificationListenerEnabled();
        boolean serviceRunning = FusionBridgeService.isRunning();

        StringBuilder sb = new StringBuilder();
        sb.append("通知访问权限: ").append(notifEnabled ? "✅ 已开启" : "❌ 未开启").append("\n");
        sb.append("Bridge 服务: ").append(serviceRunning ? "✅ 运行中" : "⏹ 已停止").append("\n");

        int port = prefs.getInt("ws_port", 17532);
        sb.append("WebSocket 端口: ").append(port).append("\n");

        // 混合模式状态
        com.fusion.companion.FusionWebSocketServer ws = FusionBridgeService.getWebSocketServer();
        if (ws != null) {
            sb.append("运行模式: ").append(ws.isHybridMode() ? "🔀 混合 (Termux)" : "📡 独立").append("\n");
        }

        statusText.setText(sb.toString());
    }

    private boolean isNotificationListenerEnabled() {
        String pkg = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(pkg);
    }
}
