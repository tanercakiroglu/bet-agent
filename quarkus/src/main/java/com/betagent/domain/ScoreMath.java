/*
 * Decompiled with CFR 0.152.
 */
package com.betagent.domain;

public final class ScoreMath {
    private ScoreMath() {
    }

    public static String oddsBand(double decimalOdds) {
        return ScoreMath.coarseOddsBand(decimalOdds);
    }

    public static String coarseOddsBand(double decimalOdds) {
        if (decimalOdds < 1.5) {
            return "1.20-1.39";
        }
        if (decimalOdds < 1.7) {
            return "1.40-1.59";
        }
        if (decimalOdds < 1.9) {
            return "1.60-1.79";
        }
        if (decimalOdds < 2.1) {
            return "1.80-1.99";
        }
        if (decimalOdds < 2.3) {
            return "2.00-2.19";
        }
        if (decimalOdds < 2.5) {
            return "2.20-2.39";
        }
        if (decimalOdds < 2.7) {
            return "2.40-2.59";
        }
        if (decimalOdds < 2.9) {
            return "2.60-2.79";
        }
        if (decimalOdds < 3.1) {
            return "2.80-2.99";
        }
        if (decimalOdds < 3.3) {
            return "3.00-3.19";
        }
        if (decimalOdds < 3.5) {
            return "3.20-3.39";
        }
        if (decimalOdds < 3.7) {
            return "3.40-3.59";
        }
        if (decimalOdds < 3.9) {
            return "3.60-3.79";
        }
        if (decimalOdds < 4.1) {
            return "3.80-3.99";
        }
        return "4.00+";
    }

    public static double wilsonLow(int hits, int total, double z) {
        if (total == 0) {
            return 0.0;
        }
        double p = (double)hits / (double)total;
        double z2 = z * z;
        double denom = 1.0 + z2 / (double)total;
        double center = p + z2 / (double)(2 * total);
        double margin = z * Math.sqrt((p * (1.0 - p) + z2 / (double)(4 * total)) / (double)total);
        return Math.max(0.0, (center - margin) / denom);
    }

    /** İY KG+taraf, İY KG ve HT/FT gibi nadir kombinasyonlarda mutlak %35 Wilson mümkün değil; implied ile ölçekle. */
    public static double wilsonMinThreshold(
            String market, double implied, double minConfidenceLow, boolean scaleByImplied) {
        if (scaleByImplied
                && (Markets.FIRST_HALF_KG_TARAF.equals(market)
                        || Markets.FIRST_HALF_BTTS.equals(market)
                        || Markets.HTFT.equals(market))) {
            return minConfidenceLow * implied;
        }
        return minConfidenceLow;
    }
}

