/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.quarkus.hibernate.reactive.panache.Panache
 *  io.quarkus.hibernate.reactive.panache.common.WithSession
 *  io.quarkus.hibernate.reactive.panache.common.WithTransaction
 *  io.smallrye.mutiny.Multi
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 *  org.hibernate.reactive.mutiny.Mutiny$Query
 *  org.hibernate.reactive.mutiny.Mutiny$Session
 */
package com.betagent.service;

import com.betagent.domain.LeagueCatalog;
import com.betagent.domain.Markets;
import com.betagent.persistence.ReactiveQueries;
import com.betagent.persistence.entity.MatchEntity;
import com.betagent.persistence.entity.MatchScoreEntity;
import com.betagent.persistence.entity.OddsSnapshotEntity;
import com.betagent.persistence.entity.ProviderEventEntity;
import com.betagent.persistence.entity.ProviderSyncRunEntity;
import com.betagent.persistence.repository.MatchRepository;
import com.betagent.persistence.repository.MatchScoreRepository;
import com.betagent.persistence.repository.OddsSnapshotRepository;
import com.betagent.persistence.repository.ProviderEventRepository;
import com.betagent.persistence.repository.ProviderSyncRunRepository;
import com.betagent.service.EventParser;
import com.betagent.service.NesineScoreSettlementService;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.lang.invoke.CallSite;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hibernate.reactive.mutiny.Mutiny;

@ApplicationScoped
@WithSession
public class WarehouseService {
    public static final String HTFT_SCORES_ONLY_BOOKMAKER = "Skor";
    @Inject
    MatchRepository matchRepository;
    @Inject
    MatchScoreRepository matchScoreRepository;
    @Inject
    OddsSnapshotRepository oddsSnapshotRepository;
    @Inject
    ProviderEventRepository providerEventRepository;
    @Inject
    ProviderSyncRunRepository syncRunRepository;

    @WithTransaction
    public Uni<Void> upsertProviderEvent(ProviderEventEntity incoming) {
        return this.providerEventRepository.find("provider = ?1 and providerMatchId = ?2", new Object[]{incoming.provider, incoming.providerMatchId}).firstResult().map(Optional::ofNullable).chain(existing -> {
            if (existing.isPresent()) {
                ProviderEventEntity entity = (ProviderEventEntity)existing.get();
                entity.leagueName = incoming.leagueName;
                entity.leagueSlug = incoming.leagueSlug;
                entity.competitionCode = incoming.competitionCode;
                entity.eventDate = incoming.eventDate;
                entity.homeTeam = incoming.homeTeam;
                entity.awayTeam = incoming.awayTeam;
                entity.status = incoming.status;
                entity.scoresJson = incoming.scoresJson;
                entity.rawJson = incoming.rawJson;
                entity.lastSeenAt = incoming.lastSeenAt;
                return this.providerEventRepository.persist(entity).replaceWithVoid();
            }
            return this.providerEventRepository.persist(incoming).replaceWithVoid();
        });
    }

    @WithTransaction
    public Uni<Void> upsertMatch(EventParser.MatchBundle bundle) {
        return this.matchRepository.find("provider = ?1 and providerMatchId = ?2", new Object[]{bundle.match().provider, bundle.match().providerMatchId}).firstResult().map(Optional::ofNullable).chain(existing -> {
            Uni upsertMatch;
            if (existing.isPresent()) {
                MatchEntity match = (MatchEntity)existing.get();
                match.competitionCode = bundle.match().competitionCode;
                match.matchDate = bundle.match().matchDate;
                match.homeTeam = bundle.match().homeTeam;
                match.awayTeam = bundle.match().awayTeam;
                upsertMatch = Uni.createFrom().voidItem();
            } else {
                upsertMatch = this.matchRepository.persist(bundle.match()).replaceWithVoid();
            }
            return upsertMatch.chain(() -> this.matchScoreRepository.find("provider = ?1 and providerMatchId = ?2", new Object[]{bundle.score().provider, bundle.score().providerMatchId}).firstResult().map(Optional::ofNullable).chain(scoreExisting -> {
                if (scoreExisting.isPresent()) {
                    MatchScoreEntity score = (MatchScoreEntity)scoreExisting.get();
                    score.hthg = bundle.score().hthg;
                    score.htag = bundle.score().htag;
                    score.fthg = bundle.score().fthg;
                    score.ftag = bundle.score().ftag;
                    score.htResult = bundle.score().htResult;
                    score.ftResult = bundle.score().ftResult;
                    score.htftCode = bundle.score().htftCode;
                    score.firstHalfKg = bundle.score().firstHalfKg;
                    score.firstHalfKgTarafCode = bundle.score().firstHalfKgTarafCode;
                    return Uni.createFrom().voidItem();
                }
                return this.matchScoreRepository.persist(bundle.score()).replaceWithVoid();
            }));
        });
    }

