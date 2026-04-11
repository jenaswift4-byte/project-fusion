package com.fusion.companion;

import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

/**
 * Fusion WebSocket Server (兼容模式)
 *
 * 模式 A — 独立模式: 自己跑 WS Server (端口 17532)，PC 直连
 * 模式 B — 混合模式: Termux Bridge 已在跑 WS Server，本 App 作为 WS Client 连接
 *          通知/来电事件通过 Termux Bridge 转发给 PC
 *
 * 自动检测: 启动时尝试连接 127.0.0.1:17532
 *   - 如果连接失败 (端口无人监听) → 模式 A (独立模式)
 *   - 如果连接成功 (Termux Bridge 已在运行) → 模式 B (混合模式)
 */
public class FusionWebSocketServer extends WebSocketServer {

    private static final String TAG = "FusionWS";
    private static final int DEFAULT_PORT = 17532;

    private EventListener eventListener;

    // 混合模式: 作为 Client 连接到 Termux Bridge
    private WebSocketClient termuxClient;
    private boolean hybridMode = false;

    public interface EventListener {
        void onClientMessage(String type, String data);
    }

    public FusionWebSocketServer(InetSocketAddress address) {
        super(address);
    }

    public void setEventListener(EventListener listener) {
        this.eventListener = listener;
    }

    /**
     * 检测 Termux Bridge 是否在运行，决定模式
     * 在后台线程调用
     */
    public void detectMode() {
        try {
            // 尝试连接本地 WS Server (Termux Bridge)
            URI uri = new URI("ws://127.0.0.1:" + DEFAULT_PORT);
            termuxClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    hybridMode = true;
                    Log.i(TAG, "✅ 混合模式: 已连接到 Termux Bridge");
                    // 停止自己的 Server (端口冲突)
                    try {
                        FusionWebSocketServer.this.stop();
                    } catch (Exception e) {
                        Log.d(TAG, "停止独立 Server: " + e.getMessage());
                    }
                    // 发送标识消息，让 Termux Bridge 知道这是伴侣 App
                    try {
                        org.json.JSONObject hello = new org.json.JSONObject();
                        hello.put("type", "companion_hello");
                        hello.put("device", android.os.Build.MODEL);
                        hello.put("androidVersion", android.os.Build.VERSION.RELEASE);
                        hello.put("sdkVersion", android.os.Build.VERSION.SDK_INT);
                        termuxClient.send(hello.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "发送标识消息失败", e);
                    }
                }

                @Override
                public void onMessage(String message) {
                    // Termux Bridge 转发的 PC 命令
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(message);
                        String type = json.optString("type", "");
                        if (eventListener != null) {
                            eventListener.onClientMessage(type, message);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析 Termux 消息失败", e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (hybridMode) {
                        hybridMode = false;
                        Log.w(TAG, "Termux Bridge 断开，回退到独立模式");
                        // 回退: 重启自己的 Server
                        try {
                            FusionWebSocketServer.this.start();
                        } catch (Exception e) {
                            Log.e(TAG, "重启独立 Server 失败", e);
                        }
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Log.d(TAG, "Termux Bridge 连接失败 (可能未运行): " + ex.getMessage());
                }
            };

            termuxClient.setConnectionLostTimeout(10);
            termuxClient.connectBlocking(3, java.util.concurrent.TimeUnit.SECONDS);

            if (!hybridMode) {
                // 连接超时/失败 → 独立模式
                termuxClient = null;
                Log.i(TAG, "📡 独立模式: 未检测到 Termux Bridge");
            }

        } catch (Exception e) {
            hybridMode = false;
            termuxClient = null;
            Log.i(TAG, "📡 独立模式: " + e.getMessage());
        }
    }

    /**
     * 广播消息 — 根据模式选择通道
     * 独立模式: 广播给所有连接的 PC 客户端
     * 混合模式: 发送给 Termux Bridge (由它转发给 PC)
     */
    @Override
    public void broadcast(String text) {
        if (hybridMode && termuxClient != null && termuxClient.isOpen()) {
            // 混合模式: 发给 Termux Bridge
            termuxClient.send(text);
        } else {
            // 独立模式: 广播给所有 PC 客户端
            super.broadcast(text);
        }
    }

    // === Server 回调 (独立模式) ===

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.i(TAG, "PC 已连接: " + conn.getRemoteSocketAddress());
        try {
            org.json.JSONObject welcome = new org.json.JSONObject();
            welcome.put("type", "connected");
            welcome.put("device", android.os.Build.MODEL);
            welcome.put("androidVersion", android.os.Build.VERSION.RELEASE);
            welcome.put("sdkVersion", android.os.Build.VERSION.SDK_INT);
            welcome.put("bridge", "companion");  // 标识来源
            conn.send(welcome.toString());
        } catch (Exception e) {
            Log.e(TAG, "发送欢迎消息失败", e);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.i(TAG, "PC 已断开: " + (remote ? "远程" : "本地") + " code=" + code);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Log.d(TAG, "收到 PC 消息: " + message.substring(0, Math.min(100, message.length())));
        try {
            org.json.JSONObject json = new org.json.JSONObject(message);
            String type = json.optString("type", "");
            if (eventListener != null) {
                eventListener.onClientMessage(type, message);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析消息失败", e);
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        Log.d(TAG, "收到二进制消息: " + message.remaining() + " bytes");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "WebSocket 错误", ex);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "WebSocket Server 已启动: " + getAddress());
        setConnectionLostTimeout(10);
    }

    /**
     * 停止 — 清理所有连接
     */
    public void stopAll() {
        try {
            if (termuxClient != null) {
                termuxClient.close();
            }
        } catch (Exception e) {
            Log.d(TAG, "关闭 Termux Client: " + e.getMessage());
        }
        try {
            this.stop();
        } catch (Exception e) {
            Log.d(TAG, "停止 Server: " + e.getMessage());
        }
    }

    public boolean isHybridMode() {
        return hybridMode;
    }
}
