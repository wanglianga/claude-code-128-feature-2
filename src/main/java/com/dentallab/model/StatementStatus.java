package com.dentallab.model;

/**
 * 诊所月结单状态：
 * 财务生成 → 技工所同步诊所 → 诊所确认 → 财务确认；
 * 诊所有异议可发起争议，争议处理后重新同步、再次确认。
 */
public enum StatementStatus {
    GENERATED("已生成待同步"),
    SYNCED("技工所已同步诊所"),
    CLINIC_CONFIRMED("诊所已确认"),
    DISPUTED("月结争议中"),
    FINANCE_CONFIRMED("财务已确认");

    private final String label;

    StatementStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
