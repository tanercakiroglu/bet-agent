/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.persistence.Column
 *  jakarta.persistence.Entity
 *  jakarta.persistence.Id
 *  jakarta.persistence.Table
 */
package com.betagent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name="provider_sync_runs")
public class ProviderSyncRunEntity {
    @Id
    public UUID id;
    @Column(nullable=false, length=64)
    public String provider;
    @Column(nullable=false, length=32)
    public String status;
    @Column(name="started_at", nullable=false)
    public LocalDateTime startedAt;
    @Column(name="finished_at")
    public LocalDateTime finishedAt;
    @Column(name="request_budget", nullable=false)
    public int requestBudget;
    @Column(name="request_count", nullable=false)
    public int requestCount;
    @Column(name="events_seen", nullable=false)
    public int eventsSeen;
    @Column(name="settled_events", nullable=false)
    public int settledEvents;
    @Column(name="pending_events", nullable=false)
    public int pendingEvents;
    @Column(name="odds_payloads", nullable=false)
    public int oddsPayloads;
    @Column(name="odds_snapshots_inserted", nullable=false)
    public int oddsSnapshotsInserted;
    @Column(name="matches_inserted", nullable=false)
    public int matchesInserted;
    @Column(name="future_fixtures", nullable=false)
    public int futureFixtures;
    @Column(name="failures_json", columnDefinition="text")
    public String failuresJson;
    @Column(name="league_stats_json", columnDefinition="text")
    public String leagueStatsJson;
    @Column(name="optimization_stats_json", columnDefinition="text")
    public String optimizationStatsJson;
}

