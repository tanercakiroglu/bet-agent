package com.betagent.service;

import com.betagent.config.PredictionConfig;
import com.betagent.domain.LeagueCatalog;
import com.betagent.domain.Markets;
import com.betagent.domain.ScoreMath;
import com.betagent.persistence.entity.ProviderEventEntity;
import com.betagent.persistence.repository.ProviderEventRepository;
import com.betagent.provider.OddsDataProvider;
import com.betagent.provider.OddsProviderRegistry;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.reactive.mutiny.Mutiny;

@ApplicationScoped
@WithSession
public class SharpPredictionService {

    @Inject PredictionConfig config;
    @Inject PredictionSettingsService predictionSettingsService;
    @Inject OddsProviderRegistry providerRegistry;
    @Inject ProviderEventRepository providerEventRepository;

    public Uni<List<Map<String, Object>>> predict(int limit) {
        int perProvider = Math.max(5, limit);
        return Multi.createFrom().iterable(providerRegistry.configuredProviders())
                .onItem()
                .transformToUniAndConcatenate(provider -> predictForCatalog(provider, perProvider).map(rows -> {
                    for (Map<String, Object> row : rows) {
                        row.put("odds_provider", provider.catalogName());
                        row.put("odds_provider_id", provider.id());
                    }
                    return rows;
                }))
                .collect()
                .asList()
                .map(providerRows -> {
                    List<Map<String, Object>> all = new ArrayList<>();
                    for (List<Map<String, Object>> rows : providerRows) {
                        List<Map<String, Object>> sorted = new ArrayList<>(rows);
                        sorted.sort((a, b) -> Double.compare((double) b.get("decimal_odds"), (double) a.get("decimal_odds")));
                        all.addAll(sorted.stream().limit(perProvider).toList());
                    }
                    return all;
                });
    }

