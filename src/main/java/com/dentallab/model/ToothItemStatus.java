package com.dentallab.model;

/** 单个牙位的流转状态。 */
public enum ToothItemStatus {
    PENDING("待接收"),
    DESIGNING("扫描设计中"),
    WAITING_CONFIRM("待医生确认"),
    CONFIRMED("医生已确认"),
    ARRANGING("排牙中"),
    SINTERING("烧结中"),
    POLISHING("抛光中"),
    QC("质检中"),
    QC_PASSED("质检通过"),
    READY_TO_SHIP("待发货"),
    SHIPPED("已发货"),
    DELIVERED("已签收"),
    TRY_IN_OK("试戴密合"),
    TRY_IN_MISFIT("试戴不密合"),
    AWAIT_REWORK_CONFIRM("待确认返工"),
    REWORKING("返工中");

    private final String label;

    ToothItemStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
