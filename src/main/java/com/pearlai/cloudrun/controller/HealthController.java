package com.pearlai.cloudrun.controller;

import com.pearlai.cloudrun.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/")
    public ApiResponse<Map<String, String>> index() {
        Map<String, String> data = new HashMap<String, String>();
        data.put("service", "pearl-cloudrun-springboot");
        data.put("status", "ok");
        return ApiResponse.ok(data);
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> health() {
        Map<String, String> data = new HashMap<String, String>();
        data.put("status", "ok");
        return ApiResponse.ok(data);
    }
}
