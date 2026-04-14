package com.fusion.companion.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import com.fusion.companion.FusionWebSocketServer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 摄像头流传输器 — 手机摄像头 → PC (非 root, Camera2 API)
 *
 * 原理:
 *   Camera2 API 预览 → ImageReader 获取 YUV 帧 → JPEG 压缩 → Base64 → WebSocket → PC 端解码显示
 *
 * 参数:
 *   - 默认分辨率: 640x480 (VGA, 低带宽)
 *   - 默认帧率: ~10 fps (可调)
 *   - JPEG 质量: 60 (可调 1-100)
 *   - 编码: YUV_420_888 → JPEG → Base64
 *
 * 使用方式:
 *   1. PC 端发送 WS 消息: {"type": "camera_control", "action": "start"}
 *   2. 手机打开摄像头并通过 WS 发送 JPEG 帧
 *   3. PC 端发送: {"type": "camera_control", "action": "stop"}
 *   4. 手机关闭摄像头
 *
 * 可选参数:
 *   - "camera_id": 0=后置, 1=前置 (默认 0)
 *   - "width": 分辨率宽度 (默认 640)
 *   - "height": 分辨率高度 (默认 480)
 *   - "quality": JPEG 质量 1-100 (默认 60)
 *   - "fps": 目标帧率 (默认 10)
 *
 * 也可通过 MQTT 命令触发:
 *   fusion/camera/{deviceId}/command → {"action": "start_camera"} / {"action": "stop_camera"}
 */
public class CameraStreamer {

    private static final String TAG = "CameraStreamer";

    // 默认参数
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int DEFAULT_JPEG_QUALITY = 60;
    private static final int DEFAULT_FPS = 10;

    // 状态
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private FusionWebSocketServer wsServer;
    private Context appContext;

    // 当前配置
    private int currentCameraId = 0;  // 0=后置, 1=前置
    private int streamWidth = DEFAULT_WIDTH;
    private int streamHeight = DEFAULT_HEIGHT;
    private int jpegQuality = DEFAULT_JPEG_QUALITY;
    private int targetFps = DEFAULT_FPS;

    // 帧率控制
    private long lastFrameTime = 0;
    private long frameIntervalMs;
    private int frameCount = 0;

    // 权限
    private boolean permissionGranted = false;

    public CameraStreamer(FusionWebSocketServer wsServer) {
        this.wsServer = wsServer;
        this.frameIntervalMs = 1000 / DEFAULT_FPS;
    }

    public void setContext(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean checkPermission(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            permissionGranted = context.checkSelfPermission(
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } else {
            permissionGranted = true;
        }
        if (!permissionGranted) {
            Log.w(TAG, "摄像头权限未授予");
        }
        return permissionGranted;
    }

    /**
     * 开始摄像头流传输
     */
    public boolean startStreaming() {
        return startStreaming(currentCameraId, streamWidth, streamHeight, jpegQuality, targetFps);
    }

    public boolean startStreaming(int cameraId, int width, int height, int quality, int fps) {
        if (isStreaming.get()) {
            Log.w(TAG, "摄像头已在流传输中");
            return false;
        }

        // 动态权限检查
        if (!permissionGranted && appContext != null) {
            checkPermission(appContext);
        }
        if (!permissionGranted) {
            Log.e(TAG, "无摄像头权限");
            notifyStatus("error", "无摄像头权限");
            return false;
        }

        this.currentCameraId = cameraId;
        this.streamWidth = width;
        this.streamHeight = height;
        this.jpegQuality = Math.max(10, Math.min(100, quality));
        this.targetFps = Math.max(1, Math.min(30, fps));
        this.frameIntervalMs = 1000L / this.targetFps;
        this.frameCount = 0;

        // 启动后台线程
        startBackgroundThread();

        // 打开摄像头
        boolean opened = openCamera(cameraId, width, height);
        if (!opened) {
            stopBackgroundThread();
            return false;
        }

        isStreaming.set(true);
        Log.i(TAG, String.format("摄像头流开始 (camera=%d, %dx%d, Q=%d, fps=%d)",
            cameraId, width, height, quality, fps));
        notifyStatus("started", "摄像头流已开始");

        return true;
    }

    /**
     * 停止摄像头流传输
     */
    public void stopStreaming() {
        if (!isStreaming.get()) {
            return;
        }

        isStreaming.set(false);

        closeCamera();
        stopBackgroundThread();

        Log.i(TAG, "摄像头流已停止, 共发送 " + frameCount + " 帧");
        notifyStatus("stopped", "摄像头流已停止");
    }

    /**
     * 切换前后摄像头
     */
    public void switchCamera() {
        if (!isStreaming.get()) {
            return;
        }
        int newId = currentCameraId == 0 ? 1 : 0;
        stopStreaming();
        startStreaming(newId, streamWidth, streamHeight, jpegQuality, targetFps);
    }

    /**
     * 截取当前帧
     */
    public void takeSnapshot() {
        // 下一帧自动触发 snapshot 通知
        // 实际上 ImageReader 的 onImageAvailable 会在下一帧时发送 snapshot 类型
        // 这里通过标志位来标记
        snapshotRequested = true;
        Log.i(TAG, "截图请求已标记");
    }

    private boolean snapshotRequested = false;

    public boolean isStreaming() {
        return isStreaming.get();
    }

    public int getCurrentCameraId() {
        return currentCameraId;
    }

    // ═══════════════════════════════════════════════════════
    // Camera2 API 内部实现
    // ═══════════════════════════════════════════════════════

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("camera_background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private boolean openCamera(int cameraId, int width, int height) {
        CameraManager manager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG, "无法获取 CameraManager");
            notifyStatus("error", "无法获取 CameraManager");
            return false;
        }

