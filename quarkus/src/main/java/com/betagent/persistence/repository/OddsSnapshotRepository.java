/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.quarkus.hibernate.reactive.panache.PanacheRepository
 *  io.quarkus.hibernate.reactive.panache.common.WithSession
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 */
package com.betagent.persistence.repository;

import com.betagent.persistence.entity.OddsSnapshotEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
@WithSession
public class OddsSnapshotRepository
implements PanacheRepository<OddsSnapshotEntity> {
    public Uni<List<OddsSnapshotEntity>> findByProvider(String provider) {
        return this.list("provider", new Object[]{provider});
    }

    public Uni<Long> countHourlyByProvider(String provider) {
        return this.count("provider = ?1 and snapshotType like 'hourly_%'", new Object[]{provider});
    }
}

