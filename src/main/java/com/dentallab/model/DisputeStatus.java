package com.dentallab.model;

/** 月结争议处理状态。 */
public enum DisputeStatus {
    OPEN("待处理"),
    RESOLVED("已解决");

    private final String label;

    DisputeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
