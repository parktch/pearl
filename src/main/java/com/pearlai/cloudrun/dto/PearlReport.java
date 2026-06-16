package com.pearlai.cloudrun.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PearlReport {

    private String id;
    private String createdAt;
    private String result;
    private String authenticity;
    private Boolean pearlDetected;
    private String pearlType;
    private String imitationType;
    private int confidence;
    private Map<String, Object> priceEstimate;
    private Map<String, Object> qualityGrade;
    private List<Map<String, Object>> attributes = new ArrayList<>();
    private String summary;
    private List<String> reasons = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();
    private String rawText;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getAuthenticity() {
        return authenticity;
    }

    public void setAuthenticity(String authenticity) {
        this.authenticity = authenticity;
    }

    public Boolean getPearlDetected() {
        return pearlDetected;
    }

    public void setPearlDetected(Boolean pearlDetected) {
        this.pearlDetected = pearlDetected;
    }

    public String getPearlType() {
        return pearlType;
    }

    public void setPearlType(String pearlType) {
        this.pearlType = pearlType;
    }

    public String getImitationType() {
        return imitationType;
    }

    public void setImitationType(String imitationType) {
        this.imitationType = imitationType;
    }

    public int getConfidence() {
        return confidence;
    }

    public void setConfidence(int confidence) {
        this.confidence = confidence;
    }

    public Map<String, Object> getPriceEstimate() {
        return priceEstimate;
    }

    public void setPriceEstimate(Map<String, Object> priceEstimate) {
        this.priceEstimate = priceEstimate;
    }

    public Map<String, Object> getQualityGrade() {
        return qualityGrade;
    }

    public void setQualityGrade(Map<String, Object> qualityGrade) {
        this.qualityGrade = qualityGrade;
    }

    public List<Map<String, Object>> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<Map<String, Object>> attributes) {
        this.attributes = attributes;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }
}
