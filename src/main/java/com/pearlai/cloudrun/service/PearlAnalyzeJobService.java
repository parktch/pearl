package com.pearlai.cloudrun.service;

import com.pearlai.cloudrun.dto.PearlAnalyzeJob;
import com.pearlai.cloudrun.dto.PearlAnalyzeRequest;
import com.pearlai.cloudrun.dto.PearlReport;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PearlAnalyzeJobService {

    private static final String[] READABLE_PROGRESS = new String[]{
            "正在确认图片里是否有清晰的珍珠主体。",
            "正在观察珍珠轮廓，判断是否适合继续分析。",
            "正在查看表面反光，区分柔和光泽和表面浮光。",
            "正在寻找纹理、孔口和边缘细节。",
            "正在对比颜色分布和伴色变化。",
            "正在综合判断真假、类型和可信度。",
            "正在整理亮度、圆度、瑕疵和颜色维度。",
            "正在把识别依据整理成易读报告。"
    };

    private final ArkVisionService arkVisionService;
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private final Map<String, PearlAnalyzeJob> jobs = new ConcurrentHashMap<String, PearlAnalyzeJob>();

    public PearlAnalyzeJobService(ArkVisionService arkVisionService) {
        this.arkVisionService = arkVisionService;
    }

    public PearlAnalyzeJob submit(PearlAnalyzeRequest request) {
        String now = Instant.now().toString();
        PearlAnalyzeJob job = new PearlAnalyzeJob();
        job.setJobId("JOB" + UUID.randomUUID().toString().replace("-", ""));
        job.setStatus("PENDING");
        job.setProgressText("鉴定任务已提交，正在排队分析...");
        job.setAnswerText("");
        job.setReasoningText("鉴定任务已提交，正在排队分析...");
        job.setProgressPercent(12);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobs.put(job.getJobId(), job);

        executorService.submit(new Runnable() {
            @Override
            public void run() {
                runJob(job.getJobId(), request);
            }
        });

        return job;
    }

    public PearlAnalyzeJob get(String jobId) {
        PearlAnalyzeJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在或已过期");
        }
        return job;
    }

    private void runJob(String jobId, PearlAnalyzeRequest request) {
        PearlAnalyzeJob job = jobs.get(jobId);
        if (job == null) {
            return;
        }

        try {
            final int[] progressPercent = new int[]{18};
            final int[] deltaCount = new int[]{0};
            job.setStatus("RUNNING");
            job.setProgressText("正在读取图片特征...");
            job.setReasoningText("正在读取图片特征...");
            job.setProgressPercent(progressPercent[0]);
            job.setUpdatedAt(Instant.now().toString());
            PearlReport report = arkVisionService.analyzeWithProgress(request, new ArkVisionService.StreamProgressListener() {
                @Override
                public void onStart(String message) {
                    updateProgress(jobId, message, "", message, 24);
                }

                @Override
                public void onDelta(String content, String reasoning) {
                    progressPercent[0] = Math.min(88, progressPercent[0] + 2);
                    deltaCount[0] += 1;
                    String progressText = buildReadableProgress(deltaCount[0], progressPercent[0]);
                    updateProgress(jobId, progressText, "", progressText, progressPercent[0]);
                }

                @Override
                public void onReport(PearlReport report) {
                    updateProgress(jobId, "分析完成，正在生成最终鉴定报告。", "", "分析完成，正在生成最终鉴定报告。", 96);
                }
            });
            job.setReport(report);
            job.setStatus("SUCCEEDED");
            job.setProgressText("分析完成，正在打开鉴定报告。");
            job.setAnswerText("");
            job.setReasoningText("分析完成，正在打开鉴定报告。");
            job.setProgressPercent(100);
            job.setUpdatedAt(Instant.now().toString());
        } catch (Exception error) {
            job.setError(error.getMessage());
            job.setStatus("FAILED");
            job.setProgressText("鉴定任务失败，请稍后重试。");
            job.setAnswerText("");
            job.setReasoningText("鉴定任务失败，请稍后重试。");
            job.setUpdatedAt(Instant.now().toString());
        }
    }

    private String buildReadableProgress(int deltaCount, int progressPercent) {
        int index = Math.min(READABLE_PROGRESS.length - 1, Math.max(0, deltaCount / 8));
        String message = READABLE_PROGRESS[index];
        if (progressPercent >= 80) {
            return "正在做最后的综合判断，并准备生成报告。";
        }
        return message;
    }

    private void updateProgress(String jobId, String progressText, String answerText, String reasoningText, int progressPercent) {
        PearlAnalyzeJob job = jobs.get(jobId);
        if (job == null) {
            return;
        }
        job.setProgressText(progressText);
        job.setAnswerText(answerText);
        job.setReasoningText(reasoningText);
        job.setProgressPercent(progressPercent);
        job.setUpdatedAt(Instant.now().toString());
    }
}
