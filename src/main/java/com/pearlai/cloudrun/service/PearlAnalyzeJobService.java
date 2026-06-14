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
            job.setStatus("RUNNING");
            job.setUpdatedAt(Instant.now().toString());
            PearlReport report = arkVisionService.analyze(request);
            job.setReport(report);
            job.setStatus("SUCCEEDED");
            job.setUpdatedAt(Instant.now().toString());
        } catch (Exception error) {
            job.setError(error.getMessage());
            job.setStatus("FAILED");
            job.setUpdatedAt(Instant.now().toString());
        }
    }
}
