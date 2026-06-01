package com.betagent.scheduler;

import com.betagent.config.NesineConfig;
import com.betagent.persistence.ReactiveContextRunner;
import com.betagent.service.NesineScoreSettlementService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NesineScoreRepairJob {
    private static final Logger LOG = Logger.getLogger(NesineScoreRepairJob.class);

    @Inject
    NesineConfig config;
    @Inject
    NesineScoreSettlementService settlementService;
    @Inject
    ReactiveContextRunner contextRunner;

    @Scheduled(cron = "{betagent.nesine.score-repair-cron}", concurrentExecution = ConcurrentExecution.SKIP)
    void run() {
        if (!this.config.enabled() || !this.config.scoreRepairJobEnabled()) {
            return;
        }
        this.contextRunner.subscribe(() -> this.settlementService
                .repairScoresFromLiveFeed("scheduled")
                .invoke(result -> {
                    if ("skipped".equals(result.get("status"))) {
                        return;
                    }
                    int repaired = ((Number) result.getOrDefault("repaired", 0)).intValue();
                    int corrections = ((Number) result.getOrDefault("corrections_count", 0)).intValue();
                    if (repaired > 0 || corrections > 0) {
                        LOG.infof(
                                "Nesine score repair job: repaired=%d corrections=%d still_missing=%s",
                                repaired,
                                corrections,
                                result.get("still_missing"));
                    }
                }));
    }
}
