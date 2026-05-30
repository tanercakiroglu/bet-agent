/*
 * Decompiled with CFR 0.152.
 */
package com.betagent.service.model;

import com.betagent.service.model.LeagueStat;
import java.util.List;
import java.util.Map;

public record CollectionResult(int requestCount, int eventsSeen, int settledEvents, int pendingEvents, int oddsPayloads, int snapshotsInserted, int matchesInserted, List<LeagueStat> leagueStats, Map<String, Object> optimizationStats, List<Map<String, String>> failures) {
}

