package com.dentallab.model;

/**
 * 返工原因归类：返工方案经诊所确认后由技工所标记，月结时按责任调整费用。
 * 设计问题=技工所设计责任；加工问题=技工所制作责任；医生取模问题=诊所责任。
 */
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