    @WithTransaction
    public Uni<Integer> insertSnapshots(List<OddsSnapshotEntity> snapshots) {
        if (snapshots.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        return Multi.createFrom().iterable(snapshots).onItem().transformToUniAndConcatenate(snapshot -> this.oddsSnapshotRepository.count("provider = ?1 and providerMatchId = ?2 and bookmaker = ?3 and market = ?4 and outcome = ?5 and snapshotType = ?6 and snapshotAt = ?7", new Object[]{snapshot.provider, snapshot.providerMatchId, snapshot.bookmaker, snapshot.market, snapshot.outcome, snapshot.snapshotType, snapshot.snapshotAt}).chain(count -> count == 0L ? this.oddsSnapshotRepository.persist(snapshot).replaceWith(1) : Uni.createFrom().item(0))).collect().asList().map(rows -> rows.stream().mapToInt(Integer::intValue).sum());
    }

    @WithTransaction
    public Uni<Void> resetProviderData(String catalogName) {
        return this.matchScoreRepository.delete("provider", new Object[]{catalogName}).chain(() -> this.oddsSnapshotRepository.delete("provider", new Object[]{catalogName})).chain(() -> this.matchRepository.delete("provider", new Object[]{catalogName})).chain(() -> this.providerEventRepository.delete("provider", new Object[]{catalogName})).replaceWithVoid();
    }

    @WithTransaction
    public Uni<Set<String>> findMatchIdsWithRecentSnapshot(String catalogName, String snapshotType, LocalDateTime since, List<String> candidateIds) {
        if (candidateIds.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }
        List<List<String>> batches = WarehouseService.partition(candidateIds, 400);
        return Panache.getSession().flatMap(session -> Multi.createFrom().iterable(batches).onItem().transformToUniAndConcatenate(batch -> session.createQuery("select distinct o.providerMatchId from OddsSnapshotEntity o\nwhere o.provider = :catalog and o.snapshotType = :type\n  and o.snapshotAt >= :since and o.providerMatchId in :ids\n", String.class).setParameter("catalog", catalogName).setParameter("type", snapshotType).setParameter("since", since).setParameter("ids", batch).getResultList()).collect().asList().map((List<List<String>> parts) -> {
            HashSet<String> found = new HashSet<>();
            for (List<String> rows : parts) {
                found.addAll(rows);
            }
            return found;
        }));
    }

    @WithTransaction
    public Uni<Set<String>> findMatchIdsWithAnySnapshot(String catalogName, List<String> matchIds) {
        if (matchIds.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }
        List<List<String>> batches = WarehouseService.partition(matchIds, 400);
        return Panache.getSession().flatMap(session -> Multi.createFrom().iterable(batches).onItem().transformToUniAndConcatenate(batch -> session.createQuery("select distinct o.providerMatchId from OddsSnapshotEntity o\nwhere o.provider = :catalog and o.providerMatchId in :ids\n", String.class).setParameter("catalog", catalogName).setParameter("ids", batch).getResultList()).collect().asList().map((List<List<String>> parts) -> {
            HashSet<String> found = new HashSet<>();
            for (List<String> rows : parts) {
                found.addAll(rows);
            }
            return found;
        }));
    }

    @WithTransaction
    public Uni<List<String>> findSettledIdsMissingOdds(String catalogName, List<String> settledIds) {
        if (settledIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return this.findMatchIdsWithAnySnapshot(catalogName, settledIds).chain(withOdds -> {
            List<List<String>> batches = WarehouseService.partition(settledIds, 400);
            return Panache.getSession().flatMap(session -> Multi.createFrom().iterable(batches).onItem().transformToUniAndConcatenate(batch -> session.createQuery("select s.providerMatchId from MatchScoreEntity s\nwhere s.provider = :catalog and s.providerMatchId in :ids\n", String.class).setParameter("catalog", catalogName).setParameter("ids", batch).getResultList()).collect().asList().map((List<List<String>> parts) -> {
                ArrayList<String> missing = new ArrayList<>();
                for (List<String> withScores : parts) {
                    for (String id : withScores) {
                        if (withOdds.contains(id)) continue;
                        missing.add(id);
                    }
                }
                return missing.stream().distinct().toList();
            }));
        });
    }

    @WithTransaction
    public Uni<List<String>> findScoredMatchIdsMissingOdds(String catalogName, int limit) {
        return Panache.getSession().flatMap(session -> session.createQuery("select s.providerMatchId\nfrom MatchScoreEntity s\nwhere s.provider = :catalog\n  and not exists (\n    select 1 from OddsSnapshotEntity o\n    where o.provider = :catalog and o.providerMatchId = s.providerMatchId\n  )\norder by s.providerMatchId desc\n", String.class).setParameter("catalog", catalogName).setMaxResults(Math.max(1, limit)).getResultList()).map(rows -> rows.stream().distinct().toList());
    }

    @WithTransaction
    public Uni<Integer> bridgePendingOddsToSettled(String catalogName, List<String> settledIds) {
        if (settledIds.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        List<List<String>> batches = WarehouseService.partition(settledIds, 300);
        LocalDateTime now = LocalDateTime.now();
        return Panache.getSession().flatMap(session -> Multi.createFrom().iterable(batches).onItem().transformToUniAndConcatenate(batch -> session.createQuery("select o from OddsSnapshotEntity o\nwhere o.provider = :catalog and o.snapshotType = 'hourly_pending'\n  and o.providerMatchId in :ids\norder by o.snapshotAt desc, o.id desc\n", OddsSnapshotEntity.class).setParameter("catalog", catalogName).setParameter("ids", batch).getResultList().chain(rows -> {
            HashMap<String, OddsSnapshotEntity> latest = new HashMap<>();
            for (OddsSnapshotEntity row : rows) {
                String key = row.providerMatchId + "::" + row.bookmaker + "::" + row.market + "::" + row.outcome;
                latest.putIfAbsent(key, row);
            }
            ArrayList<OddsSnapshotEntity> clones = new ArrayList<>();
            for (OddsSnapshotEntity src : latest.values()) {
                OddsSnapshotEntity dst = new OddsSnapshotEntity();
                dst.provider = src.provider;
                dst.providerMatchId = src.providerMatchId;
                dst.bookmaker = src.bookmaker;
                dst.market = src.market;
                dst.outcome = src.outcome;
                dst.decimalOdds = src.decimalOdds;
                dst.snapshotType = "hourly_settled";
                dst.snapshotAt = now;
                clones.add(dst);
            }
            return this.insertSnapshots(clones);
        })).collect().asList().map((List<Integer> parts) -> parts.stream().mapToInt(Integer::intValue).sum()));
    }

    public Uni<List<Map<String, Object>>> listScores(List<String> catalogs, int limit) {
        return ReactiveQueries.rowsNative("select m.provider_match_id, m.match_date, m.competition_code, m.home_team, m.away_team,\n       s.hthg, s.htag, s.fthg, s.ftag, s.ht_result, s.ft_result, s.htft_code, s.first_half_kg, s.first_half_kg_taraf_code,\n       m.provider\nfrom matches m\njoin match_scores s on s.provider = m.provider and s.provider_match_id = m.provider_match_id\nwhere m.provider in (?1)\norder by m.match_date desc nulls last, m.provider_match_id desc\nlimit ?2\n", catalogs, limit).map(rows -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object[] r : rows) {
            LinkedHashMap<String, Object> it = new LinkedHashMap<>();
            it.put("provider_match_id", r[0]);
            it.put("match_date", r[1]);
            it.put("competition_code", r[2]);
            it.put("home_team", r[3]);
            it.put("away_team", r[4]);
            it.put("hthg", r[5]);
            it.put("htag", r[6]);
            it.put("fthg", r[7]);
            it.put("ftag", r[8]);
            it.put("ht_result", r[9]);
            it.put("ft_result", r[10]);
            it.put("htft_code", r[11]);
            it.put("first_half_kg", r[12]);
            it.put("first_half_kg_taraf_code", r[13]);
            it.put("provider", r[14]);
            items.add(it);
            }
            return items;
        });
    }

    public Uni<Map<String, Object>> dataQualityMetrics(String catalogName) {
        Map<String, Object> quality = new LinkedHashMap<>();
        List<String> trackedMarkets = Markets.trackedMarketsForProvider(catalogName);
        return Panache.getSession().flatMap(session -> WarehouseService.nativeRows(session, "select count(*) from match_scores\nwhere provider = :catalog and (fthg < hthg or ftag < htag)\n", Map.of("catalog", catalogName)).chain(impossible -> {
            quality.put("impossible_ft_lt_ht", WarehouseService.toLong(impossible));
            return WarehouseService.nativeRows(session, "select count(*) from provider_events e\nwhere e.provider = :catalog\n  and lower(coalesce(e.status, 'pending')) in ('settled', 'finished', 'complete', 'ft')\n  and (e.raw_json::jsonb #> '{scores,periods,p1}') is null\n  and (e.raw_json::jsonb #> '{scores,halftime}') is null\n", Map.of("catalog", catalogName));
        }).chain(missingHt -> {
            quality.put("settled_missing_ht_breakdown", WarehouseService.toLong(missingHt));
            return this.matchScoreRepository.count("provider", new Object[]{catalogName});
        }).chain(scoredMatches -> {
            quality.put("scored_matches", scoredMatches);
            return WarehouseService.nativeRows(session, "select o.market, count(distinct o.provider_match_id)\nfrom odds_snapshots o\nwhere o.provider = :catalog\n  and o.snapshot_type = 'hourly_pending'\n  and o.market in ('HTFT', 'FIRST_HALF_1X2', 'FIRST_HALF_KG_TARAF', 'FIRST_HALF_BTTS')\ngroup by o.market\n", Map.of("catalog", catalogName));
        }).chain(pendingByMarket -> {
            HashMap<String, Long> pendingCounts = new HashMap<String, Long>();
            for (Object[] row : pendingByMarket) {
                pendingCounts.put(String.valueOf(row[0]), WarehouseService.toLong(List.of(row[1])));
            }
            return WarehouseService.nativeRows(session, "select o.market, count(distinct s.provider_match_id)\nfrom match_scores s\njoin odds_snapshots o\n  on o.provider = s.provider and o.provider_match_id = s.provider_match_id\nwhere s.provider = :catalog\n  and o.snapshot_type in ('hourly_settled', 'hourly_pending')\n  and o.market in ('HTFT', 'FIRST_HALF_1X2', 'FIRST_HALF_KG_TARAF', 'FIRST_HALF_BTTS')\ngroup by o.market\norder by o.market\n", Map.of("catalog", catalogName)).chain(historyByMarket -> {
                HashMap<String, Long> scoredCounts = new HashMap<String, Long>();
                for (Object[] row : historyByMarket) {
                    scoredCounts.put(String.valueOf(row[0]), WarehouseService.toLong(List.of(row[1])));
                }
                ArrayList marketRows = new ArrayList();
                for (String market : trackedMarkets) {
                    LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("market", market);
                    item.put("pending_matches_with_odds", pendingCounts.getOrDefault(market, 0L));
                    item.put("scored_matches_with_odds", scoredCounts.getOrDefault(market, 0L));
                    marketRows.add(item);
                }
                quality.put("history_by_market", marketRows);
                return WarehouseService.nativeRows(session, "with scored as (\n  select provider_match_id, ht_result, htft_code, first_half_kg_taraf_code\n  from match_scores where provider = :catalog\n),\nlatest as (\n  select distinct on (o.provider_match_id, o.bookmaker, o.market, o.outcome)\n         o.market, o.outcome, o.decimal_odds,\n         case\n           when o.market = 'FIRST_HALF_1X2' then s.ht_result = o.outcome\n           when o.market = 'HTFT' then s.htft_code = o.outcome\n           when o.market = 'FIRST_HALF_KG_TARAF' then s.first_half_kg_taraf_code = o.outcome\n           else false\n         end as won\n  from odds_snapshots o\n  join scored s on s.provider_match_id = o.provider_match_id\n  where o.provider = :catalog\n    and o.snapshot_type in ('hourly_settled', 'hourly_pending')\n    and o.decimal_odds > 1.0\n  order by o.provider_match_id, o.bookmaker, o.market, o.outcome, o.snapshot_at desc nulls last, o.id desc\n),\nbanded as (\n  select market, outcome,\n         case\n           when decimal_odds < 1.5 then '1.20-1.39'\n           when decimal_odds < 1.7 then '1.40-1.59'\n           when decimal_odds < 1.9 then '1.60-1.79'\n           when decimal_odds < 2.1 then '1.80-1.99'\n           when decimal_odds < 2.3 then '2.00-2.19'\n           when decimal_odds < 2.5 then '2.20-2.39'\n           when decimal_odds < 2.7 then '2.40-2.59'\n           when decimal_odds < 2.9 then '2.60-2.79'\n           when decimal_odds < 3.1 then '2.80-2.99'\n           when decimal_odds < 3.3 then '3.00-3.19'\n           when decimal_odds < 3.5 then '3.20-3.39'\n           when decimal_odds < 3.7 then '3.40-3.59'\n           when decimal_odds < 3.9 then '3.60-3.79'\n           when decimal_odds < 4.1 then '3.80-3.99'\n           else '4.00+'\n         end as band\n  from latest\n)\nselect market, outcome, band, count(*) as n\nfrom banded\ngroup by market, outcome, band\nhaving count(*) >= 8\norder by n desc\n", Map.of("catalog", catalogName));
            });
        }).chain(bands -> {
            ArrayList readyBands = new ArrayList();
            for (Object[] row : bands) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("market", String.valueOf(row[0]));
                item.put("outcome", String.valueOf(row[1]));
                item.put("band", String.valueOf(row[2]));
                item.put("sample_count", WarehouseService.toLong(List.of(row[3])));
                if (Markets.HTFT.equals(item.get("market")) && !Markets.htftOddsFromProvider(catalogName)) {
                    continue;
                }
                readyBands.add(item);
            }
            quality.put("bands_with_min_samples", readyBands);
            quality.put("bands_with_min_samples_count", readyBands.size());
            return this.appendHtftQualityMetrics(session, catalogName, quality);
        }));
    }

