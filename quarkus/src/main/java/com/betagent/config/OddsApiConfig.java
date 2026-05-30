/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.smallrye.config.ConfigMapping
 *  io.smallrye.config.WithDefault
 */
package com.betagent.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix="betagent.odds-api")
public interface OddsApiConfig {
    @WithDefault(value="https://api.odds-api.io/v3")
    public String baseUrl();

    Optional<String> apiKey();

    Optional<String> apiKeySecondary();

    Optional<String> apiKeyTertiary();

    @WithDefault(value="football")
    public String sport();

    @WithDefault(value="Bet365")
    public String bookmakers();

    @WithDefault(value="100")
    public int requestBudget();

    @WithDefault(value="900")
    public int eventLimit();

    @WithDefault(value="true")
    public boolean collectionEnabled();

    @WithDefault(value="1h")
    public String collectionInterval();

    @WithDefault(value="15m")
    public String collectionTickInterval();

    @WithDefault(value="0 1 * ? * *")
    public String collectionCron();

    @WithDefault(value="true")
    public boolean collectionRunOnStartup();

    @WithDefault(value="2")
    public int reservedEventRequests();

    @WithDefault(value="95")
    public int maxOddsRequestsPerRun();

    @WithDefault(value="10")
    public int oddsChunkSize();

    @WithDefault(value="pending_only")
    public String oddsFetchMode();

    @WithDefault(value="1")
    public int skipOddsSnapshotWithinHours();

    @WithDefault(value="50")
    public int minMinutesBetweenRuns();

    @WithDefault(value="20")
    public int staleRunMinutes();

    @WithDefault(value="2")
    public int oddsRetryAttemptsOn429();

    @WithDefault(value="30")
    public int oddsRetryDelaySecondsOn429();
}

