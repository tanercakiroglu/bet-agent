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
import com.betagent.service.model.CollectionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.UUID;

@ApplicationScoped
@WithSession
public class SyncRunService {
    @Inject
    ProviderSyncRunRepository syncRunRepository;
    @Inject
    ObjectMapper mapper;

    @WithTransaction
    public Uni<ProviderSyncRunEntity> start(String catalogName, int requestBudget) {
        ProviderSyncRunEntity run = new ProviderSyncRunEntity();
        run.id = UUID.randomUUID();
        run.provider = catalogName;
        run.status = "running";
        run.startedAt = LocalDateTime.now();
        run.requestBudget = requestBudget;
        return this.syncRunRepository.persist(run).replaceWith(run);
    }

    @WithTransaction
    public Uni<Void> finish(UUID id, CollectionResult result) {
        return this.syncRunRepository.findById(id).invoke(run -> {
            if (run == null) {
                return;
            }
            run.status = result.failures().isEmpty() ? "succeeded" : "partial";
            run.finishedAt = LocalDateTime.now();
            run.requestCount = result.requestCount();
            run.eventsSeen = result.eventsSeen();
            run.settledEvents = result.settledEvents();
            run.pendingEvents = result.pendingEvents();
            run.oddsPayloads = result.oddsPayloads();
            run.oddsSnapshotsInserted = result.snapshotsInserted();
            run.matchesInserted = result.matchesInserted();
            run.futureFixtures = result.pendingEvents();
            try {
                run.failuresJson = this.mapper.writeValueAsString(result.failures());
                run.leagueStatsJson = this.mapper.writeValueAsString(result.leagueStats());
                run.optimizationStatsJson = this.mapper.writeValueAsString(result.optimizationStats());
            }
            catch (Exception ex) {
                run.failuresJson = "[]";
                run.leagueStatsJson = "[]";
                run.optimizationStatsJson = "{}";
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

    public Uni<Void> finishAsync(UUID id, CollectionResult result) {
        return this.finish(id, result);
    }

    public Uni<Void> failAsync(UUID id, String error) {
        return this.fail(id, error);
    }
}

