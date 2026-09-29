package com.dentallab.model;

/** 返工原因三分类：返工经诊所确认后由技工所标记，月结按对应责任调整费用。 */
public enum ReworkCause {
    DESIGN("设计问题"),
    PROCESSING("加工问题"),
    IMPRESSION("医生取模问题");

    private final String label;

    ReworkCause(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
