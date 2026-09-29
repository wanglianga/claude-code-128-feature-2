package com.dentallab.model;

/** 诊所月结单状态：财务生成 → 同步技工所 → 同步诊所并确认 → 财务最终确认。 */
public enum StatementStatus {
    GENERATED("已生成"),
    LAB_SYNCED("已同步技工所"),
    CLINIC_CONFIRMED("诊所已确认"),
    FINANCE_CONFIRMED("财务已确认");

    private final String label;

    StatementStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
