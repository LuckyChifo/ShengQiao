package com.shengqiao.app.ui.glass;

public enum GlassMode {
    SYSTEM("system"),
    LIQUID("liquid"),
    FROSTED("frosted");

    final String storageValue;

    GlassMode(String storageValue) {
        this.storageValue = storageValue;
    }

    public static GlassMode fromStorage(String value) {
        for (GlassMode mode : values()) {
            if (mode.storageValue.equals(value)) {
                return mode;
            }
        }
        return SYSTEM;
    }
}
