package com.pearlai.cloudrun.dto;

public class PearlAnalyzeJob {

    private String jobId;
    private String status;
    private PearlReport report;
    private String error;
    private String progressText;
    private String answerText;
    private String reasoningText;
    private Integer progressPercent;
    private String createdAt;
    private String updatedAt;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public PearlReport getReport() {
        return report;
    }

    public void setReport(PearlReport report) {
        this.report = report;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getProgressText() {
        return progressText;
    }

    public void setProgressText(String progressText) {
        this.progressText = progressText;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public String getReasoningText() {
        return reasoningText;
    }

    public void setReasoningText(String reasoningText) {
        this.reasoningText = reasoningText;
    }

    public Integer getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(Integer progressPercent) {
        this.progressPercent = progressPercent;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
