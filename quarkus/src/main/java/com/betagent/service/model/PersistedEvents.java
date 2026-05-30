/*
 * Decompiled with CFR 0.152.
 */
package com.betagent.service.model;

import com.betagent.service.model.LeagueStat;
import java.util.List;
import java.util.Map;

public record PersistedEvents(List<String> settledIds, List<String> settledEligibleForOddsIds, List<String> pendingIds, Map<String, String> statusById, int matchesInserted, List<LeagueStat> leagueStats) {
}

