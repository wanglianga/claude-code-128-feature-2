package com.dentallab.model;

/** 试戴结果。 */
public enum TryInResult {
    FIT("密合"),
    MISFIT("不密合"),
    HIGH_BITE("咬合高");

    private final String label;

    TryInResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
