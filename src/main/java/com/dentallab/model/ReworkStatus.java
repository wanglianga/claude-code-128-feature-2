package com.dentallab.model;

/**
 * 返工单状态（试戴不密合返工流程）：
 * 待技工所提交方案 → 待诊所确认 → 返工重做 → 完成；诊所可驳回，技工所修订后重新提交。
 */
public enum ReworkStatus {
    AWAITING_PLAN("待技工所提交返工方案"),
    AWAITING_CONFIRM("待诊所确认返工方案"),
    REJECTED("诊所驳回待修订"),
    IN_REWORK("返工重做中（诊所已确认）"),
    COMPLETED("返工完成");

    private final String label;

    ReworkStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
