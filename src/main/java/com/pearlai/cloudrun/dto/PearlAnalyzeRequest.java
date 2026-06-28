package com.pearlai.cloudrun.dto;

import java.util.ArrayList;
import java.util.List;

public class PearlAnalyzeRequest {

    private String mode;
    private List<ImageInput> images = new ArrayList<>();
    private PearlAnalyzeContext context = new PearlAnalyzeContext();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<ImageInput> getImages() {
        return images;
    }

    public void setImages(List<ImageInput> images) {
        this.images = images;
    }

    public PearlAnalyzeContext getContext() {
        return context;
    }

    public void setContext(PearlAnalyzeContext context) {
        this.context = context;
    }
}
