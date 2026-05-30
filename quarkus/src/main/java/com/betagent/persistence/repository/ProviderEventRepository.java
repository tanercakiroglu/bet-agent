/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.quarkus.hibernate.reactive.panache.PanacheRepository
 *  io.quarkus.hibernate.reactive.panache.common.WithSession
 *  jakarta.enterprise.context.ApplicationScoped
 */
package com.betagent.persistence.repository;

import com.betagent.persistence.entity.ProviderEventEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@WithSession
public class ProviderEventRepository
implements PanacheRepository<ProviderEventEntity> {
}

