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
import com.betagent.service.ScoreJobRunService;
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

    public Uni<Map<String, Object>> latestRepairSummary() {
        return this.syncRunRepository
                .findLatest(ScoreJobRunService.NESINE_REPAIR_PROVIDER)
                .map(opt -> opt.map(this::toRepairSummary).orElseGet(this::emptyRepairSummary));
    }

    private Map<String, Object> emptyRepairSummary() {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("has_run", false);
        summary.put("corrections_count", 0);
        summary.put("corrections", List.of());
        return summary;
    }

    private Map<String, Object> toRepairSummary(ProviderSyncRunEntity run) {
        Map<String, Object> item = this.toItem(run);
        Map<String, Object> optimization = this.parseOptimizationStats(run.optimizationStatsJson);
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("has_run", true);
        summary.put("run_id", item.get("run_id"));
        summary.put("status", item.get("status"));
        summary.put("started_at", item.get("started_at"));
        summary.put("finished_at", item.get("finished_at"));
        summary.put("trigger", optimization.getOrDefault("trigger", "scheduled"));
        summary.put("repaired", optimization.getOrDefault("repaired", 0));
        summary.put("cleared_suspicious", optimization.getOrDefault("cleared_suspicious", 0));
        summary.put("from_live_score", optimization.getOrDefault("from_live_score", 0));
        summary.put("from_cross_provider", optimization.getOrDefault("from_cross_provider", 0));
        summary.put("reference_corrected", optimization.getOrDefault("reference_corrected", 0));
        summary.put("still_missing", optimization.getOrDefault("still_missing", 0));
        summary.put("corrections_count", optimization.getOrDefault("corrections_count", 0));
        Object corrections = optimization.get("corrections");
        summary.put("corrections", corrections instanceof List<?> list ? list : List.of());
        return summary;
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

