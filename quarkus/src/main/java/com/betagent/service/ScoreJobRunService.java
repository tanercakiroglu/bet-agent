/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  io.quarkus.hibernate.reactive.panache.common.WithSession
 *  io.quarkus.hibernate.reactive.panache.common.WithTransaction
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 */
package com.betagent.service;

import com.betagent.persistence.entity.ProviderSyncRunEntity;
import com.betagent.persistence.repository.ProviderSyncRunRepository;
import com.betagent.service.NesineScoreSettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
@WithSession
public class ScoreJobRunService {
    public static final String NESINE_SCORE_PROVIDER = "Nesine \u00b7 Skor";
    public static final String NESINE_REPAIR_PROVIDER = "Nesine \u00b7 Skor Onar";
    @Inject
    ProviderSyncRunRepository syncRunRepository;
    @Inject
    ObjectMapper mapper;

    @WithTransaction
    public Uni<ProviderSyncRunEntity> start() {
        return this.start(NESINE_SCORE_PROVIDER);
    }

    @WithTransaction
    public Uni<ProviderSyncRunEntity> startRepair() {
        return this.start(NESINE_REPAIR_PROVIDER);
    }

    @WithTransaction
    public Uni<ProviderSyncRunEntity> start(String providerLabel) {
        ProviderSyncRunEntity run = new ProviderSyncRunEntity();
        run.id = UUID.randomUUID();
        run.provider = providerLabel;
        run.status = "running";
        run.startedAt = LocalDateTime.now();
        run.requestBudget = 1;
        return this.syncRunRepository.persist(run).replaceWith(run);
    }

    @WithTransaction
    public Uni<Void> finish(UUID id, NesineScoreSettlementService.SettlementResult result, String trigger) {
        return this.syncRunRepository.findById(id).invoke(run -> {
            if (run == null) {
                return;
            }
            run.finishedAt = LocalDateTime.now();
            run.requestCount = 1;
            run.eventsSeen = result.trackedTotal();
            run.settledEvents = result.settledThisRun();
            run.pendingEvents = result.stillMissing();
            run.matchesInserted = result.fromLiveScore() + result.fromCrossProvider();
            run.oddsSnapshotsInserted = result.bridgedOdds();
            run.status = switch (result.status()) {
                case "disabled", "skipped" -> "skipped";
                case "ok" -> "succeeded";
                default -> "failed";
            };
            try {
                LinkedHashMap<String, Object> optimization = new LinkedHashMap<String, Object>();
                optimization.put("job_type", "nesine_score");
                optimization.put("trigger", trigger);
                optimization.put("from_live_score", result.fromLiveScore());
                optimization.put("from_cross_provider", result.fromCrossProvider());
                optimization.put("bridged_odds", result.bridgedOdds());
                optimization.put("settled_this_run", result.settledThisRun());
                optimization.put("tracked_total", result.trackedTotal());
                optimization.put("still_missing", result.stillMissing());
                run.optimizationStatsJson = this.mapper.writeValueAsString(optimization);
                run.leagueStatsJson = "[]";
                run.failuresJson = "[]";
            }
            catch (Exception ex) {
                run.optimizationStatsJson = "{}";
                run.failuresJson = "[]";
                run.leagueStatsJson = "[]";
            }
        }).replaceWithVoid();
    }

    @WithTransaction
    public Uni<Void> finishRepair(UUID id, Map<String, Object> result, String trigger) {
        return this.syncRunRepository.findById(id).invoke(run -> {
            if (run == null) {
                return;
            }
            run.finishedAt = LocalDateTime.now();
            run.requestCount = 1;
            run.eventsSeen = ((Number) result.getOrDefault("repaired", 0)).intValue();
            run.settledEvents = ((Number) result.getOrDefault("repaired", 0)).intValue();
            run.pendingEvents = ((Number) result.getOrDefault("still_missing", 0)).intValue();
            run.matchesInserted = ((Number) result.getOrDefault("repaired", 0)).intValue();
            run.oddsSnapshotsInserted = 0;
            run.status = "ok".equals(String.valueOf(result.get("status"))) ? "succeeded" : "failed";
            try {
                LinkedHashMap<String, Object> optimization = new LinkedHashMap<>();
                optimization.put("job_type", "nesine_score_repair");
                optimization.put("trigger", trigger);
                optimization.put("cleared_suspicious", result.get("cleared_suspicious"));
                optimization.put("from_live_score", result.get("from_live_score"));
                optimization.put("from_cross_provider", result.get("from_cross_provider"));
                optimization.put("reference_corrected", result.get("reference_corrected"));
                optimization.put("repaired", result.get("repaired"));
                optimization.put("still_missing", result.get("still_missing"));
                Object corrections = result.get("corrections");
                optimization.put(
                        "corrections_count",
                        corrections instanceof List<?> list ? list.size() : 0);
                optimization.put("corrections", corrections instanceof List<?> list ? list : List.of());
                run.optimizationStatsJson = this.mapper.writeValueAsString(optimization);
                run.leagueStatsJson = "[]";
                run.failuresJson = "[]";
            } catch (Exception ex) {
                run.optimizationStatsJson = "{}";
                run.failuresJson = "[]";
                run.leagueStatsJson = "[]";
            }
        }).replaceWithVoid();
    }

    @WithTransaction
    public Uni<Void> fail(UUID id, String error) {
        return this.syncRunRepository.findById(id).invoke(run -> {
            if (run == null) {
                return;
            }
            run.status = "failed";
            run.finishedAt = LocalDateTime.now();
            run.failuresJson = "[{\"error\":\"" + error.replace("\"", "'") + "\"}]";
        }).replaceWithVoid();
    }
}

