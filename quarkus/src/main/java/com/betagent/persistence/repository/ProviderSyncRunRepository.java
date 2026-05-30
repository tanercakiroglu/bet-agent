/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
 *  io.quarkus.hibernate.reactive.panache.common.WithSession
 *  io.quarkus.hibernate.reactive.panache.common.WithTransaction
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 */
package com.betagent.persistence.repository;

import com.betagent.persistence.entity.ProviderSyncRunEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@WithSession
public class ProviderSyncRunRepository
implements PanacheRepositoryBase<ProviderSyncRunEntity, UUID> {
    public Uni<Optional<ProviderSyncRunEntity>> findLatest(String provider) {
        return this.find("provider = ?1 order by startedAt desc", new Object[]{provider}).firstResult().map(Optional::ofNullable);
    }

    public Uni<Optional<ProviderSyncRunEntity>> findRunning(String provider) {
        return this.find("provider = ?1 and status = 'running'", new Object[]{provider}).firstResult().map(Optional::ofNullable);
    }

    public Uni<List<ProviderSyncRunEntity>> findRecent(String provider, int limit) {
        return this.find("provider = ?1 order by startedAt desc", new Object[]{provider}).page(0, limit).list();
    }

    public Uni<List<ProviderSyncRunEntity>> findRecentAll(int limit) {
        return this.find("order by startedAt desc", new Object[0]).page(0, Math.max(1, limit)).list();
    }

    public Uni<List<ProviderSyncRunEntity>> findRecentForProviders(List<String> providers, int limit) {
        if (providers == null || providers.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return this.find("provider in ?1 order by startedAt desc", new Object[]{providers}).page(0, limit).list();
    }

    public Uni<Optional<ProviderSyncRunEntity>> findLatestAcross(List<String> providers) {
        if (providers == null || providers.isEmpty()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return this.find("provider in ?1 order by startedAt desc", new Object[]{providers}).firstResult().map(Optional::ofNullable);
    }

    @WithTransaction
    public Uni<Integer> failStaleRuns(String provider, LocalDateTime startedBefore) {
        return this.update("status = 'failed', finishedAt = ?1 where provider = ?2 and status = 'running' and startedAt < ?3", new Object[]{LocalDateTime.now(), provider, startedBefore});
    }
}

