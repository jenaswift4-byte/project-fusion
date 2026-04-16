package com.fusion.companion.llm;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简化版 LLM 引擎（占位实现）
 * 使用关键词提取 + 规则总结，无需 native 库
 * 
 * TODO: 后续替换为真正的 Qwen3.5-0.8B 推理
 */
public class LLMEngineSimple implements LLMEngine {
    private static final String TAG = "LLMEngineSimple";
    
    private boolean isLoaded = false;
    
    // 关键词权重（示例）
    private static final Map<String, Integer> KEYWORD_WEIGHTS = new HashMap<String, Integer>() {{
        put("会议", 10);
        put("工作", 8);
        put("项目", 8);
        put("时间", 7);
        put("地点", 7);
        put("重要", 9);
        put("紧急", 9);
        put("今天", 5);
        put("明天", 5);
        put("下午", 4);
        put("上午", 4);
        put("晚上", 4);
        put("电话", 6);
        put("联系", 6);
        put("邮件", 6);
        put("天气", 4);
        put("吃饭", 3);
        put("回家", 4);
    }};
    
    // 停用词
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
        "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
        "自己", "这", "那", "什么", "他", "她", "它", "这个", "那个", "这里", "那里"
    ));
    
    /**
     * 加载模型（占位）
     */
    public synchronized boolean loadModel(String modelPath) {
        Log.i(TAG, "简化版 LLM 引擎初始化（关键词提取模式）");
        isLoaded = true;
        return true;
    }
    
    /**
     * 文本推理（关键词提取 + 规则总结）
     */
    public synchronized String infer(String prompt, int maxTokens) {
        if (!isLoaded) {
            Log.e(TAG, "引擎未初始化");
            return null;
        }
        
        // 提取实际文本内容
        String content = extractContent(prompt);
        if (content == null || content.isEmpty()) {
            return "无有效内容";
        }
        
        // 关键词提取
        List<String> keywords = extractKeywords(content);
        
        // 时间实体识别
        List<String> timeEntities = extractTimeEntities(content);
        
        // 生成总结
        String summary = generateSummary(content, keywords, timeEntities);
        
        return summary;
    }
    
    /**
     * 多模态推理（占位）
     */
    public synchronized String inferMultimodal(String prompt, String imagePath, int maxTokens) {
        // TODO: 实现图像分析
        Log.w(TAG, "多模态推理暂未实现，返回文本推理结果");
        return "图像分析功能待集成 Qwen-VL 模型。当前图像: " + imagePath;
    }
    
    /**
     * 释放资源（占位）
     */
    public synchronized void free() {
        isLoaded = false;
        Log.i(TAG, "简化版 LLM 引擎已释放");
    }
    
    public boolean isLoaded() {
        return isLoaded;
    }
    
    // ========== 私有方法 ==========
    
    /**
     * 从 prompt 中提取实际内容
     */
    private String extractContent(String prompt) {
        // 提取 <|im_start|>user 和 <|im_end|> 之间的内容
        Pattern pattern = Pattern.compile("<\\|im_start\\|>user\\n(.*?)\\n<\\|im_end\\|>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(prompt);
        
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        // 如果没有 prompt 格式，直接返回原文
        return prompt;
    }
    
    /**
     * 关键词提取
     */
    private List<String> extractKeywords(String text) {
        List<String> keywords = new ArrayList<>();
        Map<String, Integer> scores = new HashMap<>();
        
        // 简单分词（按空格和标点）
        String[] words = text.split("[\\s,。！？、；：\"\"''（）【】]");
        
        for (String word : words) {
            if (word.length() < 2 || STOP_WORDS.contains(word)) {
                continue;
            }
            
            // 计算得分
            int score = KEYWORD_WEIGHTS.getOrDefault(word, 1);
            scores.put(word, scores.getOrDefault(word, 0) + score);
        }
        
        // 按得分排序，取前 5 个
        scores.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(5)
            .forEach(entry -> keywords.add(entry.getKey()));
        
        return keywords;
    }
    
    /**
     * 时间实体识别
     */
    private List<String> extractTimeEntities(String text) {
        List<String> entities = new ArrayList<>();
        
        // 匹配时间模式
        Pattern[] timePatterns = {
            Pattern.compile("\\d{1,2}月\\d{1,2}日"),  // 4月16日
            Pattern.compile("\\d{1,2}点\\d{0,2}分?"),  // 3点30分
            Pattern.compile("今天|明天|后天|昨天"),  // 相对日期
            Pattern.compile("上午|下午|晚上|中午|早上|傍晚"),  // 时间段
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}")  // 2026-04-16
        };
        
        for (Pattern pattern : timePatterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                entities.add(matcher.group());
            }
        }
        
        return entities;
    }
    
    /**
     * 生成总结
     */
    private String generateSummary(String content, List<String> keywords, List<String> timeEntities) {
        StringBuilder summary = new StringBuilder();
        
        // 添加时间信息
        if (!timeEntities.isEmpty()) {
            summary.append("时间：").append(String.join("、", timeEntities)).append("。");
        }
        
        // 添加关键词
        if (!keywords.isEmpty()) {
            summary.append("关键词：").append(String.join("、", keywords)).append("。");
        }
        
        // 添加内容摘要（前 50 字）
        if (content.length() > 50) {
            summary.append("内容：").append(content.substring(0, 50)).append("...");
        } else {
            summary.append("内容：").append(content);
        }
        
        return summary.toString();
    }
}