        try {
            String[] cameraIds = manager.getCameraIdList();
            if (cameraId >= cameraIds.length) {
                Log.e(TAG, "摄像头 ID 越界: " + cameraId + " (共 " + cameraIds.length + " 个)");
                notifyStatus("error", "摄像头 ID 不存在: " + cameraId);
                return false;
            }

            String cameraIdStr = cameraIds[cameraId];

            // 创建 ImageReader
            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            // 打开摄像头
            manager.openCamera(cameraIdStr, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    Log.i(TAG, "摄像头已打开: " + cameraIdStr);
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.w(TAG, "摄像头断开连接");
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "摄像头打开错误: " + error);
                    camera.close();
                    cameraDevice = null;
                    notifyStatus("error", "摄像头打开失败: error=" + error);
                }
            }, backgroundHandler);

            return true;

        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException: " + e.getMessage());
            notifyStatus("error", "摄像头访问被拒绝");
            return false;
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: " + e.getMessage());
            notifyStatus("error", "摄像头权限被拒绝");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "打开摄像头失败: " + e.getMessage());
            notifyStatus("error", "打开摄像头失败: " + e.getMessage());
            return false;
        }
    }

    private void createCaptureSession() {
        if (cameraDevice == null || imageReader == null) {
            Log.e(TAG, "无法创建捕获会话: cameraDevice 或 imageReader 为 null");
            return;
        }

        try {
            Surface surface = imageReader.getSurface();

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            // 帧率控制
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                new android.util.Range<Integer>(targetFps, targetFps));

            cameraDevice.createCaptureSession(
                Arrays.asList(surface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            builder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            Log.i(TAG, "摄像头捕获会话已配置");
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "设置重复请求失败: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        Log.e(TAG, "捕获会话配置失败");
                        notifyStatus("error", "捕获会话配置失败");
                    }
                },
                backgroundHandler
            );

        } catch (CameraAccessException e) {
            Log.e(TAG, "创建捕获会话失败: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception e) {
                Log.w(TAG, "关闭捕获会话异常: " + e.getMessage());
            }
            captureSession = null;
        }

        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception e) {
                Log.w(TAG, "关闭摄像头异常: " + e.getMessage());
            }
            cameraDevice = null;
        }

        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception e) {
                Log.w(TAG, "关闭 ImageReader 异常: " + e.getMessage());
            }
            imageReader = null;
        }
    }

    // ═══════════════════════════════════════════════════════
    // 帧处理 — YUV_420_888 → JPEG → Base64 → WS
    // ═══════════════════════════════════════════════════════

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = reader -> {
        if (!isStreaming.get()) {
            Image img = reader.acquireLatestImage();
            if (img != null) img.close();
            return;
        }

        // 帧率控制
        long now = System.currentTimeMillis();
        if (now - lastFrameTime < frameIntervalMs) {
            Image img = reader.acquireLatestImage();
            if (img != null) img.close();
            return;
        }
        lastFrameTime = now;

        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            // 验证格式
            int format = image.getFormat();
            if (format != ImageFormat.YUV_420_888) {
                image.close();
                return;
            }

            // YUV → JPEG
            byte[] jpegData = yuvImageToJpeg(image);
            image.close();
            image = null;

            if (jpegData == null || jpegData.length == 0) return;

            // Base64 编码
            String base64Data = Base64.encodeToString(jpegData, Base64.NO_WRAP);

            // 构建 WS 消息
            String msgType = snapshotRequested ? "camera_snapshot" : "camera_frame";
            JSONObject msg = new JSONObject();
            msg.put("type", msgType);
            msg.put("seq", frameCount++);
            msg.put("camera_id", currentCameraId);
            msg.put("width", streamWidth);
            msg.put("height", streamHeight);
            msg.put("quality", jpegQuality);
            msg.put("data", base64Data);
            msg.put("size", jpegData.length);
            msg.put("ts", System.currentTimeMillis());

            if (wsServer != null) {
                wsServer.broadcast(msg.toString());
            }

            if (snapshotRequested) {
                snapshotRequested = false;
                Log.i(TAG, "截图帧已发送: " + jpegData.length + " bytes");
            }

            // 日志节流
            if (frameCount % 100 == 0) {
                Log.d(TAG, "摄像头流: 已发送 " + frameCount + " 帧 (" + jpegData.length + " bytes/frame)");
            }

        } catch (Exception e) {
            Log.e(TAG, "帧处理异常: " + e.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };

    /**
     * YUV_420_888 → JPEG 转换
     */
    private byte[] yuvImageToJpeg(Image image) {
        Image.Plane[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();

        // YUV 数据提取
        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        int yRowStride = yPlane.getRowStride();
        int uvRowStride = uPlane.getRowStride();
        int uvPixelStride = uPlane.getPixelStride();

        // 构建 NV21 格式 (Android YUV→JPEG 标准方式)
        byte[] nv21 = new byte[width * height * 3 / 2];

        // Y 通道
        byte[] yBuffer = new byte[yPlane.getBuffer().remaining()];
        yPlane.getBuffer().get(yBuffer);

        // U 和 V 通道
        byte[] uBuffer = new byte[uPlane.getBuffer().remaining()];
        byte[] vBuffer = new byte[vPlane.getBuffer().remaining()];
        uPlane.getBuffer().get(uBuffer);
        vPlane.getBuffer().get(vBuffer);

        // 填充 Y
        for (int row = 0; row < height; row++) {
            System.arraycopy(yBuffer, row * yRowStride, nv21, row * width, width);
        }

        // 填充 VU (NV21 格式: VUVU...)
        int uvHeight = height / 2;
        int uvWidth = width / 2;
        int nv21Offset = width * height;
        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int uvIndex = row * uvRowStride + col * uvPixelStride;
                nv21[nv21Offset++] = vBuffer[uvIndex];  // V
                nv21[nv21Offset++] = uBuffer[uvIndex];  // U
            }
        }

        // NV21 → JPEG
        try {
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                new Rect(0, 0, width, height),
                jpegQuality,
                out
            );
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "JPEG 压缩失败: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════
    // 状态通知
    // ═══════════════════════════════════════════════════════

    private void notifyStatus(String status, String message) {
        if (wsServer != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "camera_status");
                msg.put("status", status);
                msg.put("message", message);
                msg.put("camera_id", currentCameraId);
                msg.put("ts", System.currentTimeMillis());
                wsServer.broadcast(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "发送状态通知失败: " + e.getMessage());
            }
        }
    }

    /**
     * 获取摄像头列表信息
     */
    public String getCameraInfo() {
        try {
            CameraManager manager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return "{}";

            StringBuilder sb = new StringBuilder("{");
            String[] ids = manager.getCameraIdList();
            for (int i = 0; i < ids.length; i++) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(ids[i]);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                String facingStr = facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back";
                if (i > 0) sb.append(",");
                sb.append("\"").append(i).append("\":{\"id\":\"").append(ids[i])
                  .append("\",\"facing\":\"").append(facingStr).append("\"}");
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 更新 WebSocket Server 引用
     */
    public void updateWsServer(FusionWebSocketServer wsServer) {
        this.wsServer = wsServer;
    }

    /**
     * 释放资源
     */
    public void release() {
        stopStreaming();
        wsServer = null;
        Log.i(TAG, "CameraStreamer 资源已释放");
    }
}