    private Uni<Map<String, Object>> appendHtftQualityMetrics(
            Mutiny.Session session, String catalogName, Map<String, Object> quality) {
        if (!Markets.htftOddsFromProvider(catalogName)) {
            quality.put("htft_distinct_pending_matches", 0L);
            quality.put("htft_settled_matches", 0L);
            quality.put("htft_pending_outcomes", emptyHtftOutcomeRows());
            quality.put("htft_settled_outcomes", emptyHtftOutcomeRows());
            return Uni.createFrom().item(quality);
        }
        return WarehouseService.nativeRows(session,
                """
                select o.outcome, count(distinct o.provider_match_id)
                from odds_snapshots o
                where o.provider = :catalog
                  and o.market = 'HTFT'
                  and o.snapshot_type = 'hourly_pending'
                  and o.outcome in ('1/2', '2/1', '1/X', '2/X')
                group by o.outcome
                order by o.outcome
                """,
                Map.of("catalog", catalogName)).chain(htftPendingRows -> WarehouseService.nativeRows(session,
                """
                select count(distinct o.provider_match_id)
                from odds_snapshots o
                where o.provider = :catalog
                  and o.market = 'HTFT'
                  and o.snapshot_type = 'hourly_pending'
                  and o.outcome in ('1/2', '2/1', '1/X', '2/X')
                """,
                Map.of("catalog", catalogName)).chain(distinctPending -> WarehouseService.nativeRows(session,
                """
                select s.htft_code, count(*)
                from match_scores s
                where s.provider = :catalog
                  and s.htft_code in ('1/2', '2/1', '1/X', '2/X')
                group by s.htft_code
                order by s.htft_code
                """,
                Map.of("catalog", catalogName)).map(htftSettledRows -> {
                    List<Map<String, Object>> htftRows = new ArrayList<>();
                    Map<String, Long> pendingByOutcome = new HashMap<>();
                    for (Object[] row : htftPendingRows) {
                        pendingByOutcome.put(String.valueOf(row[0]), WarehouseService.toLong(List.of(row[1])));
                    }
                    Map<String, Long> settledByOutcome = new HashMap<>();
                    for (Object[] row : htftSettledRows) {
                        settledByOutcome.put(String.valueOf(row[0]), WarehouseService.toLong(List.of(row[1])));
                    }
                    for (String outcome : List.of("1/2", "2/1", "1/X", "2/X")) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("outcome", outcome);
                        item.put("pending_matches", pendingByOutcome.getOrDefault(outcome, 0L));
                        item.put("settled_matches", settledByOutcome.getOrDefault(outcome, 0L));
                        htftRows.add(item);
                    }
                    quality.put("htft_distinct_pending_matches", WarehouseService.toLong(distinctPending));
                    quality.put("htft_settled_matches", settledByOutcome.values().stream().mapToLong(Long::longValue).sum());
                    quality.put("htft_pending_outcomes", htftRows);
                    quality.put("htft_settled_outcomes", htftRows);
                    return quality;
                })));
    }

    private static List<Map<String, Object>> emptyHtftOutcomeRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String outcome : List.of("1/2", "2/1", "1/X", "2/X")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("outcome", outcome);
            item.put("pending_matches", 0L);
            item.put("settled_matches", 0L);
            rows.add(item);
        }
        return rows;
    }

    public Uni<List<Map<String, Object>>> latestHtftOdds(String catalogName, int limit) {
        return Panache.getSession().flatMap(session -> WarehouseService.nativeRows(session, "select distinct on (o.provider_match_id, o.outcome)\n       o.provider_match_id,\n       coalesce(m.competition_code, pe.league_name),\n       coalesce(m.match_date, cast(pe.event_date as date)),\n       coalesce(m.home_team, pe.home_team),\n       coalesce(m.away_team, pe.away_team),\n       o.bookmaker,\n       o.outcome,\n       o.decimal_odds,\n       o.snapshot_at\nfrom odds_snapshots o\nleft join matches m\n  on m.provider = o.provider and m.provider_match_id = o.provider_match_id\nleft join provider_events pe\n  on pe.provider = o.provider and pe.provider_match_id = o.provider_match_id\nwhere o.provider = :catalog\n  and o.market = 'HTFT'\n  and o.snapshot_type = 'hourly_pending'\n  and o.outcome in ('1/2', '2/1', '1/X', '2/X')\n  and (m.provider_match_id is not null or pe.provider_match_id is not null)\norder by o.provider_match_id, o.outcome, o.snapshot_at desc nulls last, o.id desc\nlimit :limit\n", Map.of("catalog", catalogName, "limit", Math.max(1, limit)))).map(rows -> {
            ArrayList items = new ArrayList();
            for (Object[] row : rows) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("provider_match_id", row[0]);
                item.put("competition_code", row[1]);
                item.put("match_date", row[2]);
                item.put("home_team", row[3]);
                item.put("away_team", row[4]);
                item.put("bookmaker", row[5]);
                item.put("outcome", row[6]);
                item.put("decimal_odds", row[7]);
                item.put("snapshot_at", row[8]);
                items.add(item);
            }
            return items;
        });
    }

    public Uni<List<Map<String, Object>>> latestHtftOddsAll(List<String> catalogNames, int limitPerCatalog) {
        return this.htftOddsPage(catalogNames, null, 1, limitPerCatalog).map(page -> {
            List items = (List)page.get("items");
            return items;
        });
    }

    public Uni<Map<String, Object>> htftOddsPage(List<String> catalogNames, String bookmaker, int page, int pageSize) {
        if (catalogNames.isEmpty()) {
            return Uni.createFrom().item(Map.of("items", List.of(), "total", 0, "page", 1, "page_size", pageSize, "total_pages", 0));
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safeSize;
        if (HTFT_SCORES_ONLY_BOOKMAKER.equalsIgnoreCase(bookmaker)) {
            return this.htftScoresOnlyPage(catalogNames, safePage, safeSize, offset);
        }
        String bookmakerClause = bookmaker != null && !bookmaker.isBlank() ? " and lo.bookmaker = :bookmaker" : "";
        String baseCte = "with latest_odds as (\n    select distinct on (o.provider, o.provider_match_id, o.bookmaker, o.outcome)\n           o.provider,\n           o.provider_match_id,\n           o.bookmaker,\n           o.outcome,\n           o.decimal_odds,\n           o.snapshot_at\n    from odds_snapshots o\n    where o.provider in :catalogs\n      and o.market = 'HTFT'\n      and o.snapshot_type in ('hourly_pending', 'hourly_settled')\n      and o.outcome in ('1/2', '2/1', '1/X', '2/X')\n    order by o.provider, o.provider_match_id, o.bookmaker, o.outcome,\n             case o.snapshot_type when 'hourly_settled' then 0 else 1 end,\n             o.snapshot_at desc nulls last, o.id desc\n),\ngrouped as (\n    select lo.provider,\n           lo.provider_match_id,\n           lo.bookmaker,\n           max(lo.snapshot_at) as snapshot_at,\n           max(case when lo.outcome = '1/2' then lo.decimal_odds end) as odd_12,\n           max(case when lo.outcome = '2/1' then lo.decimal_odds end) as odd_21,\n           max(case when lo.outcome = '1/X' then lo.decimal_odds end) as odd_1x,\n           max(case when lo.outcome = '2/X' then lo.decimal_odds end) as odd_2x\n    from latest_odds lo\n    where 1=1" + bookmakerClause + "    group by lo.provider, lo.provider_match_id, lo.bookmaker\n),\nfiltered as (\n    select g.*,\n           coalesce(m.competition_code, pe.league_name) as competition_code,\n           coalesce(m.match_date, cast(pe.event_date as date)) as match_date,\n           coalesce(pe.event_date, cast(m.match_date as timestamp)) as kickoff_at,\n           to_char(\n               coalesce(pe.event_date, cast(m.match_date as timestamp)),\n               'YYYY-MM-DD HH24:MI'\n           ) as kickoff_at_text,\n           coalesce(m.home_team, pe.home_team) as home_team,\n           coalesce(m.away_team, pe.away_team) as away_team,\n           s.hthg, s.htag, s.fthg, s.ftag, s.htft_code,\n           case when s.provider_match_id is not null then 'finished' else 'pending' end as status\n    from grouped g\n    left join matches m\n      on m.provider = g.provider and m.provider_match_id = g.provider_match_id\n    left join provider_events pe\n      on pe.provider = g.provider and pe.provider_match_id = g.provider_match_id\n    left join match_scores s\n      on s.provider = g.provider and s.provider_match_id = g.provider_match_id\n    where s.provider_match_id is null\n       or s.htft_code in ('1/2', '2/1', '1/X', '2/X')\n)\n";
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("catalogs", catalogNames);
        if (bookmaker != null && !bookmaker.isBlank()) {
            params.put("bookmaker", bookmaker);
        }
        return Panache.getSession().flatMap(session -> WarehouseService.nativeRows(session, baseCte + " select count(*) from filtered", params).chain(countRows -> {
            long total = WarehouseService.toLong(countRows);
            HashMap<String, Object> dataParams = new HashMap<String, Object>(params);
            dataParams.put("limit", safeSize);
            dataParams.put("offset", offset);
            return WarehouseService.nativeRows(session, baseCte + "select provider, provider_match_id, bookmaker, competition_code, match_date, kickoff_at_text,\n       home_team, away_team, odd_12, odd_21, odd_1x, odd_2x,\n       snapshot_at, hthg, htag, fthg, ftag, htft_code, status\nfrom filtered\norder by kickoff_at asc nulls last, provider_match_id asc\nlimit :limit offset :offset\n", dataParams).map(dataRows -> WarehouseService.buildHtftOddsPagePayload(dataRows, total, safePage, safeSize, bookmaker));
        }));
    }

    private Uni<Map<String, Object>> htftScoresOnlyPage(List<String> catalogNames, int safePage, int safeSize, int offset) {
        String baseCte = "with score_only as (\n    select s.provider,\n           s.provider_match_id,\n'Skor' as bookmaker,           coalesce(m.competition_code, pe.league_name) as competition_code,\n           coalesce(m.match_date, cast(pe.event_date as date)) as match_date,\n           coalesce(pe.event_date, cast(m.match_date as timestamp)) as kickoff_at,\n           to_char(\n               coalesce(pe.event_date, cast(m.match_date as timestamp)),\n               'YYYY-MM-DD HH24:MI'\n           ) as kickoff_at_text,\n           coalesce(m.home_team, pe.home_team) as home_team,\n           coalesce(m.away_team, pe.away_team) as away_team,\n           null::numeric as odd_12,\n           null::numeric as odd_21,\n           null::numeric as odd_1x,\n           null::numeric as odd_2x,\n           null::timestamp as snapshot_at,\n           s.hthg, s.htag, s.fthg, s.ftag, s.htft_code,\n           'finished' as status\n    from match_scores s\n    left join matches m\n      on m.provider = s.provider and m.provider_match_id = s.provider_match_id\n    left join provider_events pe\n      on pe.provider = s.provider and pe.provider_match_id = s.provider_match_id\n    where s.provider in :catalogs\n      and s.htft_code in ('1/2', '2/1', '1/X', '2/X')\n      and not exists (\n          select 1\n          from odds_snapshots o\n          where o.provider = s.provider\n            and o.provider_match_id = s.provider_match_id\n            and o.market = 'HTFT'\n            and o.outcome in ('1/2', '2/1', '1/X', '2/X')\n            and o.snapshot_type in ('hourly_pending', 'hourly_settled')\n      )\n)\n";
        HashMap<String, Object> params = new HashMap<>();
        params.put("catalogs", catalogNames);
        return Panache.getSession().flatMap(session -> WarehouseService.nativeRows(session, baseCte + " select count(*) from score_only", params).chain(countRows -> {
            long total = WarehouseService.toLong(countRows);
            HashMap<String, Object> dataParams = new HashMap<>(params);
            dataParams.put("limit", safeSize);
            dataParams.put("offset", offset);
            return WarehouseService.nativeRows(session, baseCte + "select provider, provider_match_id, bookmaker, competition_code, match_date, kickoff_at_text,\n       home_team, away_team, odd_12, odd_21, odd_1x, odd_2x,\n       snapshot_at, hthg, htag, fthg, ftag, htft_code, status\nfrom score_only\norder by kickoff_at desc nulls last, provider_match_id asc\nlimit :limit offset :offset\n", dataParams).map(dataRows -> WarehouseService.buildHtftOddsPagePayload(dataRows, total, safePage, safeSize, HTFT_SCORES_ONLY_BOOKMAKER));
        }));
    }

    private static Map<String, Object> buildHtftOddsPagePayload(List<Object[]> dataRows, long total, int safePage, int safeSize, String bookmaker) {
        ArrayList items = new ArrayList();
        for (Object[] row : dataRows) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("provider", row[0]);
            item.put("provider_match_id", row[1]);
            item.put("bookmaker", row[2]);
            item.put("competition_code", row[3]);
            item.put("match_date", row[4]);
            item.put("kickoff_at", row[5] != null ? String.valueOf(row[5]) : null);
            item.put("home_team", row[6]);
            item.put("away_team", row[7]);
            item.put("odds", WarehouseService.oddsMap(row[8], row[9], row[10], row[11]));
            item.put("snapshot_at", row[12]);
            if (row[13] != null) {
                item.put("hthg", row[13]);
                item.put("htag", row[14]);
                item.put("fthg", row[15]);
                item.put("ftag", row[16]);
                item.put("htft_code", row[17]);
            }
            item.put("status", row[18]);
            items.add(item);
        }
        int totalPages = total == 0L ? 0 : (int)Math.ceil((double)total / (double)safeSize);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("items", items);
        payload.put("total", total);
        payload.put("page", safePage);
        payload.put("page_size", safeSize);
        payload.put("total_pages", totalPages);
        if (bookmaker != null && !bookmaker.isBlank()) {
            payload.put("bookmaker", bookmaker);
        }
        return payload;
    }

    private static Map<String, Object> oddsMap(Object odd12, Object odd21, Object odd1x, Object odd2x) {
        LinkedHashMap<String, Object> odds = new LinkedHashMap<String, Object>();
        WarehouseService.putOdd(odds, "1/2", odd12);
        WarehouseService.putOdd(odds, "2/1", odd21);
        WarehouseService.putOdd(odds, "1/X", odd1x);
        WarehouseService.putOdd(odds, "2/X", odd2x);
        return odds;
    }

    private static void putOdd(Map<String, Object> odds, String outcome, Object value) {
        if (value != null) {
            Double d;
            if (value instanceof Number) {
                Number n = (Number)value;
                d = n.doubleValue();
            } else {
                d = Double.parseDouble(String.valueOf(value));
            }
            odds.put(outcome, d);
        }
    }

    public Uni<Map<String, Object>> aggregatedDataQuality(List<String> catalogNames) {
        return Multi.createFrom().iterable(catalogNames).onItem().transformToUniAndConcatenate(this::dataQualityMetrics).collect().asList().map((List<Map<String, Object>> qualities) -> {
            LinkedHashMap<String, Long> pendingByMarket = new LinkedHashMap<>();
            LinkedHashMap<String, Long> scoredByMarket = new LinkedHashMap<>();
            LinkedHashMap<String, Long> htftPendingByOutcome = new LinkedHashMap<>();
            LinkedHashMap<String, Long> htftSettledByOutcome = new LinkedHashMap<>();
            long htftDistinctPending = 0L;
            long htftSettledTotal = 0L;
            long scoredMatches = 0L;
            ArrayList<Map<String, Object>> bands = new ArrayList<>();
            ArrayList<Map<String, Object>> byProvider = new ArrayList<>();
            for (int i = 0; i < qualities.size(); ++i) {
                Map<String, Object> quality = qualities.get(i);
                String provider = i < catalogNames.size() ? catalogNames.get(i) : "unknown";
                boolean nesineHtft = Markets.htftOddsFromProvider(provider);
                if (nesineHtft) {
                    htftDistinctPending += ((Number)quality.getOrDefault("htft_distinct_pending_matches", 0L)).longValue();
                    htftSettledTotal += ((Number)quality.getOrDefault("htft_settled_matches", 0L)).longValue();
                }
                scoredMatches += ((Number)quality.getOrDefault("scored_matches", 0L)).longValue();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> marketRows = (List<Map<String, Object>>) quality.get("history_by_market");
                if (marketRows != null) {
                    for (Map<String, Object> row : marketRows) {
                        String market = String.valueOf(row.get("market"));
                        if (Markets.HTFT.equals(market) && !nesineHtft) {
                            continue;
                        }
                        pendingByMarket.merge(market, ((Number)row.get("pending_matches_with_odds")).longValue(), Long::sum);
                        scoredByMarket.merge(market, ((Number)row.get("scored_matches_with_odds")).longValue(), Long::sum);
                    }
                }
                if (nesineHtft) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> htftRows = (List<Map<String, Object>>) quality.get("htft_pending_outcomes");
                    if (htftRows != null) {
                        for (Map<String, Object> row : htftRows) {
                            String outcome = String.valueOf(row.get("outcome"));
                            htftPendingByOutcome.merge(outcome, ((Number)row.getOrDefault("pending_matches", 0L)).longValue(), Long::sum);
                            htftSettledByOutcome.merge(outcome, ((Number)row.getOrDefault("settled_matches", 0L)).longValue(), Long::sum);
                        }
                    }
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> readyBands = (List<Map<String, Object>>) quality.get("bands_with_min_samples");
                if (readyBands != null) {
                    for (Map<String, Object> band : readyBands) {
                        if (Markets.HTFT.equals(band.get("market")) && !nesineHtft) {
                            continue;
                        }
                        bands.add(band);
                    }
                }
            }
            for (int i = 0; i < qualities.size(); ++i) {
                Map<String, Object> quality = qualities.get(i);
                String provider = i < catalogNames.size() ? catalogNames.get(i) : "unknown";
                LinkedHashMap<String, Object> providerQuality = new LinkedHashMap<>(quality);
                providerQuality.put("provider", provider);
                byProvider.add(providerQuality);
            }
            List<String> trackedMarkets = List.of("HTFT", "FIRST_HALF_1X2", "FIRST_HALF_KG_TARAF", "FIRST_HALF_BTTS");
            ArrayList marketRows = new ArrayList();
            for (String string : trackedMarkets) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("market", string);
                item.put("pending_matches_with_odds", pendingByMarket.getOrDefault(string, 0L));
                item.put("scored_matches_with_odds", scoredByMarket.getOrDefault(string, 0L));
                marketRows.add(item);
            }
            ArrayList htftRows = new ArrayList();
            for (String outcome : List.of("1/2", "2/1", "1/X", "2/X")) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("outcome", outcome);
                item.put("pending_matches", htftPendingByOutcome.getOrDefault(outcome, 0L));
                item.put("settled_matches", htftSettledByOutcome.getOrDefault(outcome, 0L));
                htftRows.add(item);
            }
            Map<String, Object> aggregated = new LinkedHashMap<>();
            aggregated.put("impossible_ft_lt_ht", 0L);
            aggregated.put("settled_missing_ht_breakdown", 0L);
            aggregated.put("scored_matches", scoredMatches);
            aggregated.put("htft_distinct_pending_matches", htftDistinctPending);
            aggregated.put("htft_settled_matches", htftSettledTotal);
            aggregated.put("history_by_market", marketRows);
            aggregated.put("bands_with_min_samples", bands);
            aggregated.put("bands_with_min_samples_count", bands.size());
            aggregated.put("htft_pending_outcomes", htftRows);
            aggregated.put("htft_settled_outcomes", htftRows);
            aggregated.put("by_provider", byProvider);
            return aggregated;
        });
    }

    public Uni<Map<String, Object>> dashboard(String catalogName) {
        return this.matchRepository.countByProvider(catalogName).chain(matches -> this.oddsSnapshotRepository.countHourlyByProvider(catalogName).chain(hourly -> this.oddsSnapshotRepository.count("provider", new Object[]{catalogName}).chain(total -> this.dataQualityMetrics(catalogName).chain(quality -> this.syncRunRepository.findLatest(catalogName).map(opt -> opt.orElse(null)).onFailure().recoverWithItem((ProviderSyncRunEntity) null).chain(collector -> Panache.getSession().flatMap(session -> session.createQuery("select m.competitionCode, count(m) from MatchEntity m where m.provider = :p group by m.competitionCode order by count(m) desc", Object[].class).setParameter("p", catalogName).setMaxResults(15).getResultList()).map(leagues -> {
            HashMap<String, Object> payload = new HashMap<String, Object>();
            payload.put("matches", matches);
            payload.put("hourly_snapshots", hourly);
            payload.put("odds_snapshots", total);
            payload.put("data_quality", quality);
            payload.put("collector", collector);
            payload.put("leagues", leagues);
            return payload;
        }))))));
    }

    @WithTransaction
    public Uni<List<String>> findMatchIdsMissingScores(String catalogName, int limit) {
        return Panache.getSession().flatMap(session -> session.createQuery("select distinct pe.providerMatchId\nfrom ProviderEventEntity pe\nwhere pe.provider = :catalog\n  and exists (\n    select 1 from OddsSnapshotEntity o\n    where o.provider = :catalog and o.providerMatchId = pe.providerMatchId\n  )\n  and not exists (\n    select 1 from MatchScoreEntity s\n    where s.provider = :catalog and s.providerMatchId = pe.providerMatchId\n  )\norder by pe.providerMatchId desc\n", String.class).setParameter("catalog", catalogName).setMaxResults(Math.max(1, limit)).getResultList()).map(rows -> rows.stream().distinct().toList());
    }

    @WithTransaction
    public Uni<Integer> copyScoresFromOtherProviders(String targetCatalog, Set<String> missingIds) {
        if (missingIds.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        return Panache.getSession().flatMap(session -> session.createQuery("select s.providerMatchId, s.provider, s.hthg, s.htag, s.fthg, s.ftag,\n       s.htResult, s.ftResult, s.htftCode, s.firstHalfKg, s.firstHalfKgTarafCode,\n       m.homeTeam, m.awayTeam, m.matchDate, m.competitionCode\nfrom MatchScoreEntity s\njoin MatchEntity m on m.provider = s.provider and m.providerMatchId = s.providerMatchId\nwhere s.provider <> :target\n", Object[].class).setParameter("target", targetCatalog).getResultList().chain(candidates -> {
            HashMap<String, Object[]> byTeamDate = new HashMap<String, Object[]>();
            for (Object[] row : candidates) {
                LocalDate ld;
                LocalDate date;
                String home = String.valueOf(row[11]);
                String away = String.valueOf(row[12]);
                Object patt0$temp = row[13];
                LocalDate localDate = date = patt0$temp instanceof LocalDate ? (ld = (LocalDate)patt0$temp) : null;
                if (date == null) continue;
                byTeamDate.put(WarehouseService.crossProviderKey(home, away, date), row);
            }
            return Multi.createFrom().iterable(List.copyOf(missingIds)).onItem().transformToUniAndConcatenate(matchId -> this.copySingleScore(targetCatalog, missingIds, (Map<String, Object[]>)byTeamDate, (String)matchId)).collect().asList().map(parts -> parts.stream().mapToInt(Integer::intValue).sum());
        }));
    }

    private static String crossProviderKey(String home, String away, LocalDate date) {
        return NesineScoreSettlementService.normalizeTeam(home) + "|" + NesineScoreSettlementService.normalizeTeam(away) + "|" + String.valueOf(date);
    }

    private Uni<Integer> copySingleScore(String targetCatalog, Set<String> missingIds, Map<String, Object[]> byTeamDate, String matchId) {
        return this.providerEventRepository.find("provider = ?1 and providerMatchId = ?2", new Object[]{targetCatalog, matchId}).firstResult().chain(event -> {
            if (event == null || event.eventDate == null) {
                return Uni.createFrom().item(0);
            }
            Object[] source = (Object[])byTeamDate.get(WarehouseService.crossProviderKey(event.homeTeam, event.awayTeam, event.eventDate.toLocalDate()));
            if (source == null) {
                return Uni.createFrom().item(0);
            }
            return this.matchScoreRepository.find("provider = ?1 and providerMatchId = ?2", new Object[]{targetCatalog, matchId}).firstResult().chain(existingScore -> {
                if (existingScore != null) {
                    return Uni.createFrom().item(0);
                }
                MatchEntity match = new MatchEntity();
                match.provider = targetCatalog;
                match.providerMatchId = matchId;
                match.competitionCode = event.competitionCode != null ? event.competitionCode : event.leagueName;
                match.matchDate = event.eventDate.toLocalDate();
                match.season = String.valueOf(match.matchDate.getYear());
                match.homeTeam = event.homeTeam;
                match.awayTeam = event.awayTeam;
                return this.matchRepository.find("provider = ?1 and providerMatchId = ?2", new Object[]{targetCatalog, matchId}).firstResult().chain(existingMatch -> {
                    Uni upsertMatch;
                    if (existingMatch != null) {
                        existingMatch.competitionCode = match.competitionCode;
                        existingMatch.matchDate = match.matchDate;
                        existingMatch.homeTeam = match.homeTeam;
                        existingMatch.awayTeam = match.awayTeam;
                        upsertMatch = Uni.createFrom().voidItem();
                    } else {
                        upsertMatch = this.matchRepository.persist(match).replaceWithVoid();
                    }
                    MatchScoreEntity score = new MatchScoreEntity();
                    score.provider = targetCatalog;
                    score.providerMatchId = matchId;
                    score.hthg = (Integer)source[2];
                    score.htag = (Integer)source[3];
                    score.fthg = (Integer)source[4];
                    score.ftag = (Integer)source[5];
                    score.htResult = (String)source[6];
                    score.ftResult = (String)source[7];
                    score.htftCode = (String)source[8];
                    score.firstHalfKg = (String)source[9];
                    score.firstHalfKgTarafCode = (String)source[10];
                    return upsertMatch.chain(() -> this.matchScoreRepository.persist(score).replaceWithVoid()).chain(() -> {
                        event.status = "finished";
                        event.lastSeenAt = LocalDateTime.now();
                        return this.providerEventRepository.persist(event).replaceWithVoid();
                    }).replaceWith(1).invoke(() -> missingIds.remove(matchId));
                });
            });
        });
    }

    private static List<List<String>> partition(List<String> values, int batchSize) {
        ArrayList<List<String>> batches = new ArrayList<List<String>>();
        for (int i = 0; i < values.size(); i += batchSize) {
            batches.add(values.subList(i, Math.min(i + batchSize, values.size())));
        }
        return batches;
    }

    private static Uni<List<Object[]>> nativeRows(Mutiny.Session session, String sql, Map<String, Object> params) {
        Mutiny.Query query = session.createNativeQuery(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
        return query.getResultList();
    }

    private static long toLong(List<?> rows) {
        if (rows == null || rows.isEmpty() || rows.getFirst() == null) {
            return 0L;
        }
        Object value = rows.getFirst();
        if (value instanceof Number) {
            Number number = (Number)value;
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}

