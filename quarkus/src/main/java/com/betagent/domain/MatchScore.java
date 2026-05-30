/*
 * Decompiled with CFR 0.152.
 */
package com.betagent.domain;

public record MatchScore(int hthg, int htag, int fthg, int ftag) {
    public String htResult() {
        return MatchScore.side(this.hthg, this.htag);
    }

    public String ftResult() {
        return MatchScore.side(this.fthg, this.ftag);
    }

    public String htftCode() {
        return this.htResult() + "/" + this.ftResult();
    }

    public String firstHalfKg() {
        return this.hthg > 0 && this.htag > 0 ? "VAR" : "YOK";
    }

    public String firstHalfKgTarafCode() {
        return "KG_" + this.firstHalfKg() + "_" + this.htResult();
    }

    private static String side(int home, int away) {
        if (home > away) {
            return "1";
        }
        if (home < away) {
            return "2";
        }
        return "X";
    }
}

