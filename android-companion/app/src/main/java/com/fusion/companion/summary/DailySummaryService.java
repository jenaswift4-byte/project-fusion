package com.fusion.companion.summary;

import android.content.Context;
import android.util.Log;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.fusion.companion.log.LogDBHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * 每日 LLM 摘要服务
 *
 * 工作流程:
 *   1. 每日 23:59 通过 WorkManager 触发
 *   2. 查询当天 transcript 记录 (最多 30 条)
 *   3. 使用 Qwen 3.5 2.1B (4-bit GGUF) 通过 llama.cpp 推理
 *   4. 提示词: 总结今日主要话题 + 整体氛围
 *   5. 结果写入日志 (type=daily_summary)
 *
 * 注意: llama.cpp 需要 NPU/CPU 推理, 模型文件约 1.2GB (4-bit GGUF)
 *
 * @author Fusion
 * @version 1.0
 */
public class DailySummaryService {

    private static final String TAG = "DailySummary";

    private static final String WORK_NAME = "daily_summary_work";

    // 摘要提示词模板
    private static final String PROMPT_TEMPLATE =
        "以下是一天中的语音记录片段，每条格式为\"[说话人]说：[内容]\"。" +
        "请用一句中文总结今日主要话题，再用一句描述整体氛围。" +
        "只输出这两句话，不要解释。\n\n%s";

    // llama.cpp 相关
    private volatile boolean llamaInitialized = false;
    private long llamaModelPtr = 0; // llama_model 指针

    private final Context appContext;
    private final LogDBHelper logHelper;

    public DailySummaryService(Context context) {
        this.appContext = context.getApplicationContext();
        this.logHelper = LogDBHelper.getInstance(context);
    }

    // ==================== WorkManager 调度 ====================

    /**
     * 注册每日摘要定时任务 (23:59 执行)
     * 在应用启动时调用
     */
    public void scheduleDailySummary() {
        // 计算距离下次 23:59 的延迟
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 0);

