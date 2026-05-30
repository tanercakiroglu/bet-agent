/*
 * Decompiled with CFR 0.152.
 */
package com.betagent.service;

final class RequestBudget {
    private final int max;
    private int used;

    RequestBudget(int max) {
        this.max = max;
    }

    boolean hasRemaining() {
        return this.used < this.max;
    }

    void consume() {
        ++this.used;
    }

    int used() {
        return this.used;
    }

    int remaining() {
        return Math.max(0, this.max - this.used);
    }
}

