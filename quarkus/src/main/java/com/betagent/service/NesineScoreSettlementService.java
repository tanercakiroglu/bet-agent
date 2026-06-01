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
import com.betagent.domain.MatchScore;
import com.betagent.persistence.entity.MatchScoreEntity;
import com.betagent.persistence.entity.ProviderEventEntity;
import com.betagent.persistence.repository.ProviderEventRepository;
import com.betagent.provider.EventStatus;
import com.betagent.provider.oddsapiio.OddsApiIoProvider;
import com.betagent.provider.nesine.NesineScoreParser;
import com.betagent.util.TeamNameMatcher;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    @Inject
    OddsApiIoProvider oddsApiProvider;
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

    public Uni<Map<String, Object>> reconcileFromOddsApi() {
        if (!this.config.enabled()) {
            return Uni.createFrom().item(Map.of("status", "disabled", "filled_missing", 0, "replaced_invalid", 0));
        }
        return this.refreshOddsApiSettledScores().chain(refreshedOddsApi -> this.warehouse.findMatchIdsMissingScores(CATALOG, 10000).chain(missing -> {
            HashSet<String> missingIds = new HashSet<>(missing);
            return this.warehouse.copyScoresFromOddsApi(CATALOG, missingIds).chain(filledMissing -> this.warehouse.findMatchIdsWithInvalidScores(CATALOG, 10000).chain(invalid -> this.warehouse.replaceInvalidScoresFromOddsApi(CATALOG, new HashSet<>(invalid)).chain(replacedInvalid -> this.warehouse.reconcileScoresFromOtherProviders(CATALOG, WarehouseService.ODDS_API_CATALOG, Set.of()).map(reconciledDivergent -> {
                int stillMissing = Math.max(0, missingIds.size() - filledMissing);
                int stillInvalid = Math.max(0, invalid.size() - replacedInvalid);
                return Map.of(
                        "status", "ok",
                        "source", WarehouseService.ODDS_API_CATALOG,
                        "refreshed_odds_api_settled", refreshedOddsApi,
                        "filled_missing", filledMissing,
                        "replaced_invalid", replacedInvalid,
                        "reconciled_divergent", reconciledDivergent,
                        "still_missing", stillMissing,
                        "still_invalid", stillInvalid);
            }))));
        }));
    }

    private Uni<Integer> refreshOddsApiSettledScores() {
        if (!this.oddsApiProvider.configured()) {
            return Uni.createFrom().item(0);
        }
        return this.oddsApiProvider.fetchEvents(EventStatus.SETTLED).chain(events -> Multi.createFrom().iterable(events)
                .onItem().transformToUniAndConcatenate(event -> {
                    Optional<EventParser.MatchBundle> bundle = this.eventParser.toSettledMatch(
                            event, WarehouseService.ODDS_API_CATALOG);
                    if (bundle.isEmpty()) {
                        return Uni.createFrom().item(0);
                    }
                    EventParser.MatchBundle matchBundle = bundle.get();
                    if (!NesineScoreParser.isValidScore(
                            matchBundle.score().hthg,
                            matchBundle.score().htag,
                            matchBundle.score().fthg,
                            matchBundle.score().ftag)) {
                        return Uni.createFrom().item(0);
                    }
                    return this.warehouse.upsertProviderEvent(
                                    this.eventParser.toProviderEvent(event, WarehouseService.ODDS_API_CATALOG))
                            .chain(() -> this.warehouse.upsertMatch(matchBundle))
                            .replaceWith(1);
                })
                .collect().asList()
                .map(parts -> parts.stream().mapToInt(Integer::intValue).sum()));
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
            List<JsonNode> settled = this.collectSettledEvents(feed, (List<ProviderEventEntity>) trackedEvents, trackedIds);
            return this.refreshOddsApiSettledScores().chain(ignored -> this.warehouse.findMatchIdsMissingScores(CATALOG, 10000).chain(missingBeforeList -> {
                HashSet<String> missingBefore = new HashSet<>(missingBeforeList);
                HashSet<String> initiallyMissing = new HashSet<>(missingBeforeList);
                AtomicInteger fromLiveScore = new AtomicInteger(0);
                AtomicInteger skippedUnverified = new AtomicInteger(0);
                return Multi.createFrom().iterable(settled)
                        .onItem().transformToUniAndConcatenate(event -> this.processSettledEvent(
                                event, missingBefore, fromLiveScore, skippedUnverified))
                        .collect().asList()
                        .chain(done -> {
                            if (skippedUnverified.get() > 0) {
                                LOG.infof(
                                        "Nesine HT/FT: %d mac Odds-API ile dogrulanamadi, skor yazilmadi (reconcile bekliyor)",
                                        skippedUnverified.get());
                            }
                            return this.completeSettlement(trackedIds, initiallyMissing, fromLiveScore, missingBefore);
                        });
            }));
        });
    }

    private Uni<SettlementResult> completeSettlement(
            Set<String> trackedIds,
            HashSet<String> initiallyMissing,
            AtomicInteger fromLiveScore,
            HashSet<String> missingBefore) {
        return this.warehouse.copyScoresFromOddsApi(CATALOG, missingBefore)
                .chain(fromOddsApiMissing -> this.warehouse.findMatchIdsWithInvalidScores(CATALOG, 10000)
                        .chain(invalidIds -> this.warehouse.replaceInvalidScoresFromOddsApi(CATALOG, new HashSet<>(invalidIds))
                                .chain(fromOddsApiInvalid -> this.warehouse.reconcileScoresFromOtherProviders(
                                        CATALOG, WarehouseService.ODDS_API_CATALOG, Set.of())
                                        .chain(fromOddsApiDivergent -> this.warehouse.findMatchIdsMissingScores(CATALOG, 10000)
                                                .chain(stillMissingList -> {
                                                    HashSet<String> stillMissing = new HashSet<>(stillMissingList);
                                                    List<String> newlySettled = initiallyMissing.stream()
                                                            .filter(id -> !stillMissing.contains(id))
                                                            .toList();
                                                    int fromOddsApi = fromOddsApiMissing + fromOddsApiInvalid + fromOddsApiDivergent;
                                                    return this.warehouse.bridgePendingOddsToSettled(CATALOG, newlySettled)
                                                            .map(bridgedOdds -> {
                                                                SettlementResult result = new SettlementResult(
                                                                        fromLiveScore.get(),
                                                                        fromOddsApi,
                                                                        bridgedOdds.intValue(),
                                                                        newlySettled.size(),
                                                                        trackedIds.size(),
                                                                        stillMissing.size());
                                                                if (fromLiveScore.get() > 0 || fromOddsApi > 0) {
                                                                    LOG.infof(
                                                                            "Nesine score sync: live=%d oddsApi=%d bridgedOdds=%d missing=%d tracked=%d",
                                                                            fromLiveScore.get(),
                                                                            fromOddsApi,
                                                                            bridgedOdds,
                                                                            stillMissing.size(),
                                                                            trackedIds.size());
                                                                }
                                                                return result;
                                                            });
                                                })))));
    }

    private Uni<Void> processSettledEvent(
            JsonNode event,
            Set<String> missingBefore,
            AtomicInteger fromLiveScore,
            AtomicInteger skippedUnverified) {
        String id = event.path("id").asText();
        Optional<EventParser.MatchBundle> bundle = this.eventParser.toSettledMatch(event, CATALOG);
        if (bundle.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        EventParser.MatchBundle matchBundle = bundle.get();
        MatchScoreEntity nesineScore = matchBundle.score();
        if (!NesineScoreParser.isValidScore(nesineScore.hthg, nesineScore.htag, nesineScore.fthg, nesineScore.ftag)) {
            LOG.warnf("Skipping invalid Nesine settled score match=%s HT=%d-%d FT=%d-%d",
                    id, nesineScore.hthg, nesineScore.htag, nesineScore.fthg, nesineScore.ftag);
            return Uni.createFrom().voidItem();
        }
        return this.warehouse.findScoreByTeamDate(
                        WarehouseService.ODDS_API_CATALOG,
                        matchBundle.match().homeTeam,
                        matchBundle.match().awayTeam,
                        matchBundle.match().matchDate)
                .chain(oddsApiScore -> {
                    if (oddsApiScore.isEmpty()) {
                        skippedUnverified.incrementAndGet();
                        LOG.debugf(
                                "Nesine HT/FT skipped (no Odds-API reference) match=%s %s vs %s HT %d-%d FT %d-%d",
                                id,
                                matchBundle.match().homeTeam,
                                matchBundle.match().awayTeam,
                                nesineScore.hthg,
                                nesineScore.htag,
                                nesineScore.fthg,
                                nesineScore.ftag);
                        return Uni.createFrom().voidItem();
                    }
                    MatchScoreEntity reference = oddsApiScore.get();
                    if (NesineScoreSettlementService.scoresEqual(nesineScore, reference)) {
                        LOG.debugf(
                                "Nesine HT/FT verified by Odds-API match=%s -> %s",
                                id,
                                reference.htftCode);
                    } else {
                        LOG.warnf(
                                "Nesine HT/FT mismatch match=%s Nesine HT %d-%d FT %d-%d (%s) vs Odds-API HT %d-%d FT %d-%d (%s) — writing Odds-API",
                                id,
                                nesineScore.hthg,
                                nesineScore.htag,
                                nesineScore.fthg,
                                nesineScore.ftag,
                                nesineScore.htftCode,
                                reference.hthg,
                                reference.htag,
                                reference.fthg,
                                reference.ftag,
                                reference.htftCode);
                        NesineScoreSettlementService.copyScoreFields(nesineScore, reference);
                    }
                    return this.warehouse.upsertProviderEvent(this.eventParser.toProviderEvent(event, CATALOG))
                            .chain(() -> this.warehouse.upsertMatch(matchBundle))
                            .invoke(() -> {
                                fromLiveScore.incrementAndGet();
                                missingBefore.remove(id);
                            });
                });
    }

    private static boolean scoresEqual(MatchScoreEntity left, MatchScoreEntity right) {
        return left.hthg == right.hthg
                && left.htag == right.htag
                && left.fthg == right.fthg
                && left.ftag == right.ftag;
    }

    private static void copyScoreFields(MatchScoreEntity target, MatchScoreEntity source) {
        target.hthg = source.hthg;
        target.htag = source.htag;
        target.fthg = source.fthg;
        target.ftag = source.ftag;
        MatchScore computed = new MatchScore(source.hthg, source.htag, source.fthg, source.ftag);
        target.htResult = computed.htResult();
        target.ftResult = computed.ftResult();
        target.htftCode = computed.htftCode();
        target.firstHalfKg = computed.firstHalfKg();
        target.firstHalfKgTarafCode = computed.firstHalfKgTarafCode();
    }

    private List<JsonNode> collectSettledEvents(JsonNode feed, List<ProviderEventEntity> trackedEvents, Set<String> trackedIds) {
        HashSet<String> matchedIds = new HashSet<>();
        ArrayList<JsonNode> settled = new ArrayList<>();
        JsonNode rows = feed.path("d");
        if (!rows.isArray()) {
            return settled;
        }
        for (JsonNode row : rows) {
            Optional<NesineScoreParser.ResolvedScore> score = NesineScoreParser.resolveFinishedRow(row);
            if (score.isEmpty()) {
                continue;
            }
            Optional<ProviderEventEntity> tracked = this.resolveTrackedEvent(row, trackedEvents, trackedIds, matchedIds);
            if (tracked.isEmpty()) {
                continue;
            }
            ProviderEventEntity event = tracked.get();
            settled.add(this.toSettledEventNode(event, row, score.get()));
            matchedIds.add(event.providerMatchId);
        }
        return settled;
    }

    private Optional<ProviderEventEntity> resolveTrackedEvent(
            JsonNode row,
            List<ProviderEventEntity> trackedEvents,
            Set<String> trackedIds,
            Set<String> alreadyMatched) {
        String feedEventId = NesineScoreParser.feedEventId(row);
        String feedNid = NesineScoreParser.feedId(row);
        for (String candidateId : List.of(feedEventId, feedNid)) {
            if (candidateId == null || candidateId.isBlank() || "0".equals(candidateId)) {
                continue;
            }
            if (!trackedIds.contains(candidateId) || alreadyMatched.contains(candidateId)) {
                continue;
            }
            for (ProviderEventEntity event : trackedEvents) {
                if (candidateId.equals(event.providerMatchId)) {
                    return Optional.of(event);
                }
            }
        }
        LocalDate matchDate = NesineScoreSettlementService.parseFeedDate(row);
        if (matchDate == null) {
            return Optional.empty();
        }
        String feedHome = NesineScoreSettlementService.text(row, "HTTR", "HT");
        String feedAway = NesineScoreSettlementService.text(row, "ATTR", "AT");
        ProviderEventEntity best = null;
        for (ProviderEventEntity event : trackedEvents) {
            if (alreadyMatched.contains(event.providerMatchId) || event.eventDate == null) {
                continue;
            }
            if (!matchDate.equals(event.eventDate.toLocalDate())) {
                continue;
            }
            if (!NesineScoreParser.teamsMatch(event.homeTeam, event.awayTeam, feedHome, feedAway)) {
                continue;
            }
            if (best != null) {
                LOG.warnf("Ambiguous Nesine team match for feed=%s (%s vs %s); skipping", NesineScoreParser.feedId(row), feedHome, feedAway);
                return Optional.empty();
            }
            best = event;
        }
        return Optional.ofNullable(best);
    }

    private JsonNode toSettledEventNode(ProviderEventEntity tracked, JsonNode row, NesineScoreParser.ResolvedScore score) {
        ObjectNode node = this.mapper.createObjectNode();
        node.put("id", tracked.providerMatchId);
        node.put("home", tracked.homeTeam);
        node.put("away", tracked.awayTeam);
        node.put("status", "finished");
        LocalDate matchDate = NesineScoreSettlementService.parseFeedDate(row);
        node.put("date", matchDate != null ? matchDate.toString() : tracked.eventDate.toLocalDate().toString());
        ObjectNode league = this.mapper.createObjectNode();
        league.put("name", tracked.leagueName != null ? tracked.leagueName : "Football");
        league.put("slug", tracked.leagueSlug != null ? tracked.leagueSlug : "football");
        node.set("league", (JsonNode) league);
        ObjectNode sport = this.mapper.createObjectNode();
        sport.put("slug", "football");
        node.set("sport", (JsonNode) sport);
        ObjectNode scores = this.mapper.createObjectNode();
        ObjectNode halftime = this.mapper.createObjectNode();
        halftime.put("home", score.hthg());
        halftime.put("away", score.htag());
        ObjectNode fulltime = this.mapper.createObjectNode();
        fulltime.put("home", score.fthg());
        fulltime.put("away", score.ftag());
        scores.set("halftime", (JsonNode) halftime);
        scores.set("fulltime", (JsonNode) fulltime);
        node.set("scores", (JsonNode) scores);
        return node;
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

    static String normalizeTeam(String value) {
        return TeamNameMatcher.normalize(value);
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

