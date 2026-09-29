package com.dentallab.model;

/** 返工单来源。 */
public enum ReworkSource {
    TRY_IN_MISFIT("试戴不密合"),
    DELIVERY_DAMAGE("配送破损"),
    MANUAL("人工登记");

    private final String label;

    ReworkSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
