/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  com.fasterxml.jackson.databind.node.ObjectNode
 *  io.quarkus.hibernate.reactive.panache.common.WithSession
 *  io.quarkus.hibernate.reactive.panache.common.WithTransaction
 *  io.smallrye.mutiny.Multi
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 *  org.jboss.logging.Logger
 */
package com.betagent.service;

import com.betagent.config.NesineConfig;
import com.betagent.persistence.entity.ProviderEventEntity;
import com.betagent.persistence.repository.ProviderEventRepository;
import com.betagent.provider.nesine.NesineAdapter;
import com.betagent.provider.nesine.NesineLiveScoreService;
import com.betagent.service.EventParser;
import com.betagent.service.ScoreJobRunService;
import com.betagent.service.WarehouseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
@WithSession
public class NesineScoreSettlementService {
    private static final Logger LOG = Logger.getLogger(NesineScoreSettlementService.class);
    private static final String CATALOG = "Nesine";
    @Inject
    NesineConfig config;
    @Inject
    NesineLiveScoreService liveScoreService;
    @Inject
    NesineAdapter adapter;
    @Inject
    EventParser eventParser;
    @Inject
    WarehouseService warehouse;
    @Inject
    ProviderEventRepository providerEventRepository;
    @Inject
    ObjectMapper mapper;
    @Inject
    NesineScoreSettlementService self;
    @Inject
    ScoreJobRunService scoreJobRunService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean isRunning() {
        return this.running.get();
    }

    public Uni<SettlementResult> syncScores(boolean invalidateCache) {
        return this.syncScores(invalidateCache, "manual");
    }

    public Uni<SettlementResult> syncScores(boolean invalidateCache, String trigger) {
        if (!this.config.enabled() || !this.config.scoreJobEnabled()) {
            return Uni.createFrom().item(SettlementResult.disabled());
        }
        if (!this.running.compareAndSet(false, true)) {
            return Uni.createFrom().item(SettlementResult.skipped("already_running"));
        }
        if (invalidateCache) {
            this.liveScoreService.invalidateCache();
        }
        return this.scoreJobRunService.start().chain(run -> this.liveScoreService.liveScoreFeed(true).chain(feed -> this.self.settleFromFeed((JsonNode)feed)).chain(result -> this.scoreJobRunService.finish(run.id, (SettlementResult)result, trigger).replaceWith(result)).onFailure().call(ex -> this.scoreJobRunService.fail(run.id, ex.getMessage()))).eventually(() -> {
            this.running.set(false);
            return Uni.createFrom().voidItem();
        });
    }

    public Uni<SettlementResult> syncScoresBlocking(boolean invalidateCache, String trigger) {
        return this.syncScores(invalidateCache, trigger);
    }

    @WithSession
    @WithTransaction
    public Uni<SettlementResult> settleFromFeed(JsonNode feed) {
        return this.applySettlement(feed);
    }

    private Uni<SettlementResult> applySettlement(JsonNode feed) {
        return this.providerEventRepository.list("provider", new Object[]{CATALOG}).chain(trackedEvents -> {
            Set<String> trackedIds = trackedEvents.stream().map(event -> event.providerMatchId).collect(Collectors.toSet());
            if (trackedIds.isEmpty()) {
                return Uni.createFrom().item(SettlementResult.empty());
            }
            ArrayList<JsonNode> settled = new ArrayList<JsonNode>(this.adapter.toSettledEvents(feed).stream().filter(event -> trackedIds.contains(event.path("id").asText())).toList());
            settled.addAll(this.matchSettledByTeamName(feed, trackedIds, (List<JsonNode>)settled, (List<ProviderEventEntity>)trackedEvents));
            return this.warehouse.findMatchIdsMissingScores(CATALOG, 10000).chain(missingBeforeList -> {
                HashSet<String> missingBefore = new HashSet<>(missingBeforeList);
                AtomicInteger fromLiveScore = new AtomicInteger(0);
                return Multi.createFrom().iterable(settled).onItem().transformToUniAndConcatenate(event -> this.processSettledEvent(event, missingBefore, fromLiveScore)).collect().asList().chain(ignored -> this.warehouse.copyScoresFromOtherProviders(CATALOG, missingBefore)).chain((Integer fromCrossProvider) -> this.warehouse.findMatchIdsMissingScores(CATALOG, 10000).chain(stillMissingList -> {
                    HashSet<String> stillMissing = new HashSet<>(stillMissingList);
                    List<String> newlySettled = missingBefore.stream().filter(id -> !stillMissing.contains(id)).toList();
                    return this.warehouse.bridgePendingOddsToSettled(CATALOG, newlySettled).map(bridgedOdds -> {
                        SettlementResult result = new SettlementResult(fromLiveScore.get(), (int)fromCrossProvider, (int)bridgedOdds, newlySettled.size(), trackedIds.size(), stillMissing.size());
                        if (fromLiveScore.get() > 0 || fromCrossProvider > 0) {
                            LOG.infof("Nesine score sync: live=%d cross=%d bridgedOdds=%d missing=%d tracked=%d", new Object[]{fromLiveScore.get(), fromCrossProvider, bridgedOdds, stillMissing.size(), trackedIds.size()});
                        }
                        return result;
                    });
                }));
            });
        });
    }

