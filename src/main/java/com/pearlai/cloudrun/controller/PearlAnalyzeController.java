package com.pearlai.cloudrun.controller;

import com.pearlai.cloudrun.dto.ApiResponse;
import com.pearlai.cloudrun.dto.PearlAnalyzeJob;
import com.pearlai.cloudrun.dto.PearlAnalyzeRequest;
import com.pearlai.cloudrun.dto.PearlReport;
import com.pearlai.cloudrun.service.ArkVisionService;
import com.pearlai.cloudrun.service.PearlAnalyzeJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/pearl")
public class PearlAnalyzeController {

    private final ArkVisionService arkVisionService;
    private final PearlAnalyzeJobService pearlAnalyzeJobService;

    public PearlAnalyzeController(ArkVisionService arkVisionService, PearlAnalyzeJobService pearlAnalyzeJobService) {
        this.arkVisionService = arkVisionService;
        this.pearlAnalyzeJobService = pearlAnalyzeJobService;
    }

    @PostMapping("/analyze")
    public ApiResponse<PearlReport> analyze(@RequestBody PearlAnalyzeRequest request) {
        return ApiResponse.ok(arkVisionService.analyze(request));
    }

    @PostMapping("/analyze/job")
    public ApiResponse<PearlAnalyzeJob> submitJob(@RequestBody PearlAnalyzeRequest request) {
        return ApiResponse.ok(pearlAnalyzeJobService.submit(request));
    }

    @GetMapping("/analyze/job/{jobId}")
    public ApiResponse<PearlAnalyzeJob> getJob(@PathVariable String jobId) {
        return ApiResponse.ok(pearlAnalyzeJobService.get(jobId));
    }

    @PostMapping(value = "/analyze/stream", produces = "application/x-ndjson;charset=UTF-8")
    public StreamingResponseBody analyzeStream(@RequestBody PearlAnalyzeRequest request) {
        return outputStream -> arkVisionService.streamAnalyze(request, outputStream);
    }
}
