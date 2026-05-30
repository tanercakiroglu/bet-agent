/*
 * Decompiled with CFR 0.152.
 */
package com.betagent.provider;

public enum EventStatus {
    SETTLED("settled"),
    PENDING("pending");

    private final String apiValue;

    private EventStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return this.apiValue;
    }
}

