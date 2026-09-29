package com.dentallab.model;

/** 试戴不密合的具体表现：医生试戴时反馈“咬合高”或“边缘不密合”。 */
public enum MisfitType {
    HIGH_BITE("咬合高"),
    EDGE_GAP("边缘不密合"),
    BOTH("咬合高且边缘不密合");

    private final String label;

    MisfitType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
