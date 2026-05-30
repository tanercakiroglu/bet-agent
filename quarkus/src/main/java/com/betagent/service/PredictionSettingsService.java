package com.betagent.service;

import com.betagent.config.PredictionConfig;
import com.betagent.persistence.entity.PredictionSettingsEntity;
import com.betagent.persistence.repository.PredictionSettingsRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@WithSession
public class PredictionSettingsService {
    @Inject
    PredictionConfig defaults;
    @Inject
    PredictionSettingsRepository repository;

    @WithTransaction
    public Uni<PredictionThresholds> resolve(String provider) {
        return this.repository.findById(provider).chain(entity -> {
            if (entity == null) {
                PredictionSettingsEntity created = new PredictionSettingsEntity();
                created.provider = provider;
                created.minSamples = this.defaults.minSamples();
                created.minEdge = this.defaults.minEdge();
                created.minConfidenceLow = this.defaults.minConfidenceLow();
                created.wilsonScaleByImplied = this.defaults.wilsonScaleByImplied();
                created.updatedAt = LocalDateTime.now();
                return this.repository.persist(created)
                        .replaceWith(new PredictionThresholds(
                                created.minSamples,
                                created.minEdge,
                                created.minConfidenceLow,
                                created.wilsonScaleByImplied,
                                created.updatedAt));
            }
            return Uni.createFrom().item(new PredictionThresholds(
                    entity.minSamples,
                    entity.minEdge,
                    entity.minConfidenceLow,
                    entity.wilsonScaleByImplied,
                    entity.updatedAt));
        });
    }

    @WithTransaction
    public Uni<PredictionThresholds> update(
            String provider, int minSamples, double minEdge, double minConfidenceLow, boolean wilsonScaleByImplied) {
        return this.repository.findById(provider).chain(entity -> {
            if (entity == null) {
                PredictionSettingsEntity created = new PredictionSettingsEntity();
                created.provider = provider;
                created.minSamples = minSamples;
                created.minEdge = minEdge;
                created.minConfidenceLow = minConfidenceLow;
                created.wilsonScaleByImplied = wilsonScaleByImplied;
                created.updatedAt = LocalDateTime.now();
                return this.repository.persist(created)
                        .replaceWith(new PredictionThresholds(
                                created.minSamples,
                                created.minEdge,
                                created.minConfidenceLow,
                                created.wilsonScaleByImplied,
                                created.updatedAt));
            }
            entity.minSamples = minSamples;
            entity.minEdge = minEdge;
            entity.minConfidenceLow = minConfidenceLow;
            entity.wilsonScaleByImplied = wilsonScaleByImplied;
            entity.updatedAt = LocalDateTime.now();
            return Uni.createFrom().item(new PredictionThresholds(
                    entity.minSamples,
                    entity.minEdge,
                    entity.minConfidenceLow,
                    entity.wilsonScaleByImplied,
                    entity.updatedAt));
        });
    }

    @WithTransaction
    public Uni<PredictionThresholds> resetToDefaults(String provider) {
        return this.update(
                provider,
                this.defaults.minSamples(),
                this.defaults.minEdge(),
                this.defaults.minConfidenceLow(),
                this.defaults.wilsonScaleByImplied());
    }

    public static Uni<Map<String, Object>> toMap(PredictionThresholds thresholds) {
        LinkedHashMap<String, Serializable> payload = new LinkedHashMap<>();
        payload.put("min_samples", thresholds.minSamples());
        payload.put("min_edge", thresholds.minEdge());
        payload.put("min_confidence_low", thresholds.minConfidenceLow());
        payload.put("wilson_scale_by_implied", thresholds.wilsonScaleByImplied());
        payload.put("updated_at", thresholds.updatedAt());
        Map<String, Object> response = new LinkedHashMap<>(payload);
        return Uni.createFrom().item(response);
    }

    public record PredictionThresholds(
            int minSamples,
            double minEdge,
            double minConfidenceLow,
            boolean wilsonScaleByImplied,
            LocalDateTime updatedAt) {}
}
