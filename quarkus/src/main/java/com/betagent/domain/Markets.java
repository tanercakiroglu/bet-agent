/*
 * Decompiled with CFR 0.152.
 */
package com.betagent.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Markets {
    public static final String HTFT = "HTFT";
    public static final String FIRST_HALF_KG_TARAF = "FIRST_HALF_KG_TARAF";
    public static final String FIRST_HALF_1X2 = "FIRST_HALF_1X2";
    public static final String FIRST_HALF_BTTS = "FIRST_HALF_BTTS";
    public static final Map<String, Set<String>> CANDIDATE_OUTCOMES = Map.of("HTFT", Set.of("1/2", "2/1", "1/X", "2/X"), "FIRST_HALF_1X2", Set.of("1", "X", "2"), "FIRST_HALF_KG_TARAF", Set.of("KG_VAR_1", "KG_VAR_X", "KG_VAR_2"));

    public static boolean htftOddsFromProvider(String catalogName) {
        return LeagueCatalog.NESINE_CATALOG.equals(catalogName);
    }

    public static List<String> trackedMarketsForProvider(String catalogName) {
        if (htftOddsFromProvider(catalogName)) {
            return List.of(HTFT, FIRST_HALF_1X2, FIRST_HALF_KG_TARAF, FIRST_HALF_BTTS);
        }
        return List.of(FIRST_HALF_1X2, FIRST_HALF_KG_TARAF, FIRST_HALF_BTTS);
    }

    private Markets() {
    }
}

