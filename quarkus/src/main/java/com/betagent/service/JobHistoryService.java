/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.core.type.TypeReference
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  io.quarkus.hibernate.reactive.panache.common.WithSession
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 */
package com.betagent.service;

import com.betagent.persistence.entity.ProviderSyncRunEntity;
import com.betagent.persistence.repository.ProviderSyncRunRepository;
import com.betagent.provider.OddsProviderRegistry;
import com.betagent.service.model.LeagueStat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@WithSession
public class JobHistoryService {
    private static final TypeReference<List<LeagueStat>> LEAGUE_STATS_TYPE = new TypeReference<List<LeagueStat>>(){};
    private static final TypeReference<List<Map<String, Object>>> FAILURES_TYPE = new TypeReference<List<Map<String, Object>>>(){};
    @Inject
    ProviderSyncRunRepository syncRunRepository;
    @Inject
    OddsProviderRegistry providerRegistry;
    @Inject
    ObjectMapper mapper;

    public Uni<List<Map<String, Object>>> list(int limit) {
        return this.syncRunRepository.findRecentAll(limit).map(runs -> runs.stream().map(this::toItem).toList());
    }

    private Map<String, Object> toItem(ProviderSyncRunEntity run) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("run_id", run.id);
        item.put("provider", run.provider);
        item.put("status", run.status);
        item.put("started_at", run.startedAt == null ? null : run.startedAt.atOffset(ZoneOffset.UTC));
        item.put("finished_at", run.finishedAt == null ? null : run.finishedAt.atOffset(ZoneOffset.UTC));
        item.put("request_count", run.requestCount);
        item.put("request_budget", run.requestBudget);
        item.put("events_seen", run.eventsSeen);
        item.put("settled_events", run.settledEvents);
        item.put("pending_events", run.pendingEvents);
        item.put("matches_inserted", run.matchesInserted);
        item.put("odds_snapshots_inserted", run.oddsSnapshotsInserted);
        item.put("leagues", this.parseLeagueStats(run.leagueStatsJson));
        item.put("optimization", this.parseOptimizationStats(run.optimizationStatsJson));
        item.put("failure_message", this.parseFailureMessage(run.failuresJson));
        return item;
    }

    private Map<String, Object> parseOptimizationStats(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return this.mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        }
        catch (Exception ex) {
            return Map.of();
        }
    }

    private String parseFailureMessage(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return null;
        }
        try {
            List rows = (List)this.mapper.readValue(json, FAILURES_TYPE);
            if (rows.isEmpty()) {
                return null;
            }
            Object msg = ((Map)rows.getFirst()).get("error");
            return msg == null ? null : String.valueOf(msg);
        }
        catch (Exception ex) {
            return json;
        }
    }

    private List<LeagueStat> parseLeagueStats(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return (List)this.mapper.readValue(json, LEAGUE_STATS_TYPE);
        }
        catch (Exception ex) {
            return List.of();
        }
    }
}