    private Uni<Void> processSettledEvent(JsonNode event, Set<String> missingBefore, AtomicInteger fromLiveScore) {
        String id = event.path("id").asText();
        if (!missingBefore.contains(id)) {
            return Uni.createFrom().voidItem();
        }
        Optional<EventParser.MatchBundle> bundle = this.eventParser.toSettledMatch(event, CATALOG);
        if (bundle.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return this.warehouse.upsertProviderEvent(this.eventParser.toProviderEvent(event, CATALOG)).chain(() -> this.warehouse.upsertMatch((EventParser.MatchBundle)bundle.get())).invoke(() -> {
            fromLiveScore.incrementAndGet();
            missingBefore.remove(id);
        });
    }

    private List<JsonNode> matchSettledByTeamName(JsonNode feed, Set<String> trackedIds, List<JsonNode> already, List<ProviderEventEntity> trackedEvents) {
        HashSet<String> alreadyIds = new HashSet<String>();
        for (JsonNode jsonNode : already) {
            alreadyIds.add(jsonNode.path("id").asText());
        }
        HashMap<String, ProviderEventEntity> byTeamKey = new HashMap<String, ProviderEventEntity>();
        for (ProviderEventEntity event : trackedEvents) {
            if (!trackedIds.contains(event.providerMatchId) || alreadyIds.contains(event.providerMatchId) || event.eventDate == null) continue;
            byTeamKey.put(NesineScoreSettlementService.teamKey(event.homeTeam, event.awayTeam, event.eventDate.toLocalDate()), event);
        }
        if (byTeamKey.isEmpty()) {
            return List.of();
        }
        ArrayList<JsonNode> arrayList = new ArrayList<JsonNode>();
        JsonNode rows = feed.path("d");
        if (!rows.isArray()) {
            return arrayList;
        }
        for (JsonNode row : rows) {
            String away;
            LocalDate matchDate;
            String feedId;
            if (row.path("S").asInt(-1) != 4 || alreadyIds.contains(feedId = String.valueOf(row.path("C").asLong())) || (matchDate = NesineScoreSettlementService.parseFeedDate(row)) == null) continue;
            String home = NesineScoreSettlementService.text(row, "HTTR", "HT");
            ProviderEventEntity tracked = (ProviderEventEntity)byTeamKey.get(NesineScoreSettlementService.teamKey(home, away = NesineScoreSettlementService.text(row, "ATTR", "AT"), matchDate));
            if (tracked == null) continue;
            Optional<int[]> ht = NesineScoreSettlementService.scoreFromEs(row, 19);
            Optional<int[]> sh = NesineScoreSettlementService.scoreFromEs(row, 2);
            if (ht.isEmpty() || sh.isEmpty()) continue;
            int[] htGoals = ht.get();
            int[] shGoals = sh.get();
            ObjectNode node = this.mapper.createObjectNode();
            node.put("id", tracked.providerMatchId);
            node.put("home", tracked.homeTeam);
            node.put("away", tracked.awayTeam);
            node.put("status", "finished");
            node.put("date", matchDate.toString());
            ObjectNode league = this.mapper.createObjectNode();
            league.put("name", tracked.leagueName != null ? tracked.leagueName : "Football");
            league.put("slug", tracked.leagueSlug != null ? tracked.leagueSlug : "football");
            node.set("league", (JsonNode)league);
            ObjectNode sport = this.mapper.createObjectNode();
            sport.put("slug", "football");
            node.set("sport", (JsonNode)sport);
            ObjectNode scores = this.mapper.createObjectNode();
            ObjectNode halftime = this.mapper.createObjectNode();
            halftime.put("home", htGoals[0]);
            halftime.put("away", htGoals[1]);
            ObjectNode fulltime = this.mapper.createObjectNode();
            fulltime.put("home", htGoals[0] + shGoals[0]);
            fulltime.put("away", htGoals[1] + shGoals[1]);
            scores.set("halftime", (JsonNode)halftime);
            scores.set("fulltime", (JsonNode)fulltime);
            node.set("scores", (JsonNode)scores);
            arrayList.add((JsonNode)node);
            alreadyIds.add(tracked.providerMatchId);
        }
        return arrayList;
    }

    private static Optional<int[]> scoreFromEs(JsonNode row, int periodType) {
        for (JsonNode period : row.path("ES")) {
            if (period.path("T").asInt(-1) != periodType) continue;
            return Optional.of(new int[]{period.path("H").asInt(), period.path("A").asInt()});
        }
        return Optional.empty();
    }

    private static LocalDate parseFeedDate(JsonNode row) {
        String s;
        String md = row.path("MD").asText("");
        if (md.length() >= 10) {
            return LocalDate.parse(md.substring(0, 10));
        }
        long day = row.path("D").asLong(0L);
        if (day > 0L && (s = String.valueOf(day)).length() == 8) {
            return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
        }
        return null;
    }

    private static String teamKey(String home, String away, LocalDate date) {
        return NesineScoreSettlementService.normalizeTeam(home) + "|" + NesineScoreSettlementService.normalizeTeam(away) + "|" + String.valueOf(date);
    }

    static String normalizeTeam(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('\u0131', 'i').replace('\u0130', 'i').replace('\u015f', 's').replace('\u015e', 's').replace('\u011f', 'g').replace('\u011e', 'g').replace('\u00fc', 'u').replace('\u00dc', 'u').replace('\u00f6', 'o').replace('\u00d6', 'o').replace('\u00e7', 'c').replace('\u00c7', 'c');
        return normalized.replaceAll("[^a-z0-9]+", "").trim();
    }

    private static String text(JsonNode node, String ... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || value.asText("").isBlank()) continue;
            return value.asText();
        }
        return "";
    }

    public record SettlementResult(int fromLiveScore, int fromCrossProvider, int bridgedOdds, int settledThisRun, int trackedTotal, int stillMissing, String status) {
        SettlementResult(int fromLiveScore, int fromCrossProvider, int bridgedOdds, int settledThisRun, int trackedTotal, int stillMissing) {
            this(fromLiveScore, fromCrossProvider, bridgedOdds, settledThisRun, trackedTotal, stillMissing, "ok");
        }

        static SettlementResult disabled() {
            return new SettlementResult(0, 0, 0, 0, 0, 0, "disabled");
        }

        static SettlementResult skipped(String reason) {
            return new SettlementResult(0, 0, 0, 0, 0, 0, reason);
        }

        static SettlementResult empty() {
            return new SettlementResult(0, 0, 0, 0, 0, 0, "ok");
        }

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("status", this.status);
            map.put("from_live_score", this.fromLiveScore);
            map.put("from_cross_provider", this.fromCrossProvider);
            map.put("bridged_odds", this.bridgedOdds);
            map.put("settled_this_run", this.settledThisRun);
            map.put("tracked_total", this.trackedTotal);
            map.put("still_missing", this.stillMissing);
            return map;
        }
    }
}