    public Uni<Map<String, Object>> diagnosticsForCatalog(String catalogName) {
        boolean nesineOnlyHtft = LeagueCatalog.NESINE_CATALOG.equals(catalogName);
        boolean oddsApiKgOnly = LeagueCatalog.ODDS_API_IO_CATALOG.equals(catalogName);
        return predictionSettingsService.resolve(catalogName).chain(thresholds -> loadEventsById(catalogName)
                .chain(eventsById -> loadPendingFixtures(catalogName, eventsById).chain(fixturesRaw -> loadHistoricalSamples(
                                catalogName,
                                nesineOnlyHtft,
                                oddsApiKgOnly)
                        .map(samples -> {
                            Map<String, BandStats> global = statsByBand(samples, s -> true);
                            Map<String, Map<String, BandStats>> byLeague = new HashMap<>();
                            for (HistoricalSample sample : samples) {
                                byLeague.computeIfAbsent(sample.competitionCode(), ignored -> new HashMap<>());
                                String key = bandKey(sample.market(), sample.outcome(), sample.band());
                                byLeague.get(sample.competitionCode())
                                        .compute(key, (k, v) -> v == null ? BandStats.from(sample) : v.add(sample));
                            }
                            int fixturesInCatalog = 0;
                            int fixturesWithCandidateOdds = 0;
                            int marketOutcomesChecked = 0;
                            int rejectedBySample = 0;
                            int rejectedByEdgeOrWilson = 0;
                            int passed = 0;
                            for (PendingFixture fixture : fixturesRaw) {
                                if (!(nesineOnlyHtft || inCatalog(fixture, eventsById))) {
                                    continue;
                                }
                                fixturesInCatalog++;
                                if (!hasCandidateOdds(fixture, nesineOnlyHtft, oddsApiKgOnly)) {
                                    continue;
                                }
                                fixturesWithCandidateOdds++;
                                for (Map.Entry<String, Double> marketOutcome : fixture.markets().entrySet()) {
                                    String[] parts = marketOutcome.getKey().split("::");
                                    if (parts.length != 2) {
                                        continue;
                                    }
                                    String market = parts[0];
                                    String outcome = parts[1];
                                    if (nesineOnlyHtft && !Markets.HTFT.equals(market)) {
                                        continue;
                                    }
                                    if (oddsApiKgOnly
                                            && !Markets.FIRST_HALF_KG_TARAF.equals(market)
                                            && !(Markets.FIRST_HALF_BTTS.equals(market) && "VAR".equals(outcome))) {
                                        continue;
                                    }
                                    if (!Markets.CANDIDATE_OUTCOMES.getOrDefault(market, Set.of()).contains(outcome)) {
                                        continue;
                                    }
                                    double odds = marketOutcome.getValue();
                                    if (odds <= 1.0) {
                                        continue;
                                    }
                                    marketOutcomesChecked++;
                                    String band = ScoreMath.oddsBand(odds);
                                    String key = bandKey(market, outcome, band);
                                    BandStats stats = byLeague
                                            .getOrDefault(fixture.competitionCode(), Map.of())
                                            .get(key);
                                    if (stats == null || stats.total() < thresholds.minSamples()) {
                                        stats = global.get(key);
                                    }
                                    if (stats == null || stats.total() < thresholds.minSamples()) {
                                        rejectedBySample++;
                                        continue;
                                    }
                                    double hitRate = stats.hitRate();
                                    int sampleCount = stats.total();
                                    int hitCount = stats.hits();
                                    double implied = 1 / odds;
                                    double edge = hitRate - implied;
                                    double confidenceLow = ScoreMath.wilsonLow(hitCount, sampleCount, 1.96);
                                    double wilsonFloor = ScoreMath.wilsonMinThreshold(
                                            market, implied, thresholds.minConfidenceLow(), thresholds.wilsonScaleByImplied());
                                    double minEdge = Markets.FIRST_HALF_BTTS.equals(market)
                                            ? Math.min(thresholds.minEdge(), 0.05)
                                            : thresholds.minEdge();
                                    if (edge < minEdge || confidenceLow < wilsonFloor) {
                                        rejectedByEdgeOrWilson++;
                                        continue;
                                    }
                                    passed++;
                                }
                            }
                            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
                            payload.put("provider", catalogName);
                            payload.put("fixtures_total", fixturesRaw.size());
                            payload.put("fixtures_in_catalog", fixturesInCatalog);
                            payload.put("fixtures_with_candidate_odds", fixturesWithCandidateOdds);
                            payload.put("historical_samples", samples.size());
                            payload.put("market_outcomes_checked", marketOutcomesChecked);
                            payload.put("rejected_by_sample", rejectedBySample);
                            payload.put("rejected_by_edge_or_wilson", rejectedByEdgeOrWilson);
                            payload.put("passed", passed);
                            payload.put("min_samples", thresholds.minSamples());
                            payload.put("min_edge", thresholds.minEdge());
                            payload.put("min_confidence_low", thresholds.minConfidenceLow());
                            payload.put("wilson_scale_by_implied", thresholds.wilsonScaleByImplied());
                            return payload;
                        }))));
    }

