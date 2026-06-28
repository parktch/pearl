package com.pearlai.cloudrun.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pearlai.cloudrun.config.ArkProperties;
import com.pearlai.cloudrun.dto.ImageInput;
import com.pearlai.cloudrun.dto.PearlAnalyzeContext;
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
    private static final Map<String, GradeInfo> QUALITY_GRADE_INFO = new LinkedHashMap<String, GradeInfo>();

    static {
        GRADE_INFO.put("A", new GradeInfo("无瑕", "肉眼观察极难见瑕疵"));
        GRADE_INFO.put("B", new GradeInfo("微瑕", "极少针点状瑕疵，肉眼较难发现"));
        GRADE_INFO.put("C", new GradeInfo("小瑕", "较小瑕疵，肉眼易见"));
        GRADE_INFO.put("D", new GradeInfo("瑕疵", "明显瑕疵，占表面积 1/4 以下"));
        GRADE_INFO.put("E", new GradeInfo("重瑕", "严重瑕疵，占表面积 1/4 以上"));
        QUALITY_GRADE_INFO.put("A+", new GradeInfo("收藏·极品", "顶级收藏，市场稀缺"));
        QUALITY_GRADE_INFO.put("A", new GradeInfo("收藏·优级", "优秀收藏品质"));
        QUALITY_GRADE_INFO.put("A-", new GradeInfo("收藏·入门", "收藏入门或高精选顶配"));
        QUALITY_GRADE_INFO.put("B+", new GradeInfo("精选·顶级", "精选顶级"));
        QUALITY_GRADE_INFO.put("B", new GradeInfo("精选·中档", "精选中档主力"));
        QUALITY_GRADE_INFO.put("B-", new GradeInfo("精选·入门", "精选入门或高入门顶配"));
        QUALITY_GRADE_INFO.put("C+", new GradeInfo("入门·高配", "入门高配"));
        QUALITY_GRADE_INFO.put("C", new GradeInfo("入门·标准", "入门标准"));
        QUALITY_GRADE_INFO.put("C-", new GradeInfo("入门·基础", "入门基础"));
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
            return normalizeReport(content, request);
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
            PearlReport report = normalizeReport(finalText, request);
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
        text.put("text", buildPrompt(request.getMode(), request.getContext()));
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

    private PearlReport normalizeReport(String content, PearlAnalyzeRequest request) {
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
            report.setPriceEstimate(buildPriceEstimate(raw, report.getAuthenticity(), report.getPearlType()));
            report.setQualityGrade(buildQualityGrade(raw, report.getConfidence()));
            report.setAttributes(buildAttributes(raw, report.getAuthenticity()));
            report.setSummary(safeString(raw.get("summary"), "已完成图片初筛，并根据当前照片给出估算结果。"));
            report.setReasons(toStringList(raw.get("reasons")));
            report.setSuggestions(toStringList(raw.get("suggestions")));
            applyReverseCalibration(report, raw, content, request);
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
            fallback.setPriceEstimate(defaultPriceEstimate("淡水珍珠", "入门", "100-300"));
            fallback.setQualityGrade(buildQualityGradeItem("B", 68));
            fallback.setAttributes(defaultAttributes());
            fallback.setSummary("模型返回内容未能解析为结构化 JSON，已按当前图片给出保守估算。");
            fallback.setReasons(Arrays.asList("模型返回格式不稳定，服务端已做兜底处理。"));
            fallback.setSuggestions(Arrays.asList("建议重新拍摄清晰照片后再次提交。"));
            fallback.setRawText(content);
            return fallback;
        }
    }

    private void applyReverseCalibration(PearlReport report, Map<String, Object> raw, String content, PearlAnalyzeRequest request) {
        if (report == null || !"真".equals(report.getAuthenticity())) {
            return;
        }
        PearlAnalyzeContext context = request == null ? null : request.getContext();
        int imageCount = request == null || request.getImages() == null ? 0 : request.getImages().size();
        String mode = request == null ? "" : safeString(request.getMode(), "");
        String evidenceText = buildEvidenceText(report, raw, content);
        String type = safeString(firstValue(report.getPearlType(), report.getResult()), "");
        double price = parsePositiveDouble(context == null ? "" : context.getPrice());
        double diameter = parsePositiveDouble(context == null ? "" : context.getDiameter());
        String channel = safeString(context == null ? "" : context.getChannel(), "");

        List<String> signals = new ArrayList<String>();
        int riskScore = 0;
        double referenceMin = referenceMinPrice(type, diameter);
        boolean highValueType = isHighValuePearlType(type);
        boolean highRiskChannel = isHighRiskChannel(channel);
        boolean veryHighRiskChannel = isVeryHighRiskChannel(channel);
        boolean missingKeyEvidence = isMissingKeyEvidence(evidenceText, imageCount, mode);
        boolean suspiciousVisual = hasSuspiciousVisualSignal(evidenceText);

        if (price > 0 && referenceMin > 0) {
            double ratio = price / referenceMin;
            if (highValueType && ratio < 0.25) {
                riskScore += 4;
                signals.add("补充价格明显低于该品类与直径的常见入门参考价");
            } else if (highValueType && ratio < 0.45) {
                riskScore += 3;
                signals.add("补充价格与标称高价值品类存在较大冲突");
            } else if (highValueType && ratio < 0.65) {
                riskScore += 2;
                signals.add("补充价格低于该品类常见参考价，需谨慎看待真珠结论");
            } else if (!highValueType && diameter >= 9 && price < 40 && highRiskChannel) {
                riskScore += 2;
                signals.add("大直径低价且渠道风险较高，存在仿珠或低质处理珠风险");
            }
        }
        if (veryHighRiskChannel) {
            riskScore += 2;
            signals.add("购买/来源渠道属于高风险场景");
        } else if (highRiskChannel) {
            riskScore += 1;
            signals.add("购买/来源渠道需要谨慎校准");
        }
        if (missingKeyEvidence) {
            riskScore += 2;
            signals.add("当前图片缺少孔口、微观纹理或多角度证据");
        }
        if (suspiciousVisual) {
            riskScore += 2;
            signals.add("图片或模型描述中出现过于光滑、浮光、几何过分规整等仿珠信号");
        }

        if (signals.isEmpty()) {
            return;
        }

        boolean strongConflict = price > 0
                && referenceMin > 0
                && price / referenceMin < 0.35
                && (highRiskChannel || missingKeyEvidence)
                && (highValueType || diameter >= 9);
        if (riskScore >= 6 || strongConflict) {
            convertToImitationByReverseCalibration(report, raw, signals);
            return;
        }

        int cap = riskScore >= 4 ? 70 : 78;
        if (report.getConfidence() > cap) {
            report.setConfidence(cap);
        }
        appendUnique(report.getReasons(), "反向校准：" + String.join("；", signals) + "，因此下调本次真珠判断可信度。");
        appendUnique(report.getSuggestions(), "建议补拍孔口特写、强光纹理图，并结合线下检测复核低价或高风险渠道样品。");
    }

    private void convertToImitationByReverseCalibration(PearlReport report, Map<String, Object> raw, List<String> signals) {
        String imitationType = safeString(report.getImitationType(), "");
        if (!StringUtils.hasText(imitationType)) {
            imitationType = inferImitationTypeFromText(buildEvidenceText(report, raw, ""));
        }
        report.setAuthenticity("假");
        report.setImitationType(imitationType);
        report.setResult(imitationType);
        report.setPriceEstimate(null);
        report.setConfidence(clamp(Math.min(report.getConfidence(), 72), 55, 78));
        report.setAttributes(buildAttributes(raw, "假"));
        List<Map<String, Object>> attributes = report.getAttributes() == null
                ? new ArrayList<Map<String, Object>>()
                : new ArrayList<Map<String, Object>>(report.getAttributes());
        attributes.add(attribute("反向校准", "高风险", String.join("；", signals), report.getConfidence()));
        report.setAttributes(attributes);
        report.setSummary("图片初筛结合价格、渠道、直径与视觉证据后，假珠或处理珠风险较高。");
        appendUnique(report.getReasons(), "反向校准：" + String.join("；", signals) + "，服务端已将结论校准为假珠风险。");
        appendUnique(report.getSuggestions(), "请补充孔口特写和强光纹理照片；高价值交易前建议送专业机构检测。");
    }

    private String buildEvidenceText(PearlReport report, Map<String, Object> raw, String content) {
        StringBuilder builder = new StringBuilder();
        if (report != null) {
            builder.append(" ").append(safeString(report.getResult(), ""));
            builder.append(" ").append(safeString(report.getPearlType(), ""));
            builder.append(" ").append(safeString(report.getImitationType(), ""));
            builder.append(" ").append(safeString(report.getSummary(), ""));
            for (String reason : report.getReasons() == null ? new ArrayList<String>() : report.getReasons()) {
                builder.append(" ").append(reason);
            }
            for (Map<String, Object> attribute : report.getAttributes() == null ? new ArrayList<Map<String, Object>>() : report.getAttributes()) {
                builder.append(" ").append(safeString(attribute.get("name"), ""));
                builder.append(" ").append(safeString(attribute.get("value"), ""));
                builder.append(" ").append(safeString(attribute.get("detail"), ""));
            }
        }
        if (raw != null) {
            builder.append(" ").append(String.valueOf(raw));
        }
        builder.append(" ").append(safeString(content, ""));
        return builder.toString();
    }

    private boolean isHighValuePearlType(String type) {
        String text = safeString(type, "");
        return text.contains("澳白")
                || text.contains("南洋")
                || text.contains("Akoya")
                || text.contains("AKOYA")
                || text.contains("日本")
                || text.contains("大溪地")
                || text.contains("黑珍珠")
                || text.contains("金珠")
                || text.contains("海水");
    }

    private boolean isHighRiskChannel(String channel) {
        String text = safeString(channel, "");
        return text.contains("直播")
                || text.contains("电商")
                || text.contains("二手")
                || text.contains("朋友")
                || text.contains("旅游")
                || text.contains("景区")
                || text.contains("地摊")
                || text.contains("其他");
    }

    private boolean isVeryHighRiskChannel(String channel) {
        String text = safeString(channel, "");
        return text.contains("旅游")
                || text.contains("景区")
                || text.contains("地摊")
                || text.contains("直播")
                || text.contains("二手");
    }

    private boolean isMissingKeyEvidence(String evidenceText, int imageCount, String mode) {
        String text = safeString(evidenceText, "");
        if (imageCount <= 1 || "quick".equals(mode)) {
            return true;
        }
        return text.contains("未见孔口")
                || text.contains("未包含孔口")
                || text.contains("缺少孔口")
                || text.contains("未见微观")
                || text.contains("缺少纹理")
                || text.contains("未见纹理")
                || text.contains("没有孔口")
                || text.contains("没有纹理");
    }

    private boolean hasSuspiciousVisualSignal(String evidenceText) {
        String text = safeString(evidenceText, "");
        return text.contains("过于光滑")
                || text.contains("机械")
                || text.contains("完美正圆")
                || text.contains("绝对正圆")
                || text.contains("浮光")
                || text.contains("镜面")
                || text.contains("单一")
                || text.contains("呆板")
                || text.contains("涂层")
                || text.contains("剥落")
                || text.contains("气泡")
                || text.contains("染料")
                || text.contains("色斑")
                || text.contains("色块");
    }

    private String inferImitationTypeFromText(String evidenceText) {
        String text = safeString(evidenceText, "");
        if (text.contains("塑料")) return "塑料仿珠";
        if (text.contains("玻璃") || text.contains("气泡")) return "玻璃仿珠";
        if (text.contains("染") || text.contains("覆膜") || text.contains("涂层")) return "染色/覆膜珠";
        if (text.contains("施家")) return "施家珍珠";
        return "贝珠";
    }

    private double referenceMinPrice(String pearlType, double diameter) {
        String type = safeString(pearlType, "淡水珍珠");
        double size = diameter > 0 ? diameter : 9.5;
        if (type.contains("澳白") || type.contains("南洋白")) {
            if (size >= 14) return 15000;
            if (size >= 13) return 8000;
            if (size >= 12) return 5000;
            if (size >= 11) return 3000;
            if (size >= 10) return 1500;
            if (size >= 9) return 800;
            return 500;
        }
        if (type.contains("金珠")) {
            if (size >= 14) return 12000;
            if (size >= 13) return 7000;
            if (size >= 12) return 4000;
            if (size >= 11) return 2500;
            if (size >= 10) return 1200;
            if (size >= 9) return 600;
            return 400;
        }
        if (type.contains("大溪地") || type.contains("黑珍珠")) {
            if (size >= 14) return 10000;
            if (size >= 13) return 6000;
            if (size >= 12) return 3500;
            if (size >= 11) return 2000;
            if (size >= 10) return 1000;
            if (size >= 9) return 500;
            return 300;
        }
        if (type.contains("Akoya") || type.contains("AKOYA") || type.contains("日本")) {
            if (size >= 9) return 1200;
            if (size >= 8) return 600;
            if (size >= 7) return 300;
            if (size >= 6) return 150;
            return 80;
        }
        if (type.contains("海水")) {
            if (size >= 11) return 2000;
            if (size >= 10) return 1000;
            if (size >= 9) return 500;
            return 300;
        }
        if (type.contains("爱迪生")) {
            if (size >= 14) return 1000;
            if (size >= 13) return 600;
            if (size >= 12) return 300;
            if (size >= 11) return 150;
            if (size >= 10) return 80;
            if (size >= 9) return 50;
            return 30;
        }
        if (type.contains("马贝")) {
            if (size >= 17) return 1800;
            if (size >= 16) return 1000;
            if (size >= 15) return 600;
            if (size >= 14) return 350;
            return 200;
        }
        if (size >= 10) return 200;
        if (size >= 9) return 120;
        if (size >= 8) return 80;
        if (size >= 7) return 60;
        if (size >= 6) return 40;
        return 20;
    }

    private double parsePositiveDouble(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        String text = value.replace(",", "").replace("，", "");
        StringBuilder number = new StringBuilder();
        boolean seenDigit = false;
        boolean seenDot = false;
        for (int index = 0; index < text.length(); index += 1) {
            char ch = text.charAt(index);
            if (Character.isDigit(ch)) {
                number.append(ch);
                seenDigit = true;
            } else if (ch == '.' && !seenDot) {
                number.append(ch);
                seenDot = true;
            } else if (seenDigit) {
                break;
            }
        }
        if (!seenDigit) {
            return 0;
        }
        try {
            return Double.parseDouble(number.toString());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private void appendUnique(List<String> list, String value) {
        if (list == null || !StringUtils.hasText(value)) {
            return;
        }
        if (!list.contains(value)) {
            list.add(value);
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
        report.setPriceEstimate(null);
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

    private Map<String, Object> buildPriceEstimate(Map<String, Object> raw, String authenticity, String pearlType) {
        if (!"真".equals(authenticity)) {
            return null;
        }
        Map<String, Object> price = toMap(raw.get("priceEstimate"));
        String range = safeString(firstValue(price.get("range"), price.get("priceRange")), "");
        String tier = safeString(price.get("tier"), "");
        String basis = safeString(price.get("basis"), "");
        String note = safeString(price.get("note"), "");
        if (!StringUtils.hasText(range)) {
            String grade = normalizeGrade(firstText(toMap(raw.get("qualityGrade")).get("grade"), toMap(raw.get("blemish")).get("grade")));
            String luster = safeString(toMap(raw.get("luster")).get("level"), "");
            tier = inferPriceTier(grade, luster);
            range = lookupPriceRange(pearlType, tier);
        }
        if (!StringUtils.hasText(range)) {
            return null;
        }
        if (!StringUtils.hasText(tier)) {
            tier = "参考";
        }
        if (!StringUtils.hasText(basis)) {
            basis = "按山下湖多品种、多规格单颗珍珠市场参考价，结合图片估算等级给出";
        }
        if (!StringUtils.hasText(note)) {
            note = "仅为图片初筛粗估价格带，不作为交易定价或回收报价";
        }
        return defaultPriceEstimate(pearlType, tier, range, basis, note);
    }

    private Map<String, Object> defaultPriceEstimate(String pearlType, String tier, String range) {
        return defaultPriceEstimate(pearlType, tier, range, "按山下湖多品种、多规格单颗珍珠市场参考价估算", "仅为图片初筛粗估价格带，不作为交易定价或回收报价");
    }

    private Map<String, Object> defaultPriceEstimate(String pearlType, String tier, String range, String basis, String note) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("currency", "CNY");
        item.put("unit", "元/颗");
        item.put("sizeReference", "9-10mm 默认参考");
        item.put("pearlType", safeString(pearlType, "珍珠"));
        item.put("tier", tier);
        item.put("range", range);
        item.put("basis", basis);
        item.put("note", note);
        return item;
    }

    private String inferPriceTier(String grade, String luster) {
        String text = safeString(luster, "");
        if ("A".equals(grade) || text.contains("极强")) {
            return "收藏";
        }
        if ("B".equals(grade) || text.contains("强光")) {
            return "精选";
        }
        return "入门";
    }

    private String lookupPriceRange(String pearlType, String tier) {
        String type = safeString(pearlType, "淡水珍珠");
        if (type.contains("爱迪生")) return pickTierRange(tier, "50", "150", "400");
        if (type.contains("澳白") || type.contains("南洋白")) return pickTierRange(tier, "800", "2,500", "7,000");
        if (type.contains("金珠")) return pickTierRange(tier, "600", "2,000", "6,000");
        if (type.contains("大溪地") || type.contains("黑珍珠")) return pickTierRange(tier, "500", "1,800", "5,000");
        if (type.contains("Akoya") || type.contains("日本")) return pickTierRange(tier, "1,200", "4,000", "10,000");
        if (type.contains("马贝")) return pickTierRange(tier, "350", "1,000", "2,500");
        return pickTierRange(tier, "120", "300", "600");
    }

    private String pickTierRange(String tier, String entry, String selected, String collectible) {
        if ("收藏".equals(tier)) return collectible;
        if ("精选".equals(tier)) return selected;
        return entry;
    }

    private Map<String, Object> buildQualityGrade(Map<String, Object> raw, int confidence) {
        Map<String, Object> qualityGrade = toMap(raw.get("qualityGrade"));
        Map<String, Object> blemish = toMap(raw.get("blemish"));
        Map<String, Object> luster = toMap(raw.get("luster"));
        Map<String, Object> roundness = toMap(raw.get("roundness"));
        Map<String, Object> color = toMap(raw.get("color"));
        String modelGrade = normalizeQualityGrade(firstText(qualityGrade.get("grade"), qualityGrade.get("level"), qualityGrade.get("name")));
        int score = clamp(toInt(qualityGrade.get("score"), calculateQualityScore(luster, blemish, roundness, color)), 1, 100);
        String grade = StringUtils.hasText(modelGrade) ? modelGrade : mapScoreToQualityGrade(score);
        grade = applyQualityVetoRules(grade, luster, blemish, roundness, color);
        return buildQualityGradeItem(grade, score);
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

    private Map<String, Object> buildQualityGradeItem(String grade, int score) {
        String normalized = normalizeQualityGrade(grade);
        if (!StringUtils.hasText(normalized)) {
            normalized = mapScoreToQualityGrade(score);
        }
        GradeInfo info = QUALITY_GRADE_INFO.containsKey(normalized) ? QUALITY_GRADE_INFO.get(normalized) : QUALITY_GRADE_INFO.get("B");
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("grade", normalized);
        item.put("level", info.level);
        item.put("score", clamp(score, 1, 100));
        item.put("description", info.description);
        item.put("label", normalized + " " + info.level);
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

    private int calculateQualityScore(Map<String, Object> luster, Map<String, Object> blemish, Map<String, Object> roundness, Map<String, Object> color) {
        double score = lusterScore(luster) * 0.35
                + blemishScore(blemish) * 0.30
                + roundnessScore(roundness) * 0.20
                + colorScore(color) * 0.15;
        return clamp((int) Math.round(score), 1, 100);
    }

    private int lusterScore(Map<String, Object> luster) {
        String level = safeString(luster.get("level"), "");
        if (level.contains("极强")) return 100;
        if (level.contains("强光") || level.contains("高光")) return 75;
        if (level.contains("弱")) return 25;
        return 50;
    }

    private int blemishScore(Map<String, Object> blemish) {
        String grade = normalizeGrade(firstText(blemish.get("grade"), blemish.get("level")));
        if ("A".equals(grade)) return 100;
        if ("B".equals(grade)) return 80;
        if ("C".equals(grade)) return 60;
        if ("D".equals(grade)) return 30;
        return 10;
    }

    private int roundnessScore(Map<String, Object> roundness) {
        String level = safeString(roundness.get("level"), "");
        if (level.contains("正圆") && !level.contains("近正圆")) return 100;
        if (level.contains("近正圆")) return 85;
        if (level.contains("近圆")) return 65;
        if (level.contains("椭圆")) return 45;
        if (level.contains("扁圆")) return 30;
        if (level.contains("水滴")) return 20;
        if (level.contains("异形") || level.contains("巴洛克")) return 10;
        return 65;
    }

    private int colorScore(Map<String, Object> color) {
        int explicit = toInt(color.get("score"), 0);
        if (explicit > 0) {
            if (explicit >= 85) return 100;
            if (explicit >= 55) return 70;
            return 40;
        }
        String text = safeString(firstValue(color.get("quality"), color.get("name"), color.get("representative"), color.get("description")), "");
        if (text.contains("优质") || text.contains("冷光") || text.contains("银白") || text.contains("孔雀")
                || text.contains("浓玫瑰") || text.contains("真多麻") || text.contains("浓茶金")
                || text.contains("橙金") || text.contains("高饱和")) {
            return 100;
        }
        if (text.contains("偏色") || text.contains("暗") || text.contains("灰") || text.contains("杂")
                || text.contains("低饱和") || text.contains("褐黄")) {
            return 40;
        }
        return 70;
    }

    private String mapScoreToQualityGrade(int score) {
        if (score >= 93) return "A+";
        if (score >= 85) return "A";
        if (score >= 78) return "A-";
        if (score >= 70) return "B+";
        if (score >= 62) return "B";
        if (score >= 54) return "B-";
        if (score >= 42) return "C+";
        if (score >= 30) return "C";
        return "C-";
    }

    private String applyQualityVetoRules(String grade, Map<String, Object> luster, Map<String, Object> blemish, Map<String, Object> roundness, Map<String, Object> color) {
        String result = normalizeQualityGrade(grade);
        if (!StringUtils.hasText(result)) {
            result = "B";
        }
        String blemishGrade = normalizeGrade(firstText(blemish.get("grade"), blemish.get("level")));
        if ("E".equals(blemishGrade)) {
            result = worseQualityGrade(result, "C");
        } else if ("D".equals(blemishGrade)) {
            result = worseQualityGrade(result, "C+");
        }
        String lusterLevel = safeString(luster.get("level"), "");
        if (lusterLevel.contains("弱")) {
            result = worseQualityGrade(result, "B-");
        } else if (lusterLevel.contains("中")) {
            result = worseQualityGrade(result, "A-");
        }
        String roundnessLevel = safeString(roundness.get("level"), "");
        if (roundnessLevel.contains("椭圆") || roundnessLevel.contains("扁圆") || roundnessLevel.contains("水滴")
                || roundnessLevel.contains("异形") || roundnessLevel.contains("巴洛克")) {
            result = worseQualityGrade(result, "B-");
        }
        if (colorScore(color) <= 40) {
            result = degradeQualityGrade(result);
        }
        return result;
    }

    private String worseQualityGrade(String grade, String cap) {
        int gradeRank = qualityGradeRank(grade);
        int capRank = qualityGradeRank(cap);
        return gradeRank < capRank ? cap : grade;
    }

    private String degradeQualityGrade(String grade) {
        String[] order = qualityGradeOrder();
        int rank = qualityGradeRank(grade);
        return order[Math.min(order.length - 1, rank + 1)];
    }

    private int qualityGradeRank(String grade) {
        String normalized = normalizeQualityGrade(grade);
        String[] order = qualityGradeOrder();
        for (int index = 0; index < order.length; index += 1) {
            if (order[index].equals(normalized)) {
                return index;
            }
        }
        return 4;
    }

    private String[] qualityGradeOrder() {
        return new String[]{"A+", "A", "A-", "B+", "B", "B-", "C+", "C", "C-"};
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

    private String normalizeQualityGrade(String value) {
        String text = value == null ? "" : value.trim().toUpperCase();
        String cleaned = text.replace(" ", "");
        String[] matchOrder = new String[]{"A+", "A-", "B+", "B-", "C+", "C-", "A", "B", "C"};
        for (String grade : matchOrder) {
            if (cleaned.startsWith(grade)) {
                return grade;
            }
        }
        if (text.contains("极品")) return "A+";
        if (text.contains("收藏") && text.contains("优")) return "A";
        if (text.contains("收藏")) return "A-";
        if (text.contains("精选") && text.contains("顶")) return "B+";
        if (text.contains("精选") && text.contains("入门")) return "B-";
        if (text.contains("精选")) return "B";
        if (text.contains("入门") && text.contains("高")) return "C+";
        if (text.contains("入门") && text.contains("基础")) return "C-";
        if (text.contains("入门")) return "C";
        return "";
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
        return firstValue(new Object[]{first, second});
    }

    private Object firstValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
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

    private String buildPrompt(String mode, PearlAnalyzeContext context) {
        String modeHint = "quick".equals(mode) ? "用户使用快速鉴定，可能只有 1 张图片。" : "用户使用完整鉴定，可能包含多角度图片。";
        return String.join("\n",
                "你是一名谨慎的珍珠图片初筛助手。请根据用户上传的珍珠照片做初步判断。",
                modeHint,
                buildSupplementContextPrompt(context),
                "必须只输出 JSON，不要输出 Markdown，不要输出额外解释。",
                "除最终 JSON 字段名外，不要输出英文单词、英文说明或英文推理过程；所有可读说明必须使用中文。",
                "不要声称自己出具权威鉴定证书。结果只能是图片初筛建议。",
                buildStagedDecisionPrompt(),
                "第一步必须判断图片中是否存在明确珍珠主体。若图片不是珍珠、没有珍珠、主体与珍珠鉴定无关，必须输出 pearlDetected=false，authenticity=非珍珠，result=未检测到珍珠，confidence=0，不要继续猜淡水珍珠、海水珍珠或仿珠类型。",
                "只有 pearlDetected=true 时，才进入真假、类型、亮度、圆度、瑕疵和颜色评估。",
                "即使珍珠图片不够完美，也必须基于可见信息给出估算值。不要输出“待复核”“无法判断”“不确定”等状态，可用较低 confidence 表达不确定性。",
                "authenticity 只能从：真、假、非珍珠 中选择。非珍珠只用于图片未检测到珍珠主体的场景。",
                buildPearlKnowledgeBase(),
                buildPearlPriceKnowledgeBase(),
                "真珍珠类型只能从：淡水珍珠、爱迪生、海水珍珠、澳白、Akoya、南洋白珠、南洋金珠、南洋大溪地、大溪地黑珍珠、马贝 中选择。",
                "假珠类型只能从：塑料仿珠、玻璃仿珠、贝珠、施家珍珠、染色/覆膜珠 中选择。",
                "只有 authenticity=真 且 pearlDetected=true 时才输出价格估算 priceEstimate；authenticity=假 或 非珍珠时 priceEstimate 必须为 null。",
                "如果用户补充了价格、渠道或直径：必须把这些信息作为辅助校准因素写入 reasons 或 suggestions 中，但不能代替图片证据。",
                "低价大直径、高风险渠道、标称高价值品类但价格明显不匹配时，要提高仿珠/贝珠/玻璃仿珠/塑料仿珠的警惕，并适当降低 confidence。",
                "正规线下珠宝店、珍珠市场或可信渠道只能作为轻微信号，不可仅凭渠道判真；直播间、电商低价、旅游景区、地摊、二手来源要更谨慎。",
                "用户填写直径时，priceEstimate.sizeReference 优先使用该直径；未填写时再按图片估算或默认 9-10mm。",
                "如果只有正面整体图、未见孔口和微观纹理，不要给出 85 以上 confidence；若同时存在低价或高风险渠道，优先把 confidence 控制在 70 以下。",
                "如果图片看起来像珍珠但缺少天然生长纹、孔口层状结构或深邃晕彩中的至少两项证据，不要轻易判为真。",
                "亮度只能从：极强光、强光（高光）、中光、弱光 中选择。",
                "圆度只能从：正圆、近正圆、近圆、椭圆、扁圆、水滴、异形（巴洛克） 中选择。",
                "瑕疵等级只能从：A 无瑕、B 微瑕、C 小瑕、D 瑕疵、E 重瑕 中选择。",
                "瑕疵描述参考：A 肉眼观察极难见瑕疵；B 极少针点状瑕疵，肉眼较难发现；C 较小瑕疵，肉眼易见；D 明显瑕疵，占表面积1/4以下；E 严重瑕疵，占表面积1/4以上。",
                "颜色系列参考：白色系（纯白、奶白、银白、瓷白）；黑色系（黑、灰黑、蓝黑、褐黑）；红色/粉色系（粉红、浅玫瑰红、浅紫红）；黄色/金色系（浅黄、米黄、金黄、橙黄）；其他色系（紫、青、蓝、绿、古铜色等）。",
                buildPearlGradingSystem(),
                "JSON 字段：",
                "{",
                "  \"pearlDetected\": true/false,",
                "  \"result\": \"淡水珍珠/Akoya/塑料仿珠/未检测到珍珠\",",
                "  \"authenticity\": \"真/假/非珍珠\",",
                "  \"pearlType\": \"淡水珍珠/爱迪生/海水珍珠/澳白/Akoya/南洋白珠/南洋金珠/南洋大溪地/大溪地黑珍珠/马贝\",",
                "  \"imitationType\": \"塑料仿珠/玻璃仿珠/贝珠/施家珍珠/染色/覆膜珠/空字符串\",",
                "  \"confidence\": 1-99,",
                "  \"priceEstimate\": {\"currency\":\"CNY\", \"unit\":\"元/颗\", \"sizeReference\":\"估算规格如 9-10mm，尺寸不明则写 9-10mm 默认参考\", \"tier\":\"入门/精选/收藏\", \"range\":\"如 300 或 250-400\", \"basis\":\"按山下湖多品种多规格参考表和图片等级估算\", \"note\":\"仅供图片初筛参考\"} 或 null,",
                "  \"qualityGrade\": {\"grade\":\"A+/A/A-/B+/B/B-/C+/C/C-\", \"name\":\"收藏·极品/收藏·优级/收藏·入门/精选·顶级/精选·中档/精选·入门/入门·高配/入门·标准/入门·基础\", \"score\":1-100, \"description\":\"按9级总评标准描述\"},",
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

    private String buildStagedDecisionPrompt() {
        return String.join("\n",
                "分阶段判定流程，必须按顺序完成，不允许直接根据整体观感一步到位下结论：",
                "阶段1：图片有效性。先判断是否有明确珍珠主体、主体占比、清晰度、是否有过曝/美颜/滤镜；如果没有珍珠主体，立即按非珍珠输出。",
                "阶段2：可用证据枚举。逐项检查表面纹理、孔口/打孔处、光泽反光、颜色分布、形状轮廓、尺寸/价格/渠道冲突。没有看到的维度必须标记为未见证据，不能编造。",
                "阶段3：真假风险对照。分别列出支持真珍珠的证据和支持仿珠/处理珠的证据；只有支持真珍珠的证据明显多于反向证据时，才输出 authenticity=真。",
                "阶段4：品类和价格校准。先按视觉证据估计品类，再用用户填写的直径、价格和渠道检查是否与市场价冲突；冲突明显时降低 confidence，并优先考虑假珠或处理珠。",
                "阶段5：最终结论。结论必须来自前面证据，不确定时降低 confidence，不能用高置信度掩盖证据不足。",
                "输出 JSON 时不要展开完整思维链，但 reasons 必须按证据顺序给出：第一条写核心视觉证据，第二条写反向证据或缺失证据，第三条写价格/渠道/直径校准或不确定性。"
        );
    }

    private String buildSupplementContextPrompt(PearlAnalyzeContext context) {
        if (context == null) {
            return "用户未填写价格、渠道、直径等补充信息，请仅依据图片和通用知识库判断。";
        }
        String price = safeString(context.getPrice(), "");
        String channel = safeString(context.getChannel(), "");
        String diameter = safeString(context.getDiameter(), "");
        if (!StringUtils.hasText(price) && !StringUtils.hasText(channel) && !StringUtils.hasText(diameter)) {
            return "用户未填写价格、渠道、直径等补充信息，请仅依据图片和通用知识库判断。";
        }
        List<String> items = new ArrayList<String>();
        if (StringUtils.hasText(price)) {
            items.add("用户填写价格：" + price + " 元");
        }
        if (StringUtils.hasText(channel)) {
            items.add("用户填写渠道：" + channel);
        }
        if (StringUtils.hasText(diameter)) {
            items.add("用户填写直径：" + diameter + " mm");
        }
        return String.join("\n",
                "用户补充信息（非必填、自述信息，仅作辅助校准，不可替代图片证据）：",
                String.join("；", items),
                "使用方式：",
                "- 价格用于检查品类与市场价是否明显冲突。例如大直径澳白、Akoya、南洋金珠、大溪地黑珍珠若价格明显低于常识，应提高仿珠/贝珠/玻璃仿珠/塑料仿珠或优化处理的可能性。",
                "- 渠道用于风险校准。直播间、电商低价、旅游景区/地摊、二手来源更容易出现低价仿珠或处理珠；线下珠宝店、珍珠市场/批发也不能直接判真，只能略微提升可信度。",
                "- 直径用于价格和品类校准。大直径真海水珠通常价格显著上升；低价大直径且图片缺少天然纹理/孔口证据时，应更谨慎判假。",
                "- 如果补充信息和图片证据冲突，请优先相信图片证据，并在 reasons 中说明冲突点。"
        );
    }

    private String buildPearlGradingSystem() {
        return String.join("\n",
                "珍珠品质9级分级量化标准 v2.0：",
                "总评得分 = 光泽分×35% + 瑕疵分×30% + 圆度分×20% + 颜色分×15%。",
                "光泽分：极强光=100，强光（高光）=75，中光=50，弱光=25。",
                "瑕疵分：A无瑕=100，B微瑕=80，C小瑕=60，D瑕疵=30，E重瑕=10。",
                "圆度分：正圆=100，近正圆=85，近圆=65，椭圆=45，扁圆=30，水滴=20，异形（巴洛克）=10。",
                "颜色分：先判色系，再判色质；优质色=100，正色=70，偏色=40。白色系优质色参考冷光蓝调/银白，正色参考纯白/奶白，偏色参考黄白/灰白；黑色系优质色参考孔雀绿/蓝黑；红粉色系优质色参考浓玫瑰红/真多麻；黄/金色系优质色参考浓茶金/橙金；其他色系优质色参考稀有高饱和色。",
                "9级映射：93-100=A+ 收藏·极品；85-92=A 收藏·优级；78-84=A- 收藏·入门；70-77=B+ 精选·顶级；62-69=B 精选·中档；54-61=B- 精选·入门；42-53=C+ 入门·高配；30-41=C 入门·标准；0-29=C- 入门·基础。",
                "否决规则优先级：瑕疵否决 > 光泽否决 > 圆度否决 > 颜色降级。瑕疵D封顶C+；瑕疵E封顶C；弱光封顶B-；中光封顶A-；圆度为椭圆/扁圆/水滴/异形时封顶B-；颜色为偏色时最终等级降1档。",
                "qualityGrade 必须输出整体品质9级，不要把瑕疵 A-E 直接当作整体品质等级；瑕疵 A-E 只放在 blemish 字段。"
        );
    }

    private String buildPearlPriceKnowledgeBase() {
        return String.join("\n",
                "山下湖珍珠明细市场参考价，单位为人民币元/颗，仅用于真珍珠图片初筛粗估。",
                "价格等级定义：入门=光泽中、可见瑕疵；精选=光泽强、微瑕；收藏=光泽极强、几乎无瑕。",
                "淡水珍珠（无核/有核，6mm以上主要参考无核）：2-3mm 入门5/精选15/收藏30；3-4mm 10/25/50；4-5mm 15/40/80；5-6mm 25/60/120；6-7mm 40/100/200；7-8mm 60/150/300；8-9mm 80/200/400；9-10mm 120/300/600；10-11mm 200/500/1000。注：淡水无核珍珠正圆率极低，9mm以上正圆收藏级稀缺。",
                "爱迪生（有核淡水）：8-9mm 30/80/200；9-10mm 50/150/400；10-11mm 80/250/600；11-12mm 150/400/1000；12-13mm 300/800/2000；13-14mm 600/1500/4000；14-15mm 1000/2500/8000。注：圆度优于无核淡水，大颗粒性价比突出。",
                "南洋澳白/南洋白珠（海水）：8-9mm 500/1500/4000；9-10mm 800/2500/7000；10-11mm 1500/4000/12000；11-12mm 3000/7000/20000；12-13mm 5000/12000/35000；13-14mm 8000/20000/60000；14-15mm 15000/35000/100000；15-16mm 30000/60000/200000。注：银白冷光为贵，13mm以上大珠稀缺。",
                "南洋金珠（海水）：8-9mm 400/1200/3500；9-10mm 600/2000/6000；10-11mm 1200/3500/10000；11-12mm 2500/6000/18000；12-13mm 4000/10000/30000；13-14mm 7000/18000/50000；14-15mm 12000/30000/90000。注：浓金为贵，浅色金价格偏低。",
                "南洋大溪地/大溪地黑珍珠（海水）：8-9mm 300/1000/3000；9-10mm 500/1800/5000；10-11mm 1000/3000/9000；11-12mm 2000/5500/16000；12-13mm 3500/9000/28000；13-14mm 6000/15000/45000；14-15mm 10000/25000/80000。注：孔雀绿伴彩为贵，纯黑无伴彩价格相对低。",
                "日本Akoya/Akoya（海水）：2-3mm 10/30/80；3-4mm 20/60/150；4-5mm 40/120/300；5-6mm 80/250/600；6-7mm 150/500/1200；7-8mm 300/1000/2500；8-9mm 600/2000/5000；9-10mm 1200/4000/10000。注：粉青光伴彩为贵，9-10mm 已属大尺寸。",
                "马贝（海水/半圆）：13-14mm 200/600/1500；14-15mm 350/1000/2500；15-16mm 600/1800/4500；16-17mm 1000/3000/8000；17-18mm 1800/5000/15000。注：以饱满度、彩虹光泽和表面无瑕度为评判标准。",
                "价格估算规则：先识别珍珠类型，再尽量根据图片中的相对尺寸、用户上传角度或可见参照估算规格；若无法可靠判断尺寸，默认按 9-10mm 单颗参考价输出，并在 sizeReference 或 note 中说明“默认参考”。",
                "输出 priceEstimate.range 时可以给单点参考价（如 300）或保守区间（如 250-400）；若图片质量不足、尺寸不明或类型不稳，应扩大为保守区间并降低 confidence。",
                "价格估算规则：根据亮度和瑕疵等级选择入门/精选/收藏；A或极强光优先收藏，B或强光优先精选，C/D/E或中弱光优先入门。",
                "价格系数规则：A+ 使用收藏价×1.00，A 使用收藏价×0.80，A- 使用收藏价×0.60；B+ 使用精选价×1.00，B 使用精选价×0.80，B- 使用精选价×0.60；C+ 使用入门价×1.00，C 使用入门价×0.80，C- 使用入门价×0.60。",
                "假珍珠、非珍珠图片、无法确认珍珠主体时，不要输出价值估算，priceEstimate 必须为 null。"
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
