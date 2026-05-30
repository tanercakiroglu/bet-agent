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

import com.betagent.persistence.entity.MatchEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@WithSession
public class MatchRepository
implements PanacheRepository<MatchEntity> {
    public Uni<Long> countByProvider(String provider) {
        return this.count("provider", new Object[]{provider});
    }
}

