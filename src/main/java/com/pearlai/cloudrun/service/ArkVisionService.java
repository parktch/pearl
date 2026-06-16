package com.pearlai.cloudrun.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pearlai.cloudrun.config.ArkProperties;
import com.pearlai.cloudrun.dto.ImageInput;
import com.pearlai.cloudrun.dto.PearlAnalyzeRequest;
import com.pearlai.cloudrun.dto.PearlReport;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ArkVisionService {

    private static final Map<String, GradeInfo> GRADE_INFO = new HashMap<String, GradeInfo>();

    static {
        GRADE_INFO.put("A", new GradeInfo("无瑕", "肉眼观察极难见瑕疵"));
        GRADE_INFO.put("B", new GradeInfo("微瑕", "极少针点状瑕疵，肉眼较难发现"));
        GRADE_INFO.put("C", new GradeInfo("小瑕", "较小瑕疵，肉眼易见"));
        GRADE_INFO.put("D", new GradeInfo("瑕疵", "明显瑕疵，占表面积 1/4 以下"));
        GRADE_INFO.put("E", new GradeInfo("重瑕", "严重瑕疵，占表面积 1/4 以上"));
    }

    private final ArkProperties arkProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ArkVisionService(ArkProperties arkProperties, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.arkProperties = arkProperties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public interface StreamProgressListener {
        void onStart(String message);

        void onDelta(String content, String reasoning);

        void onReport(PearlReport report);
    }

    public PearlReport analyze(PearlAnalyzeRequest request) {
        validateRequest(request);
        Map<String, Object> arkBody = buildArkBody(request, false);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(arkProperties.getApiKey());

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    arkProperties.getApiUrl(),
                    new HttpEntity<Map<String, Object>>(arkBody, headers),
                    Map.class
            );
            String content = extractContent(response.getBody());
            return normalizeReport(content);
        } catch (HttpStatusCodeException error) {
            throw new IllegalStateException("Ark API error " + error.getStatusCode().value() + ": " + error.getResponseBodyAsString(), error);
        }
    }

    public void streamAnalyze(PearlAnalyzeRequest request, OutputStream outputStream) {
        try {
            final int[] deltaCount = new int[]{0};
            analyzeWithProgress(request, new StreamProgressListener() {
                @Override
                public void onStart(String message) {
                    emitStreamEvent(outputStream, "start", "正在读取图片，准备开始珍珠初筛。", null, "", "正在读取图片，准备开始珍珠初筛。");
                }

                @Override
                public void onDelta(String content, String reasoning) {
                    deltaCount[0] += 1;
                    String readableText = buildReadableStreamProgress(deltaCount[0]);
                    emitStreamEvent(
                            outputStream,
                            "delta",
                            readableText,
                            null,
                            "",
                            readableText
                    );
                }

                @Override
                public void onReport(PearlReport report) {
                    emitStreamEvent(outputStream, "report", "分析完成，正在生成最终鉴定报告。", report, "", "分析完成，正在生成最终鉴定报告。");
                }
            });
        } catch (Exception error) {
            emitErrorEvent(outputStream, "鉴定服务暂时不可用，请稍后重试。");
        }
    }

    public PearlReport analyzeWithProgress(PearlAnalyzeRequest request, StreamProgressListener listener) {
        validateRequest(request);
        StringBuilder answerText = new StringBuilder();
        StringBuilder reasoningText = new StringBuilder();
        if (listener != null) {
            listener.onStart("正在鉴定图片，请稍候...");
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(arkProperties.getApiUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(arkProperties.getTimeoutMs());
            connection.setReadTimeout(arkProperties.getTimeoutMs());
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + arkProperties.getApiKey());

            Map<String, Object> arkBody = buildArkBody(request, true);
            OutputStream requestStream = connection.getOutputStream();
            objectMapper.writeValue(requestStream, arkBody);
            requestStream.flush();
            requestStream.close();

            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (statusCode >= 400) {
                String errorText = readAll(responseStream);
                throw new IllegalStateException("Ark API error " + statusCode + ": " + errorText);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, String> delta = parseArkStreamDelta(line);
                if (delta == null) {
                    continue;
                }
                String content = delta.get("content");
                String reasoning = delta.get("reasoning");
                if (StringUtils.hasText(content)) {
                    answerText.append(content);
                }
                if (StringUtils.hasText(reasoning)) {
                    reasoningText.append(reasoning);
                }
                if (listener != null && (StringUtils.hasText(content) || StringUtils.hasText(reasoning))) {
                    listener.onDelta(content, reasoning);
                }
            }

            String finalText = StringUtils.hasText(answerText.toString()) ? answerText.toString() : reasoningText.toString();
            PearlReport report = normalizeReport(finalText);
            if (listener != null) {
                listener.onReport(report);
            }
            return report;
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage(), error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void validateRequest(PearlAnalyzeRequest request) {
        if (!StringUtils.hasText(arkProperties.getApiKey())) {
            throw new IllegalStateException("ARK_API_KEY is not configured");
        }
        if (request == null || request.getImages() == null || request.getImages().isEmpty()) {
            throw new IllegalArgumentException("至少需要上传 1 张图片");
        }
        if (request.getImages().size() > 5) {
            throw new IllegalArgumentException("一次最多支持 5 张图片");
        }
    }

    private Map<String, Object> buildArkBody(PearlAnalyzeRequest request, boolean stream) {
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        for (ImageInput image : request.getImages()) {
            Map<String, Object> imageUrl = new LinkedHashMap<String, Object>();
            imageUrl.put("url", normalizeImageUrl(image));

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("type", "image_url");
            item.put("image_url", imageUrl);
            content.add(item);
        }

        Map<String, Object> text = new LinkedHashMap<String, Object>();
        text.put("type", "text");
        text.put("text", buildPrompt(request.getMode()));
        content.add(text);

        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", content);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", arkProperties.getModel());
        body.put("messages", Arrays.asList(message));
        body.put("stream", stream);
        body.put("temperature", 0.2);
        return body;
    }

    private Map<String, String> parseArkStreamDelta(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!StringUtils.hasText(trimmed) || "[DONE]".equals(trimmed)) {
            return null;
        }
        String payload = trimmed.startsWith("data:") ? trimmed.substring(5).trim() : trimmed;
        if (!StringUtils.hasText(payload) || "[DONE]".equals(payload)) {
            return null;
        }
        try {
            Map<String, Object> chunk = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
            Object choicesValue = chunk.get("choices");
            if (!(choicesValue instanceof List) || ((List) choicesValue).isEmpty()) {
                return null;
            }
            Object choiceValue = ((List) choicesValue).get(0);
            if (!(choiceValue instanceof Map)) {
                return null;
            }
            Object deltaValue = ((Map) choiceValue).get("delta");
            if (!(deltaValue instanceof Map)) {
                return null;
            }
            Map deltaMap = (Map) deltaValue;
            Map<String, String> delta = new HashMap<String, String>();
            delta.put("content", safeString(deltaMap.get("content"), ""));
            delta.put("reasoning", safeString(deltaMap.get("reasoning_content"), ""));
            return delta;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void emitStreamEvent(OutputStream outputStream, String type, String message, PearlReport report, String answerText, String reasoningText) {
        try {
            Map<String, Object> event = new LinkedHashMap<String, Object>();
            event.put("type", type);
            event.put("message", message);
            event.put("answerText", answerText);
            event.put("reasoningText", reasoningText);
            if (report != null) {
                event.put("report", report);
            }
            outputStream.write(objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
            outputStream.write('\n');
            outputStream.flush();
        } catch (Exception ignored) {
        }
    }

    private void emitErrorEvent(OutputStream outputStream, String message) {
        try {
            Map<String, Object> event = new LinkedHashMap<String, Object>();
            event.put("type", "error");
            event.put("message", StringUtils.hasText(message) ? message : "鉴定服务异常");
            outputStream.write(objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
            outputStream.write('\n');
            outputStream.flush();
        } catch (Exception ignored) {
        }
    }

    private String buildReadableStreamProgress(int deltaCount) {
        if (deltaCount < 8) {
            return "正在确认图片里是否有清晰的珍珠主体。";
        }
        if (deltaCount < 16) {
            return "正在观察轮廓、孔口和表面纹理。";
        }
        if (deltaCount < 24) {
            return "正在分析光泽层次、反光强弱和伴色变化。";
        }
        if (deltaCount < 32) {
            return "正在综合判断真假、类型和可信度。";
        }
        return "正在整理识别依据，准备生成报告。";
    }

    private String readAll(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int read;
        while ((read = inputStream.read(data)) != -1) {
            buffer.write(data, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private String normalizeImageUrl(ImageInput image) {
        if (image == null) {
            throw new IllegalArgumentException("图片参数不能为空");
        }
        if (StringUtils.hasText(image.getUrl())) {
            return image.getUrl();
        }
        if (StringUtils.hasText(image.getDataUrl())) {
            return image.getDataUrl();
        }
        if (StringUtils.hasText(image.getBase64())) {
            String raw = image.getBase64().trim();
            if (raw.startsWith("data:image/")) {
                return raw;
            }
            String mimeType = StringUtils.hasText(image.getMimeType()) ? image.getMimeType() : "image/jpeg";
            return "data:" + mimeType + ";base64," + raw;
        }
        throw new IllegalArgumentException("图片需要提供 url、dataUrl 或 base64");
    }

    private String extractContent(Map response) {
        if (response == null) {
            throw new IllegalStateException("Ark API returned empty response");
        }
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List) || ((List) choicesValue).isEmpty()) {
            throw new IllegalStateException("Ark API response missing choices");
        }
        Object choiceValue = ((List) choicesValue).get(0);
        if (!(choiceValue instanceof Map)) {
            throw new IllegalStateException("Ark API response choice is invalid");
        }
        Object messageValue = ((Map) choiceValue).get("message");
        if (!(messageValue instanceof Map)) {
            throw new IllegalStateException("Ark API response message is invalid");
        }
        Object contentValue = ((Map) messageValue).get("content");
        if (!StringUtils.hasText(String.valueOf(contentValue))) {
            throw new IllegalStateException("Ark API response content is empty");
        }
        return String.valueOf(contentValue);
    }

    private PearlReport normalizeReport(String content) {
        try {
            Map<String, Object> raw = objectMapper.readValue(extractJson(content), new TypeReference<Map<String, Object>>() {
            });
            if (!isPearlDetected(raw, content)) {
                return buildNonPearlReport(raw, content);
            }
            PearlReport report = new PearlReport();
            report.setId("PP" + System.currentTimeMillis());
            report.setCreatedAt(Instant.now().toString());
            report.setAuthenticity(normalizeAuthenticity(raw));
            report.setPearlDetected(true);
            report.setPearlType(safeString(raw.get("pearlType"), "淡水珍珠"));
            report.setImitationType(safeString(raw.get("imitationType"), ""));
            report.setConfidence(clamp(toInt(raw.get("confidence"), 72), 1, 99));
            report.setResult(cleanResultPrefix(safeString(raw.get("result"), buildResult(report))));
            report.setQualityGrade(buildQualityGrade(raw, report.getConfidence()));
            report.setAttributes(buildAttributes(raw, report.getAuthenticity()));
            report.setSummary(safeString(raw.get("summary"), "已完成图片初筛，并根据当前照片给出估算结果。"));
            report.setReasons(toStringList(raw.get("reasons")));
            report.setSuggestions(toStringList(raw.get("suggestions")));
            return report;
        } catch (Exception error) {
            PearlReport fallback = new PearlReport();
            fallback.setId("PP" + System.currentTimeMillis());
            fallback.setCreatedAt(Instant.now().toString());
            if (isNonPearlText(content)) {
                return buildNonPearlReport(new HashMap<String, Object>(), content);
            }
            fallback.setAuthenticity("真");
            fallback.setPearlDetected(true);
            fallback.setPearlType("淡水珍珠");
            fallback.setConfidence(68);
            fallback.setResult("图片初筛已完成");
            fallback.setQualityGrade(buildGrade("C", 68));
            fallback.setAttributes(defaultAttributes());
            fallback.setSummary("模型返回内容未能解析为结构化 JSON，已按当前图片给出保守估算。");
            fallback.setReasons(Arrays.asList("模型返回格式不稳定，服务端已做兜底处理。"));
            fallback.setSuggestions(Arrays.asList("建议重新拍摄清晰照片后再次提交。"));
            fallback.setRawText(content);
            return fallback;
        }
    }

    private String extractJson(String content) {
        String cleaned = content.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object found");
        }
        return cleaned.substring(start, end + 1);
    }

    private PearlReport buildNonPearlReport(Map<String, Object> raw, String content) {
        PearlReport report = new PearlReport();
        report.setId("PP" + System.currentTimeMillis());
        report.setCreatedAt(Instant.now().toString());
        report.setAuthenticity("非珍珠");
        report.setPearlDetected(false);
        report.setPearlType("");
        report.setImitationType("");
        report.setConfidence(0);
        report.setResult("未检测到珍珠");
        report.setQualityGrade(buildNonPearlGrade());
        report.setAttributes(buildNonPearlAttributes(raw));
        report.setSummary(safeString(raw.get("summary"), "当前图片未检测到明确珍珠主体，本次结果不进入真假与品质定级。"));
        List<String> reasons = toStringList(raw.get("reasons"));
        if (reasons.isEmpty()) {
            reasons = Arrays.asList(
                    "图片中未识别到可用于珍珠鉴定的圆珠主体、孔口、光泽或表面纹理。",
                    "当前照片更像非鉴定目标图片，因此不适合输出珍珠真假、类型和等级。",
                    "请重新上传珍珠主体清晰、占画面较大的照片。"
            );
        }
        report.setReasons(reasons);
        List<String> suggestions = toStringList(raw.get("suggestions"));
        if (suggestions.isEmpty()) {
            suggestions = Arrays.asList("请拍摄珍珠正面整体照，确保珍珠占画面主体。", "如需完整鉴定，请补充孔口特写、强光纹理照和侧面照片。");
        }
        report.setSuggestions(suggestions);
        report.setRawText(content);
        return report;
    }

    private Map<String, Object> buildNonPearlGrade() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("grade", "N");
        item.put("level", "非鉴定目标");
        item.put("score", 0);
        item.put("description", "未检测到珍珠主体，不进行瑕疵等级评估");
        item.put("label", "非鉴定目标");
        return item;
    }

    private List<Map<String, Object>> buildNonPearlAttributes(Map<String, Object> raw) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(attribute("图片有效性", "未检测到珍珠", "未发现可用于珍珠鉴定的主体区域", 0));
        items.add(attribute("珍珠类型", "未识别", "当前图片不进入淡水、海水或仿珠分类", 0));
        items.add(attribute("亮度", "不适用", "未检测到珍珠主体，无法评估珍珠光泽", 0));
        items.add(attribute("圆度", "不适用", "未检测到珍珠轮廓，无法评估圆度", 0));
        items.add(attribute("瑕疵", "不适用", "未检测到珍珠表面，无法评估瑕疵等级", 0));
        return items;
    }

    private boolean isPearlDetected(Map<String, Object> raw, String content) {
        Object pearlDetected = firstValue(raw.get("pearlDetected"), raw.get("isPearl"));
        if (pearlDetected instanceof Boolean) {
            return (Boolean) pearlDetected;
        }
        if (pearlDetected != null) {
            String value = String.valueOf(pearlDetected).trim().toLowerCase();
            if ("false".equals(value) || "0".equals(value) || "否".equals(value) || "no".equals(value)) {
                return false;
            }
        }
        String text = safeString(raw.get("authenticity"), "") + " "
                + safeString(raw.get("result"), "") + " "
                + safeString(raw.get("summary"), "") + " "
                + String.valueOf(content);
        return !isNonPearlText(text);
    }

    private boolean isNonPearlText(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String value = text.toLowerCase();
        return value.contains("非珍珠")
                || value.contains("未检测到珍珠")
                || value.contains("没有检测到珍珠")
                || value.contains("未识别到珍珠")
                || value.contains("未显示珍珠")
                || value.contains("未见珍珠")
                || value.contains("无珍珠主体")
                || value.contains("不包含珍珠主体")
                || value.contains("没有珍珠主体")
                || value.contains("不是珍珠图片")
                || value.contains("图片不是珍珠")
                || value.contains("not a pearl")
                || value.contains("no pearl");
    }

    private String normalizeAuthenticity(Map<String, Object> raw) {
        String text = safeString(raw.get("authenticity"), "") + " " + safeString(raw.get("result"), "") + " " + safeString(raw.get("summary"), "");
        if (isNonPearlText(text)) {
            return "非珍珠";
        }
        if (text.contains("假") || text.contains("仿") || text.contains("塑料") || text.contains("玻璃")) {
            return "假";
        }
        return "真";
    }

    private String buildResult(PearlReport report) {
        if ("假".equals(report.getAuthenticity()) && StringUtils.hasText(report.getImitationType())) {
            return report.getImitationType();
        }
        if ("非珍珠".equals(report.getAuthenticity())) {
            return "未检测到珍珠";
        }
        return safeString(report.getPearlType(), "淡水珍珠");
    }

    private String cleanResultPrefix(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.replace("疑似", "").trim();
    }

    private Map<String, Object> buildQualityGrade(Map<String, Object> raw, int confidence) {
        Map<String, Object> qualityGrade = toMap(raw.get("qualityGrade"));
        Map<String, Object> blemish = toMap(raw.get("blemish"));
        String grade = normalizeGrade(firstText(qualityGrade.get("grade"), qualityGrade.get("name"), blemish.get("grade")));
        int score = clamp(toInt(firstValue(qualityGrade.get("score"), blemish.get("score")), confidence), 1, 100);
        return buildGrade(grade, score);
    }

    private Map<String, Object> buildGrade(String grade, int score) {
        GradeInfo info = GRADE_INFO.containsKey(grade) ? GRADE_INFO.get(grade) : GRADE_INFO.get("C");
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("grade", grade);
        item.put("level", info.level);
        item.put("score", score);
        item.put("description", info.description);
        item.put("label", grade + " " + info.level);
        return item;
    }

    private List<Map<String, Object>> buildAttributes(Map<String, Object> raw, String authenticity) {
        Map<String, Object> luster = toMap(raw.get("luster"));
        Map<String, Object> roundness = toMap(raw.get("roundness"));
        Map<String, Object> blemish = toMap(raw.get("blemish"));
        Map<String, Object> color = toMap(raw.get("color"));

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        String typeValue = "假".equals(authenticity)
                ? safeString(raw.get("imitationType"), "贝珠")
                : safeString(raw.get("pearlType"), "淡水珍珠");
        items.add(attribute("珍珠类型", typeValue, "检测为“" + authenticity + "”", toInt(raw.get("typeScore"), 78)));
        items.add(attribute("亮度", safeString(luster.get("level"), "中光"), safeString(luster.get("description"), "根据高光锐度、反射强弱与光泽层次判断"), toInt(luster.get("score"), 76)));
        items.add(attribute("圆度", safeString(roundness.get("level"), "近圆"), safeString(roundness.get("description"), "根据轮廓是否对称、是否偏椭圆或异形判断"), toInt(roundness.get("score"), 74)));
        items.add(attribute("瑕疵", formatBlemish(blemish), safeString(blemish.get("description"), "根据表面针点、坑点、划痕和瑕疵面积判断"), toInt(blemish.get("score"), 72)));
        items.add(attribute("珍珠颜色", formatColor(color), safeString(firstValue(color.get("representative"), color.get("description")), "颜色会受光源、背景和相机白平衡影响"), toInt(color.get("score"), 70)));
        return items;
    }

    private Map<String, Object> attribute(String name, String value, String detail, int score) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", name);
        item.put("value", value);
        item.put("detail", detail);
        item.put("score", clamp(score, 1, 100));
        return item;
    }

    private List<Map<String, Object>> defaultAttributes() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(attribute("珍珠类型", "淡水珍珠", "检测为“真”", 72));
        items.add(attribute("亮度", "中光", "根据高光锐度、反射强弱与光泽层次判断", 70));
        items.add(attribute("圆度", "近圆", "根据轮廓是否对称、是否偏椭圆或异形判断", 70));
        items.add(attribute("瑕疵", "C 小瑕", "较小瑕疵，肉眼易见", 68));
        items.add(attribute("珍珠颜色", "白色系 / 奶白", "常见于淡水珍珠、南洋白珠、Akoya", 70));
        return items;
    }

    private String formatBlemish(Map<String, Object> blemish) {
        String grade = normalizeGrade(firstText(blemish.get("grade"), blemish.get("level")));
        GradeInfo info = GRADE_INFO.containsKey(grade) ? GRADE_INFO.get(grade) : GRADE_INFO.get("C");
        return grade + " " + safeString(blemish.get("level"), info.level);
    }

    private String formatColor(Map<String, Object> color) {
        String series = safeString(color.get("series"), "");
        String name = safeString(color.get("name"), "");
        if (StringUtils.hasText(series) && StringUtils.hasText(name)) {
            return series + " / " + name;
        }
        return safeString(firstValue(name, series), "白色系 / 奶白");
    }

    private String normalizeGrade(String value) {
        String text = value == null ? "" : value;
        if (text.contains("无瑕")) return "A";
        if (text.contains("微瑕")) return "B";
        if (text.contains("小瑕")) return "C";
        if (text.contains("重瑕")) return "E";
        if (text.contains("瑕疵")) return "D";
        if (text.length() > 0) {
            String grade = text.substring(0, 1).toUpperCase();
            if (GRADE_INFO.containsKey(grade)) {
                return grade;
            }
        }
        return "C";
    }

    private List<String> toStringList(Object value) {
        List<String> list = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List) value) {
                if (StringUtils.hasText(String.valueOf(item))) {
                    list.add(String.valueOf(item));
                }
            }
        }
        return list;
    }

    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        Map<String, Object> map = new HashMap<String, Object>();
        if (value != null) {
            map.put("level", String.valueOf(value));
        }
        return map;
    }

    private Object firstValue(Object first, Object second) {
        return first != null ? first : second;
    }

    private String firstText(Object first, Object second) {
        return safeString(firstValue(first, second), "");
    }

    private String firstText(Object first, Object second, Object third) {
        Object value = firstValue(first, second);
        return safeString(value != null ? value : third, "");
    }

    private String safeString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text) || isPending(text)) {
            return fallback;
        }
        return text;
    }

    private boolean isPending(String value) {
        return value.contains("待复核") || value.contains("无法判断") || value.contains("不确定") || value.contains("未知") || value.contains("不清晰");
    }

    private int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String buildPrompt(String mode) {
        String modeHint = "quick".equals(mode) ? "用户使用快速鉴定，可能只有 1 张图片。" : "用户使用完整鉴定，可能包含多角度图片。";
        return String.join("\n",
                "你是一名谨慎的珍珠图片初筛助手。请根据用户上传的珍珠照片做初步判断。",
                modeHint,
                "必须只输出 JSON，不要输出 Markdown，不要输出额外解释。",
                "除最终 JSON 字段名外，不要输出英文单词、英文说明或英文推理过程；所有可读说明必须使用中文。",
                "不要声称自己出具权威鉴定证书。结果只能是图片初筛建议。",
                "第一步必须判断图片中是否存在明确珍珠主体。若图片不是珍珠、没有珍珠、主体与珍珠鉴定无关，必须输出 pearlDetected=false，authenticity=非珍珠，result=未检测到珍珠，confidence=0，不要继续猜淡水珍珠、海水珍珠或仿珠类型。",
                "只有 pearlDetected=true 时，才进入真假、类型、亮度、圆度、瑕疵和颜色评估。",
                "即使珍珠图片不够完美，也必须基于可见信息给出估算值。不要输出“待复核”“无法判断”“不确定”等状态，可用较低 confidence 表达不确定性。",
                "authenticity 只能从：真、假、非珍珠 中选择。非珍珠只用于图片未检测到珍珠主体的场景。",
                buildPearlKnowledgeBase(),
                "真珍珠类型只能从：淡水珍珠、海水珍珠、澳白、Akoya、南洋白珠、南洋金珠、大溪地黑珍珠 中选择。",
                "假珠类型只能从：塑料仿珠、玻璃仿珠、贝珠、施家珍珠、染色/覆膜珠 中选择。",
                "亮度只能从：极强光、强光（高光）、中光、弱光 中选择。",
                "圆度只能从：正圆、近正圆、近圆、椭圆、扁圆、水滴、异形（巴洛克） 中选择。",
                "瑕疵等级只能从：A 无瑕、B 微瑕、C 小瑕、D 瑕疵、E 重瑕 中选择。",
                "瑕疵描述参考：A 肉眼观察极难见瑕疵；B 极少针点状瑕疵，肉眼较难发现；C 较小瑕疵，肉眼易见；D 明显瑕疵，占表面积1/4以下；E 严重瑕疵，占表面积1/4以上。",
                "颜色系列参考：白色系（纯白、奶白、银白、瓷白）；黑色系（黑、灰黑、蓝黑、褐黑）；红色/粉色系（粉红、浅玫瑰红、浅紫红）；黄色/金色系（浅黄、米黄、金黄、橙黄）；其他色系（紫、青、蓝、绿、古铜色等）。",
                "JSON 字段：",
                "{",
                "  \"pearlDetected\": true/false,",
                "  \"result\": \"淡水珍珠/Akoya/塑料仿珠/未检测到珍珠\",",
                "  \"authenticity\": \"真/假/非珍珠\",",
                "  \"pearlType\": \"淡水珍珠/海水珍珠/澳白/Akoya/南洋白珠/南洋金珠/大溪地黑珍珠\",",
                "  \"imitationType\": \"塑料仿珠/玻璃仿珠/贝珠/施家珍珠/染色/覆膜珠/空字符串\",",
                "  \"confidence\": 1-99,",
                "  \"qualityGrade\": {\"grade\":\"A/B/C/D/E\", \"name\":\"无瑕/微瑕/小瑕/瑕疵/重瑕\", \"score\":1-100, \"description\":\"按定级标准描述\"},",
                "  \"typeScore\": 1-100,",
                "  \"luster\": {\"level\":\"极强光/强光（高光）/中光/弱光\", \"score\":1-100, \"description\":\"判断依据\"},",
                "  \"roundness\": {\"level\":\"正圆/近正圆/近圆/椭圆/扁圆/水滴/异形（巴洛克）\", \"score\":1-100, \"description\":\"判断依据\"},",
                "  \"blemish\": {\"grade\":\"A/B/C/D/E\", \"level\":\"无瑕/微瑕/小瑕/瑕疵/重瑕\", \"score\":1-100, \"description\":\"按等级描述\"},",
                "  \"color\": {\"series\":\"白色系/黑色系/红色/粉色系/黄色/金色系/其他色系\", \"name\":\"具体颜色\", \"score\":1-100, \"representative\":\"典型代表品种或说明\"},",
                "  \"summary\": \"80字以内总结\",",
                "  \"reasons\": [\"依据1：必须引用纹理/孔口/光泽/颜色/轮廓中的一个\", \"依据2：必须引用另一视觉维度\", \"依据3：说明不确定性或反向证据\"],",
                "  \"suggestions\": [\"建议1\", \"建议2\"]",
                "}"
        );
    }

    private String buildPearlKnowledgeBase() {
        return String.join("\n",
                "珍珠视觉识别知识库，请按权重综合判断，不要只看单一特征：",
                "1. 表面微观纹理是核心特征。",
                "- 天然珍珠：常见有机生长纹，类似指纹、叠瓦状或微细不平整，纹理自然且无严格规律。",
                "- 仿珍珠：塑料仿珠、贝珠表面常过于平滑，缺少自然生长纹；玻璃仿珠可能出现模具微凹坑、涂层气泡或表面涂层感。",
                "- 染色/优化珍珠：染料可能填平部分天然纹理，使纹理清晰度下降；也可能出现颜料堆积的颗粒感。",
                "2. 孔口特征是高权重特征，若上传了孔口/打孔处照片，应优先参考。",
                "- 天然珍珠：孔口边缘相对锐利整齐，可见内部珍珠层的层状结构，通常无明显涂层脱落。",
                "- 仿珍珠：孔口处容易出现涂层剥落、起皮、露底、涂料堆积；玻璃仿珠尤其明显。",
                "- 染色/优化珍珠：孔口周围颜色可能明显更深，存在染料富集、渗入裂隙或孔缘染色痕迹。",
                "3. 光泽与反光是光学特征。",
                "- 天然珍珠：反光点柔和，光泽有由内而外的层次感，常伴随天然伴色或晕彩。",
                "- 仿珍珠：反光可能过于生硬、像镜面，或只有表面浮光，缺少深度；光泽评分应偏低。",
                "- 染色/优化珍珠：化学处理可能损伤珍珠质，光泽通常较同级天然白珠暗淡。",
                "4. 颜色分布用于识别天然伴色、仿珠单调色和染色异常。",
                "- 天然珍珠：颜色整体均匀，但有微妙色带变化；粉、绿等伴色过渡自然。",
                "- 仿珍珠：颜色常过于单一呆板，缺少复杂伴色，呈单一色块或单一波段观感。",
                "- 染色/优化珍珠：常出现色斑、色块、网状纹路；裂隙、孔口或凹陷处颜色异常加深，类似染料沉积。",
                "5. 形状与轮廓是辅助几何特征。",
                "- 天然珍珠：即使高品质，也常有微小不对称、生长结节或非完美正圆。",
                "- 仿珍珠：贝珠或机制仿珠可能呈数学意义上的绝对正圆，几何过分完美时天然概率降低。",
                "- 染色/优化珍珠：底珠可能仍是天然淡水珠，因此形状可接近天然，需要结合颜色、孔口和纹理判断。",
                "6. 判定难点与保守策略。",
                "- 高品质天然珠与高品质贝珠容易混淆，应提高纹理、孔口权重。",
                "- 高品质玻璃仿珠或马约卡类涂层工艺可能骗过普通光泽检测，不能只凭光泽判真。",
                "- 轻微染色或高科技固色在普通 RGB 图片中难识别；若仅有颜色线索，应降低 confidence，并在 reasons 中说明依据。",
                "7. 输出要求。",
                "- reasons 至少覆盖 2 个视觉维度，例如纹理、孔口、光泽、颜色、轮廓。",
                "- 如果图片未包含孔口或微距纹理，不要编造孔口细节；可以说“未见孔口特写，主要依据光泽/颜色/轮廓估算”，并降低 confidence。",
                "- 判断为假时，imitationType 优先选择最符合证据的一类：塑料仿珠、玻璃仿珠、贝珠、施家珍珠、染色/覆膜珠。"
        );
    }

    private static class GradeInfo {
        private final String level;
        private final String description;

        private GradeInfo(String level, String description) {
            this.level = level;
            this.description = description;
        }
    }
}
