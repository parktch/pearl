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
        job.setProgressText("AI 鉴定任务已提交，正在排队分析...");
        job.setAnswerText("");
        job.setReasoningText("AI 鉴定任务已提交，正在排队分析...");
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
            final StringBuilder answerText = new StringBuilder();
            final StringBuilder reasoningText = new StringBuilder();
            final int[] progressPercent = new int[]{18};
            job.setStatus("RUNNING");
            job.setProgressText("AI 正在读取图片特征...");
            job.setReasoningText("AI 正在读取图片特征...");
            job.setProgressPercent(progressPercent[0]);
            job.setUpdatedAt(Instant.now().toString());
            PearlReport report = arkVisionService.analyzeWithProgress(request, new ArkVisionService.StreamProgressListener() {
                @Override
                public void onStart(String message) {
                    updateProgress(jobId, message, "", message, 24);
                }

                @Override
                public void onDelta(String content, String reasoning) {
                    if (content != null && content.length() > 0) {
                        answerText.append(content);
                    }
                    if (reasoning != null && reasoning.length() > 0) {
                        reasoningText.append(reasoning);
                    }
                    progressPercent[0] = Math.min(88, progressPercent[0] + 2);
                    String progressText = reasoning != null && reasoning.length() > 0
                            ? reasoning
                            : (content != null && content.length() > 0 ? content : "AI 正在分析珍珠细节...");
                    updateProgress(jobId, progressText, answerText.toString(), reasoningText.toString(), progressPercent[0]);
                }

                @Override
                public void onReport(PearlReport report) {
                    updateProgress(jobId, "AI 鉴定完成，正在生成报告...", answerText.toString(), reasoningText.toString(), 96);
                }
            });
            job.setReport(report);
            job.setStatus("SUCCEEDED");
            job.setProgressText("AI 鉴定完成，正在生成报告...");
            job.setProgressPercent(100);
            job.setUpdatedAt(Instant.now().toString());
        } catch (Exception error) {
            job.setError(error.getMessage());
            job.setStatus("FAILED");
            job.setProgressText("AI 鉴定任务失败");
            job.setUpdatedAt(Instant.now().toString());
        }
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
