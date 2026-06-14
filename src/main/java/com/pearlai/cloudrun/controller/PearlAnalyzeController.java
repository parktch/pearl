package com.pearlai.cloudrun.controller;

import com.pearlai.cloudrun.dto.ApiResponse;
import com.pearlai.cloudrun.dto.PearlAnalyzeRequest;
import com.pearlai.cloudrun.dto.PearlReport;
import com.pearlai.cloudrun.service.ArkVisionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/pearl")
public class PearlAnalyzeController {

    private final ArkVisionService arkVisionService;

    public PearlAnalyzeController(ArkVisionService arkVisionService) {
        this.arkVisionService = arkVisionService;
    }

    @PostMapping("/analyze")
    public ApiResponse<PearlReport> analyze(@RequestBody PearlAnalyzeRequest request) {
        return ApiResponse.ok(arkVisionService.analyze(request));
    }

    @PostMapping(value = "/analyze/stream", produces = "application/x-ndjson;charset=UTF-8")
    public StreamingResponseBody analyzeStream(@RequestBody PearlAnalyzeRequest request) {
        return outputStream -> arkVisionService.streamAnalyze(request, outputStream);
    }
}