        // 如果今天已过, 推到明天
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }

        long initialDelayMs = calendar.getTimeInMillis() - System.currentTimeMillis();

        Log.i(TAG, "每日摘要定时任务已注册, 首次执行: " + initialDelayMs / 3600000 + "h 后");

        // 使用 PeriodicWorkRequest (24 小时周期)
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                SummaryWorker.class,
                24, TimeUnit.HOURS,
                1, TimeUnit.HOURS  // 弹性窗口 1 小时
            )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder()
                .setRequiresBatteryNotLow(true)  // 低电量不执行
                .build())
            .setInputData(new Data.Builder()
                .putString("prompt_template", PROMPT_TEMPLATE)
                .build())
            .build();

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // 不重复注册
            workRequest
        );

        Log.i(TAG, "WorkManager 任务已入队: " + WORK_NAME);
    }

    /**
     * 取消定时任务
     */
    public void cancelDailySummary() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME);
        Log.i(TAG, "每日摘要定时任务已取消");
    }

    /**
     * 手动触发摘要 (测试用)
     */
    public void triggerManualSummary() {
        generateSummary();
    }

    // ==================== 摘要生成 ====================

    /**
     * 生成每日摘要
     */
    private void generateSummary() {
        try {
            Log.i(TAG, "开始生成每日摘要...");

            // 1. 计算今天的时间范围
            Calendar calStart = Calendar.getInstance();
            calStart.set(Calendar.HOUR_OF_DAY, 0);
            calStart.set(Calendar.MINUTE, 0);
            calStart.set(Calendar.SECOND, 0);
            long startMs = calStart.getTimeInMillis();

            Calendar calEnd = Calendar.getInstance();
            long endMs = calEnd.getTimeInMillis();

            // 2. 查询今天的 transcript 记录
            JSONArray transcripts = logHelper.queryLogsByTimeRange("transcript", startMs, endMs, 30);

            if (transcripts.length() == 0) {
                Log.i(TAG, "今天没有语音记录, 跳过摘要");
                return;
            }

            // 3. 格式化记录
            StringBuilder records = new StringBuilder();
            for (int i = 0; i < transcripts.length(); i++) {
                JSONObject entry = transcripts.getJSONObject(i);
                String speaker = entry.optString("speaker", "未知");
                String content = entry.optString("content", "");
                if (!content.isEmpty()) {
                    records.append("[").append(speaker).append("]说：").append(content).append("\n");
                }
            }

            if (records.length() == 0) {
                Log.i(TAG, "没有有效语音内容, 跳过摘要");
                return;
            }

            // 4. 构建提示词
            String prompt = String.format(PROMPT_TEMPLATE, records.toString());
            Log.d(TAG, "提示词长度: " + prompt.length() + " 字符");

            // 5. 执行推理
            String summary = runInference(prompt);

            if (summary != null && !summary.trim().isEmpty()) {
                Log.i(TAG, "每日摘要: " + summary);

                // 6. 写入日志
                logHelper.insertLog("daily_summary", null, summary);
            } else {
                Log.w(TAG, "摘要生成失败 (模型返回空)");
            }

        } catch (Exception e) {
            Log.e(TAG, "生成摘要失败: " + e.getMessage(), e);
        }
    }

    // ==================== llama.cpp 推理 ====================

    /**
     * 使用 llama.cpp 执行推理
     *
     * TODO: 集成 llama-android AAR 后实现 JNI 调用
     * 当前为存根实现
     */
    private String runInference(String prompt) {
        if (!llamaInitialized) {
            Log.w(TAG, "llama.cpp 未初始化, 使用存根摘要");
            return stubSummary(prompt);
        }

        // TODO: JNI 调用 llama.cpp
        // 1. llama_tokenize(prompt)
        // 2. llama_decode(tokens)
        // 3. llama_sample() 循环生成
        // 4. 返回生成的文本

        return stubSummary(prompt);
    }

    /**
     * 存根摘要: 提取关键信息
     */
    private String stubSummary(String prompt) {
        try {
            JSONArray transcripts = new JSONObject()
                .put("records", prompt); // 简单处理

            int recordCount = prompt.split("\n").length;
            return String.format("今日共有 %d 条语音记录，日常交流正常。", recordCount);
        } catch (Exception e) {
            return "今日语音摘要生成失败 (模型未集成)";
        }
    }

    /**
     * 初始化 llama.cpp 模型
     *
     * @param modelPath GGUF 模型文件路径
     * @return true 如果初始化成功
     */
    public boolean initLlamaModel(String modelPath) {
        // TODO: llama-android JNI 初始化
        // llama_model_params params = llama_model_default_params();
        // params.n_gpu_layers = detectNpuSupport() ? 99 : 0;
        // llamaModelPtr = llama_load_model_from_file(modelPath, params);
        // llamaInitialized = (llamaModelPtr != 0);

        Log.i(TAG, "llama.cpp 模型初始化 (存根): " + modelPath);
        llamaInitialized = false; // 暂时无法初始化
        return false;
    }

    /**
     * 检测 NPU 支持
     */
    private boolean detectNpuSupport() {
        // TODO: 检测 NNAPI / 高通 Hexagon / 联发科 APU
        // 目前保守返回 false
        return false;
    }

    // ==================== WorkManager Worker ====================

    /**
     * WorkManager Worker: 后台执行摘要生成
     */
    public static class SummaryWorker extends Worker {

        private static final String TAG = "SummaryWorker";

        public SummaryWorker(Context context, WorkerParameters params) {
            super(context, params);
        }

        @Override
        public androidx.work.ListenableWorker.Result doWork() {
            Log.i(TAG, "每日摘要 Worker 开始执行");

            try {
                DailySummaryService service = new DailySummaryService(getApplicationContext());
                service.triggerManualSummary();
                return Result.success();
            } catch (Exception e) {
                Log.e(TAG, "Worker 执行失败: " + e.getMessage());
                return Result.retry();
            }
        }
    }
}
