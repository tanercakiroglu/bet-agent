/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.quarkus.runtime.StartupEvent
 *  io.quarkus.scheduler.Scheduled
 *  io.quarkus.scheduler.Scheduled$ConcurrentExecution
 *  io.vertx.core.Vertx
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.enterprise.event.Observes
 *  jakarta.inject.Inject
 *  org.jboss.logging.Logger
 */
package com.betagent.scheduler;

import com.betagent.config.NesineConfig;
import com.betagent.persistence.ReactiveContextRunner;
import com.betagent.service.NesineScoreSettlementService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NesineScoreJob {
    private static final Logger LOG = Logger.getLogger(NesineScoreJob.class);
    @Inject
    NesineConfig config;
    @Inject
    NesineScoreSettlementService settlementService;
    @Inject
    ReactiveContextRunner contextRunner;
    @Inject
    Vertx vertx;

    void onStartup(@Observes StartupEvent ignored) {
        if (!(this.config.enabled() && this.config.scoreJobEnabled() && this.config.scoreBackfillOnStartup())) {
            return;
        }
        this.vertx.setTimer(2000L, id -> this.contextRunner.subscribe(() -> this.settlementService.syncScores(true, "startup").invoke(result -> LOG.infof("Startup Nesine score backfill done: live=%d cross=%d stillMissing=%d", result.fromLiveScore(), result.fromCrossProvider(), result.stillMissing()))));
    }

    @Scheduled(every="{betagent.nesine.score-job-interval}", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void run() {
        if (!this.config.enabled() || !this.config.scoreJobEnabled()) {
            return;
        }
        this.contextRunner.subscribe(() -> this.settlementService.syncScores(true, "scheduled").invoke(result -> {
            if (result.fromLiveScore() > 0 || result.fromCrossProvider() > 0) {
                LOG.infof("Nesine score job: live=%d cross=%d bridged=%d missing=%d", new Object[]{result.fromLiveScore(), result.fromCrossProvider(), result.bridgedOdds(), result.stillMissing()});
            }
        }));
    }
}

