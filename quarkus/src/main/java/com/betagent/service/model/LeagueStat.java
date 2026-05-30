/*
 * Decompiled with CFR 0.152.
 */
package com.betagent.service.model;

public record LeagueStat(String league, int settled, int pending) {
    public int total() {
        return this.settled + this.pending;
    }
}

