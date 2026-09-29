package com.dentallab.model;

/** 试戴不密合的具体问题分类（医生试戴反馈）。 */
public enum MisfitType {
    HIGH_BITE("咬合高"),
    EDGE_MISFIT("边缘不密合");

    private final String label;

    MisfitType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
