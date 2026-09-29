package com.dentallab.model;

/** 返工单状态：待诊所确认 → 已确认（返工生产中）→ 返工完成；诊所有异议可驳回。 */
public enum ReworkStatus {
    AWAIT_CONFIRM("待诊所确认"),
    CONFIRMED("诊所已确认"),
    COMPLETED("返工完成"),
    REJECTED("诊所驳回");

    /** 返工单是否仍挂起（未结案）。 */
    public boolean isActive() {
        return this == AWAIT_CONFIRM || this == CONFIRMED;
    }

    public String getLabel() {
        return label;
    }

    private final String label;

    ReworkStatus(String label) {
        this.label = label;
    }
}
