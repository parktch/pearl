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

    public PearlReport analyze(PearlAnalyzeRequest request) {
        validateRequest(request);
        Map<String, Object> arkBody = buildArkBody(request);
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

    private Map<String, Object> buildArkBody(PearlAnalyzeRequest request) {
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
        body.put("stream", false);
        body.put("temperature", 0.2);
        return body;
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
            PearlReport report = new PearlReport();
            report.setId("PP" + System.currentTimeMillis());
            report.setCreatedAt(Instant.now().toString());
            report.setAuthenticity(normalizeAuthenticity(raw));
            report.setPearlType(safeString(raw.get("pearlType"), "淡水珍珠"));
            report.setImitationType(safeString(raw.get("imitationType"), ""));
            report.setConfidence(clamp(toInt(raw.get("confidence"), 72), 1, 99));
            report.setResult(safeString(raw.get("result"), buildResult(report)));
            report.setQualityGrade(buildQualityGrade(raw, report.getConfidence()));
            report.setAttributes(buildAttributes(raw, report.getAuthenticity()));
            report.setSummary(safeString(raw.get("summary"), "AI 已完成图片初筛，并根据当前照片给出估算结果。"));
            report.setReasons(toStringList(raw.get("reasons")));
            report.setSuggestions(toStringList(raw.get("suggestions")));
            return report;
        } catch (Exception error) {
            PearlReport fallback = new PearlReport();
            fallback.setId("PP" + System.currentTimeMillis());
            fallback.setCreatedAt(Instant.now().toString());
            fallback.setAuthenticity("真");
            fallback.setPearlType("淡水珍珠");
            fallback.setConfidence(68);
            fallback.setResult("AI 图片初筛已完成");
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

    private String normalizeAuthenticity(Map<String, Object> raw) {
        String text = safeString(raw.get("authenticity"), "") + " " + safeString(raw.get("result"), "") + " " + safeString(raw.get("summary"), "");
        if (text.contains("假") || text.contains("仿") || text.contains("塑料") || text.contains("玻璃")) {
            return "假";
        }
        return "真";
    }

    private String buildResult(PearlReport report) {
        if ("假".equals(report.getAuthenticity()) && StringUtils.hasText(report.getImitationType())) {
            return "疑似" + report.getImitationType();
        }
        return "疑似" + safeString(report.getPearlType(), "淡水珍珠");
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
                "不要声称自己出具权威鉴定证书。结果只能是图片初筛建议。",
                "即使图片不够完美，也必须基于可见信息给出估算值。不要输出“待复核”“无法判断”“不确定”等状态，可用较低 confidence 表达不确定性。",
                "authenticity 必须二选一：真 或 假。",
                "真珍珠类型只能从：淡水珍珠、海水珍珠、澳白、Akoya、南洋白珠、南洋金珠、大溪地黑珍珠 中选择。",
                "假珠类型只能从：塑料仿珠、玻璃仿珠、贝珠、施家珍珠、染色/覆膜珠 中选择。",
                "亮度只能从：极强光、强光（高光）、中光、弱光 中选择。",
                "圆度只能从：正圆、近正圆、近圆、椭圆、扁圆、水滴、异形（巴洛克） 中选择。",
                "瑕疵等级只能从：A 无瑕、B 微瑕、C 小瑕、D 瑕疵、E 重瑕 中选择。",
                "瑕疵描述参考：A 肉眼观察极难见瑕疵；B 极少针点状瑕疵，肉眼较难发现；C 较小瑕疵，肉眼易见；D 明显瑕疵，占表面积1/4以下；E 严重瑕疵，占表面积1/4以上。",
                "颜色系列参考：白色系（纯白、奶白、银白、瓷白）；黑色系（黑、灰黑、蓝黑、褐黑）；红色/粉色系（粉红、浅玫瑰红、浅紫红）；黄色/金色系（浅黄、米黄、金黄、橙黄）；其他色系（紫、青、蓝、绿、古铜色等）。",
                "JSON 字段：",
                "{",
                "  \"result\": \"疑似淡水珍珠/疑似Akoya/疑似塑料仿珠\",",
                "  \"authenticity\": \"真/假\",",
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
                "  \"reasons\": [\"依据1\", \"依据2\", \"依据3\"],",
                "  \"suggestions\": [\"建议1\", \"建议2\"]",
                "}"
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
