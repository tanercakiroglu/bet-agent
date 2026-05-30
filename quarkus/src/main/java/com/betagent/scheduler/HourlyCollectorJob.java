/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.quarkus.runtime.StartupEvent
 *  io.quarkus.scheduler.Scheduled
 *  io.smallrye.mutiny.Uni
 *  io.vertx.core.Vertx
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.enterprise.event.Observes
 *  jakarta.inject.Inject
 *  org.jboss.logging.Logger
 */
package com.betagent.scheduler;

import com.betagent.config.OddsApiConfig;
import com.betagent.persistence.ReactiveContextRunner;
import com.betagent.persistence.entity.ProviderSyncRunEntity;
import com.betagent.persistence.repository.ProviderSyncRunRepository;
import com.betagent.provider.OddsProviderRegistry;
import com.betagent.service.CollectorService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HourlyCollectorJob {
    private static final Logger LOG = Logger.getLogger(HourlyCollectorJob.class);
    private final AtomicReference<UUID> retriedFailedRunId = new AtomicReference();
    @Inject
    OddsApiConfig config;
    @Inject
    CollectorService collectorService;
    @Inject
    ProviderSyncRunRepository syncRunRepository;
    @Inject
    OddsProviderRegistry providerRegistry;
    @Inject
    ReactiveContextRunner contextRunner;
    @Inject
    Vertx vertx;

    void onStartup(@Observes StartupEvent ev) {
        this.vertx.setTimer(1000L, id -> this.contextRunner.subscribe(() -> this.releaseStaleRunsBefore(LocalDateTime.now())));
        if (!this.config.collectionEnabled() || !this.config.collectionRunOnStartup()) {
            return;
        }
        this.vertx.setTimer(3000L, id -> this.contextRunner.subscribe(this::startCollectorIfIdle));
    }

    @Scheduled(every="5m")
    void releaseStaleRuns() {
        this.contextRunner.subscribe(() -> this.releaseStaleRunsBefore(
                LocalDateTime.now().minusMinutes(this.config.staleRunMinutes())));
    }

    private Uni<Void> releaseStaleRunsBefore(LocalDateTime startedBefore) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String catalog : this.providerRegistry.configuredCatalogNames()) {
            chain = chain.chain(() -> this.syncRunRepository.failStaleRuns(catalog, startedBefore).invoke(updated -> {
                if (updated > 0) {
                    LOG.warnf("Marked %d stale collector runs as failed for %s", updated, catalog);
                }
            }).replaceWithVoid());
        }
        return chain;
    }

    @Scheduled(cron="{betagent.odds-api.collection-cron}")
    void run() {
        if (!this.config.collectionEnabled()) {
            return;
        }
        this.contextRunner.subscribe(this::startCollectorIfIdle);
    }

    @Scheduled(every="{betagent.odds-api.collection-tick-interval}")
    void retryFailedRun() {
        if (!this.config.collectionEnabled()) {
            return;
        }
        this.contextRunner.subscribe(this::retryFailedRunAsync);
    }

    private Uni<Void> startCollectorIfIdle() {
        return this.collectorService.isRunningOnContextUni().chain(running -> {
            if (running.booleanValue()) {
                LOG.info("Collector already running, skipping scheduled run.");
                return Uni.createFrom().voidItem();
            }
            LOG.info("Starting odds collector (force=true).");
            return this.collectorService.startBackgroundCollectAsync(true).invoke(run -> LOG.infof("Collector started: %s", run.id)).replaceWithVoid();
        });
    }

    private Uni<Void> retryFailedRunAsync() {
        return this.collectorService.isRunningOnContextUni().chain(running -> {
            if (running.booleanValue()) {
                return Uni.createFrom().voidItem();
            }
            List<String> catalogs = this.providerRegistry.configuredCatalogNames();
            return this.syncRunRepository.findRecentForProviders(catalogs, 20).chain(runs -> {
                ProviderSyncRunEntity latest = runs.stream().filter(run -> "failed".equals(run.status)).filter(run -> run.finishedAt != null).findFirst().orElse(null);
                if (latest == null || latest.finishedAt.isBefore(LocalDateTime.now().minusHours(2L))) {
                    return Uni.createFrom().voidItem();
                }
                if (latest.id.equals(this.retriedFailedRunId.get())) {
                    return Uni.createFrom().voidItem();
                }
                if (HourlyCollectorJob.isQuotaFailureAndMustWaitNextHour(latest)) {
                    LOG.infof("Latest failed run hit API quota. Waiting until :01 next hour before retry. run=%s", latest.id);
                    return Uni.createFrom().voidItem();
                }
                LOG.infof("Retrying failed collector run after tick: %s", latest.id);
                return this.collectorService.startBackgroundCollectAsync(true).invoke(run -> {
                    this.retriedFailedRunId.set(latest.id);
                    LOG.infof("Failed-run retry started: %s", run.id);
                }).replaceWithVoid();
            });
        });
    }

    private static boolean isQuotaFailureAndMustWaitNextHour(ProviderSyncRunEntity latest) {
        if (latest.failuresJson == null || !latest.failuresJson.contains("api_quota_exceeded")) {
            return false;
        }
        if (latest.finishedAt == null) {
            return true;
        }
        LocalDateTime nextCollectionSlot = latest.finishedAt.truncatedTo(ChronoUnit.HOURS).plusHours(1L).plusMinutes(1L);
        return LocalDateTime.now().isBefore(nextCollectionSlot);
    }
}