    private Uni<List<Map<String, Object>>> predictForCatalog(OddsDataProvider provider, int limit) {
        String catalogName = provider.catalogName();
        boolean nesineOnlyHtft = LeagueCatalog.NESINE_CATALOG.equals(catalogName);
        boolean oddsApiKgOnly = LeagueCatalog.ODDS_API_IO_CATALOG.equals(catalogName);
        return predictionSettingsService.resolve(catalogName).chain(thresholds -> loadEventsById(catalogName)
                .chain(eventsById -> loadPendingFixtures(catalogName, eventsById).chain(fixturesRaw -> {
                    List<PendingFixture> fixtures = fixturesRaw.stream()
                            .filter(f -> nesineOnlyHtft || inCatalog(f, eventsById))
                            .filter(f -> hasCandidateOdds(f, nesineOnlyHtft, oddsApiKgOnly))
                            .toList();
                    if (fixtures.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    return loadHistoricalSamples(catalogName, nesineOnlyHtft, oddsApiKgOnly).map(samples -> {
                        Map<String, BandStats> global = statsByBand(samples, s -> true);
                        Map<String, Map<String, BandStats>> byLeague = new HashMap<>();
                        for (HistoricalSample sample : samples) {
                            byLeague.computeIfAbsent(sample.competitionCode(), ignored -> new HashMap<>());
                            String key = bandKey(sample.market(), sample.outcome(), sample.band());
                            byLeague.get(sample.competitionCode())
                                    .compute(key, (k, v) -> v == null ? BandStats.from(sample) : v.add(sample));
                        }

                        List<Map<String, Object>> candidates = new ArrayList<>();
                        for (PendingFixture fixture : fixtures) {
                            for (Map.Entry<String, Double> marketOutcome : fixture.markets().entrySet()) {
                                String[] parts = marketOutcome.getKey().split("::");
                                if (parts.length != 2) {
                                    continue;
                                }
                                String market = parts[0];
                                String outcome = parts[1];
                                if (nesineOnlyHtft && !Markets.HTFT.equals(market)) {
                                    continue;
                                }
                                if (oddsApiKgOnly
                                        && !Markets.FIRST_HALF_KG_TARAF.equals(market)
                                        && !(Markets.FIRST_HALF_BTTS.equals(market) && "VAR".equals(outcome))) {
                                    continue;
                                }
                                if (!Markets.CANDIDATE_OUTCOMES.getOrDefault(market, Set.of()).contains(outcome)) {
                                    continue;
                                }
                                double odds = marketOutcome.getValue();
                                if (odds <= 1.0) {
                                    continue;
                                }
                                Map<String, Object> row = buildCandidateRow(
                                        fixture, market, outcome, odds, global, byLeague, thresholds);
                                if (row != null) {
                                    candidates.add(row);
                                }
                            }
                        }
                        candidates.sort((a, b) -> Double.compare((double) b.get("decimal_odds"), (double) a.get("decimal_odds")));
                        return candidates.stream().limit(limit).toList();
                    });
                })));
    }

    private Map<String, Object> buildCandidateRow(
            PendingFixture fixture,
            String market,
            String outcome,
            double odds,
            Map<String, BandStats> global,
            Map<String, Map<String, BandStats>> byLeague,
            PredictionSettingsService.PredictionThresholds thresholds) {
        String band = ScoreMath.oddsBand(odds);
        String key = bandKey(market, outcome, band);
        BandStats stats = byLeague.getOrDefault(fixture.competitionCode(), Map.of()).get(key);
        String scope = fixture.competitionCode();
        if (stats == null || stats.total() < thresholds.minSamples()) {
            stats = global.get(key);
            scope = "Tum ligler";
        }
        if (stats == null || stats.total() < thresholds.minSamples()) {
            return null;
        }
        double hitRate = stats.hitRate();
        int sampleCount = stats.total();
        int hitCount = stats.hits();
        double implied = 1 / odds;
        double edge = hitRate - implied;
        double confidenceLow = ScoreMath.wilsonLow(hitCount, sampleCount, 1.96);
        double wilsonFloor = ScoreMath.wilsonMinThreshold(
                market, implied, thresholds.minConfidenceLow(), thresholds.wilsonScaleByImplied());
        double minEdge = Markets.FIRST_HALF_BTTS.equals(market)
                ? Math.min(thresholds.minEdge(), 0.05)
                : thresholds.minEdge();
        if (edge < minEdge || confidenceLow < wilsonFloor) {
            return null;
        }
        double roi = hitRate * odds - 1;

        double score = edge * 120
                + Math.max(roi, 0) * 15
                + confidenceLow * 40
                + Math.min(sampleCount, 40) * 0.5
                + odds * 0.01;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider_match_id", fixture.eventId());
        row.put("competition_code", fixture.competitionCode());
        row.put("match_date", fixture.matchDate());
        row.put("home_team", fixture.homeTeam());
        row.put("away_team", fixture.awayTeam());
        row.put("bookmaker", fixture.bookmaker());
        row.put("market", market);
        row.put("outcome", outcome);
        row.put("decimal_odds", odds);
        row.put("odds_band", band);
        row.put("sample_count", sampleCount);
        row.put("hit_count", hitCount);
        row.put("hit_rate", hitRate);
        row.put("historical_roi", roi);
        row.put("implied_probability", implied);
        row.put("edge", edge);
        row.put("confidence_low", confidenceLow);
        row.put("confidence_tier", confidenceTier(confidenceLow, sampleCount));
        row.put("score", score);
        row.put("scope", scope);
        row.put("play_pick", isPlayPick(edge, roi, sampleCount, thresholds));
        row.put(
                "rationale",
                scope
                        + " | band "
                        + band
                        + " | n="
                        + sampleCount
                        + " hit="
                        + String.format("%.1f%%", hitRate * 100)
                        + " implied="
                        + String.format("%.1f%%", implied * 100)
                        + " WilsonLow="
                        + String.format("%.1f%%", confidenceLow * 100));
        return row;
    }

    private static String rowKey(PendingFixture fixture, String market, String outcome) {
        return fixture.eventId() + "::" + fixture.bookmaker() + "::" + market + "::" + outcome;
    }

    private Uni<List<HistoricalSample>> loadHistoricalSamples(String catalogName, boolean nesineOnlyHtft, boolean oddsApiKgOnly) {
        String marketClause;
        if (nesineOnlyHtft) {
            marketClause = " and o.market = 'HTFT'";
        } else if (oddsApiKgOnly) {
            marketClause = " and o.market in ('FIRST_HALF_KG_TARAF', 'FIRST_HALF_1X2', 'FIRST_HALF_BTTS')";
        } else {
            marketClause = " and o.market in ('HTFT', 'FIRST_HALF_KG_TARAF', 'FIRST_HALF_1X2', 'FIRST_HALF_BTTS')";
        }
        return Panache.getSession().flatMap(session -> {
            @SuppressWarnings("unchecked")
            Mutiny.Query<Object[]> query = (Mutiny.Query<Object[]>) (Mutiny.Query<?>) session.createNativeQuery(
                    """
                    with scored as (
                        select s.provider_match_id, s.ht_result, s.htft_code, s.first_half_kg,
                               s.first_half_kg_taraf_code,
                               coalesce(m.competition_code, pe.league_name, '') as competition_code,
                               coalesce(pe.league_name, m.competition_code, '') as league_name,
                               coalesce(pe.league_slug, '') as league_slug
                        from match_scores s
                        left join matches m on m.provider = s.provider and m.provider_match_id = s.provider_match_id
                        left join provider_events pe on pe.provider = s.provider and pe.provider_match_id = s.provider_match_id
                        where s.provider = :catalog
                    ),
                    latest as (
                        select distinct on (o.provider_match_id, o.bookmaker, o.market, o.outcome)
                               s.competition_code, s.league_name, s.league_slug,
                               s.first_half_kg_taraf_code,
                               o.provider_match_id, o.bookmaker, o.market, o.outcome, o.decimal_odds,
                               case
                                   when o.market = 'FIRST_HALF_1X2' then s.ht_result = o.outcome
                                   when o.market = 'HTFT' then s.htft_code = o.outcome
                                   when o.market = 'FIRST_HALF_KG_TARAF' then s.first_half_kg_taraf_code = o.outcome
                                   when o.market = 'FIRST_HALF_BTTS' then
                                       (o.outcome = 'VAR' and s.first_half_kg = 'VAR')
                                       or (o.outcome = 'YOK' and s.first_half_kg = 'YOK')
                                   else false
                               end as won
                        from odds_snapshots o
                        join scored s on s.provider_match_id = o.provider_match_id
                        where o.provider = :catalog
                          and o.snapshot_type in ('hourly_settled', 'hourly_pending')
                          and o.decimal_odds > 1.0
                    """
                            + marketClause
                            + """
                        order by o.provider_match_id, o.bookmaker, o.market, o.outcome,
                                 o.snapshot_at desc nulls last, o.id desc
                    )
                    select competition_code, league_name, league_slug, first_half_kg_taraf_code,
                           provider_match_id, bookmaker, market, outcome, decimal_odds, won
                    from latest
                    """);
            query.setParameter("catalog", catalogName);
            return query.getResultList();
        }).map(rows -> {
            List<HistoricalSample> samples = new ArrayList<>();
            Map<String, Double> bttsVarByMatchBook = new HashMap<>();
            Map<String, Map<String, Double>> sideByMatchBook = new HashMap<>();
            Set<String> directKgTarafKeys = new HashSet<>();
            Map<String, String> competitionByMatch = new HashMap<>();

            Map<String, String> kgTarafCodeByMatch = new HashMap<>();

            for (Object[] row : rows) {
                String competitionCode = String.valueOf(row[0]);
                String leagueName = String.valueOf(row[1]);
                String leagueSlug = String.valueOf(row[2]);
                String kgTarafCode = row[3] == null ? "" : String.valueOf(row[3]);
                if (!nesineOnlyHtft
                        && !LeagueCatalog.inCatalog(leagueName, leagueSlug)
                        && !LeagueCatalog.inCatalog(competitionCode)) {
                    continue;
                }
                String matchId = String.valueOf(row[4]);
                String bookmaker = String.valueOf(row[5]);
                String market = String.valueOf(row[6]);
                String outcome = String.valueOf(row[7]);
                double decimalOdds = asDouble(row[8]);
                boolean won = Boolean.TRUE.equals(row[9]) || "t".equalsIgnoreCase(String.valueOf(row[9]));
                if (!kgTarafCode.isBlank()) {
                    kgTarafCodeByMatch.putIfAbsent(matchId, kgTarafCode);
                }
                competitionByMatch.putIfAbsent(matchId, competitionCode);

                if (Markets.FIRST_HALF_BTTS.equals(market) && "VAR".equals(outcome)) {
                    bttsVarByMatchBook.put(matchId + "::" + bookmaker, decimalOdds);
                    samples.add(new HistoricalSample(
                            competitionCode, market, outcome, ScoreMath.oddsBand(decimalOdds), won));
                    continue;
                }
                if (Markets.FIRST_HALF_BTTS.equals(market)) {
                    continue;
                }
                if (Markets.FIRST_HALF_1X2.equals(market)) {
                    sideByMatchBook.computeIfAbsent(matchId + "::" + bookmaker, ignored -> new HashMap<>())
                            .put(outcome, decimalOdds);
                }
                if (!Markets.CANDIDATE_OUTCOMES.containsKey(market)) {
                    continue;
                }
                if (Markets.FIRST_HALF_KG_TARAF.equals(market)) {
                    directKgTarafKeys.add(matchId + "::" + bookmaker + "::" + outcome);
                }
                samples.add(new HistoricalSample(
                        competitionCode, market, outcome, ScoreMath.oddsBand(decimalOdds), won));
            }

            if (!nesineOnlyHtft) {
                for (Map.Entry<String, Double> e : bttsVarByMatchBook.entrySet()) {
                    String[] parts = e.getKey().split("::", 2);
                    if (parts.length != 2) {
                        continue;
                    }
                    String matchId = parts[0];
                    String bookmaker = parts[1];
                    String competitionCode = competitionByMatch.getOrDefault(matchId, "");
                    String actualKgTaraf = kgTarafCodeByMatch.getOrDefault(matchId, "");
                    Map<String, Double> sides = sideByMatchBook.getOrDefault(e.getKey(), Map.of());
                    for (String side : List.of("1", "X", "2")) {
                        Double sideOdds = sides.get(side);
                        if (sideOdds == null) {
                            continue;
                        }
                        String kgOutcome = "KG_VAR_" + side;
                        if (directKgTarafKeys.contains(matchId + "::" + bookmaker + "::" + kgOutcome)) {
                            continue;
                        }
                        double synthetic = round4(e.getValue() * sideOdds);
                        if (synthetic <= 1.0) {
                            continue;
                        }
                        samples.add(new HistoricalSample(
                                competitionCode,
                                Markets.FIRST_HALF_KG_TARAF,
                                kgOutcome,
                                ScoreMath.oddsBand(synthetic),
                                kgOutcome.equals(actualKgTaraf)));
                    }
                }
            }
            return samples;
        });
    }

    private Uni<Map<String, ProviderEventEntity>> loadEventsById(String catalogName) {
        return providerEventRepository
                .list("provider", catalogName)
                .map(rows -> rows.stream().collect(Collectors.toMap(e -> e.providerMatchId, e -> e, (a, b) -> a)));
    }

    private static boolean inCatalog(PendingFixture fixture, Map<String, ProviderEventEntity> eventsById) {
        ProviderEventEntity event = eventsById.get(fixture.eventId());
        if (event != null) {
            return LeagueCatalog.inCatalog(event.leagueName, event.leagueSlug);
        }
        return LeagueCatalog.inCatalog(fixture.competitionCode());
    }

    private static boolean hasCandidateOdds(PendingFixture fixture, boolean nesineOnlyHtft, boolean oddsApiKgOnly) {
        boolean hasKgTaraf = false;
        boolean hasBttsVar = false;
        for (String key : fixture.markets().keySet()) {
            String[] parts = key.split("::");
            if (parts.length != 2) {
                continue;
            }
            String market = parts[0];
            String outcome = parts[1];
            if (nesineOnlyHtft && Markets.HTFT.equals(market)
                    && Markets.CANDIDATE_OUTCOMES.getOrDefault(market, Set.of()).contains(outcome)) {
                return true;
            }
            if (oddsApiKgOnly) {
                if (Markets.FIRST_HALF_KG_TARAF.equals(market)
                        && Markets.CANDIDATE_OUTCOMES.getOrDefault(market, Set.of()).contains(outcome)) {
                    hasKgTaraf = true;
                }
                if (Markets.FIRST_HALF_BTTS.equals(market) && "VAR".equals(outcome)) {
                    hasBttsVar = true;
                }
            }
        }
        if (oddsApiKgOnly) {
            return hasKgTaraf || hasBttsVar;
        }
        return false;
    }

    private Uni<List<PendingFixture>> loadPendingFixtures(String catalogName, Map<String, ProviderEventEntity> eventsById) {
        return Panache.getSession().flatMap(session -> {
            @SuppressWarnings("unchecked")
            Mutiny.Query<Object[]> query = (Mutiny.Query<Object[]>) (Mutiny.Query<?>) session.createNativeQuery(
                    """
                    select o.provider_match_id, coalesce(e.competition_code, :catalog), coalesce(e.home_team, 'Home'),
                           coalesce(e.away_team, 'Away'), coalesce(cast(e.event_date as varchar), ''), o.bookmaker, o.market, o.outcome, o.decimal_odds
                    from (
                        select provider_match_id, bookmaker, market, outcome, decimal_odds,
                               row_number() over (partition by provider_match_id, bookmaker, market, outcome order by snapshot_at desc nulls last, id desc) rn
                        from odds_snapshots where provider = :catalog and snapshot_type = 'hourly_pending'
                    ) o
                    inner join provider_events e on e.provider = :catalog and e.provider_match_id = o.provider_match_id
                    where o.rn = 1 and lower(coalesce(e.status, 'pending')) not in ('settled', 'finished', 'complete', 'ft')
                    """);
            query.setParameter("catalog", catalogName);
            return query.getResultList();
        }).map(rows -> {
            Map<String, PendingFixture> fixtures = new LinkedHashMap<>();
            for (Object[] row : rows) {
                String eventId = String.valueOf(row[0]);
                if (!LeagueCatalog.NESINE_CATALOG.equals(catalogName)) {
                    ProviderEventEntity event = eventsById.get(eventId);
                    if (event != null) {
                        if (!LeagueCatalog.inCatalog(event.leagueName, event.leagueSlug)) {
                            continue;
                        }
                    } else if (!LeagueCatalog.inCatalog(String.valueOf(row[1]))) {
                        continue;
                    }
                }
                String bookmaker = String.valueOf(row[5]);
                String key = eventId + "::" + bookmaker;
                PendingFixture fixture = fixtures.computeIfAbsent(
                        key,
                        ignored -> new PendingFixture(
                                eventId,
                                String.valueOf(row[1]),
                                String.valueOf(row[4]).substring(0, Math.min(10, String.valueOf(row[4]).length())),
                                String.valueOf(row[2]),
                                String.valueOf(row[3]),
                                bookmaker,
                                new HashMap<>()));
                fixture.markets().put(String.valueOf(row[6]) + "::" + String.valueOf(row[7]), asDouble(row[8]));
            }
            addSynthetic(fixtures);
            return new ArrayList<>(fixtures.values());
        });
    }

    private void addSynthetic(Map<String, PendingFixture> fixtures) {
        for (PendingFixture fixture : fixtures.values()) {
            Double btts = null;
            Map<String, Double> sides = new HashMap<>();
            for (Map.Entry<String, Double> e : new ArrayList<>(fixture.markets().entrySet())) {
                String[] p = e.getKey().split("::");
                if (p.length != 2) {
                    continue;
                }
                if (Markets.FIRST_HALF_BTTS.equals(p[0]) && "VAR".equals(p[1])) {
                    btts = e.getValue();
                }
                if (Markets.FIRST_HALF_1X2.equals(p[0])) {
                    sides.put(p[1], e.getValue());
                }
            }
            if (btts != null) {
                for (String side : List.of("1", "X", "2")) {
                    if (sides.containsKey(side)) {
                        fixture.markets()
                                .putIfAbsent(
                                        Markets.FIRST_HALF_KG_TARAF + "::KG_VAR_" + side, round4(btts * sides.get(side)));
                    }
                }
            }
        }
    }

    private Map<String, BandStats> statsByBand(List<HistoricalSample> samples, java.util.function.Predicate<HistoricalSample> filter) {
        Map<String, BandStats> stats = new HashMap<>();
        for (HistoricalSample sample : samples) {
            if (!filter.test(sample)) {
                continue;
            }
            String key = bandKey(sample.market(), sample.outcome(), sample.band());
            stats.compute(key, (k, v) -> v == null ? BandStats.from(sample) : v.add(sample));
        }
        return stats;
    }

    private static String bandKey(String market, String outcome, String band) {
        return market + "::" + outcome + "::" + band;
    }

    private static String confidenceTier(double low, int n) {
        if (n >= 15 && low >= 0.55) {
            return "yuksek";
        }
        if (n >= 8 && low >= 0.45) {
            return "orta";
        }
        return "dusuk";
    }

    /** Gosterim filtresinden daha siki: n>=8, edge>=8%, pozitif ROI. */
    private static boolean isPlayPick(
            double edge, double roi, int sampleCount, PredictionSettingsService.PredictionThresholds thresholds) {
        int minSamples = Math.max(8, thresholds.minSamples());
        double minEdge = Math.max(0.08, thresholds.minEdge());
        return sampleCount >= minSamples && edge >= minEdge && roi > 0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double asDouble(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private record PendingFixture(
            String eventId,
            String competitionCode,
            String matchDate,
            String homeTeam,
            String awayTeam,
            String bookmaker,
            Map<String, Double> markets) {}

    private record HistoricalSample(String competitionCode, String market, String outcome, String band, boolean won) {}

    private static final class BandStats {
        int hits;
        int total;

        static BandStats from(HistoricalSample s) {
            BandStats b = new BandStats();
            b.total = 1;
            if (s.won()) {
                b.hits = 1;
            }
            return b;
        }

        BandStats add(HistoricalSample s) {
            total++;
            if (s.won()) {
                hits++;
            }
            return this;
        }

        int hits() {
            return hits;
        }

        int total() {
            return total;
        }

        double hitRate() {
            return total == 0 ? 0 : (double) hits / total;
        }
    }
}
