/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
 *  io.quarkus.hibernate.reactive.panache.common.WithSession
 *  jakarta.enterprise.context.ApplicationScoped
 */
package com.betagent.persistence.repository;

import com.betagent.persistence.entity.PredictionSettingsEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@WithSession
public class PredictionSettingsRepository
implements PanacheRepositoryBase<PredictionSettingsEntity, String> {
}

