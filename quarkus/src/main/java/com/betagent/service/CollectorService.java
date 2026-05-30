/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Multi
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 *  jakarta.ws.rs.WebApplicationException
 *  org.jboss.logging.Logger
 */
package com.betagent.service;

import com.betagent.config.OddsApiConfig;
import com.betagent.domain.LeagueCatalog;
import com.betagent.domain.Markets;
import com.betagent.persistence.ReactiveContextRunner;
import com.betagent.persistence.entity.OddsSnapshotEntity;
import com.betagent.persistence.entity.ProviderSyncRunEntity;
import com.betagent.persistence.repository.ProviderSyncRunRepository;
import com.betagent.provider.EventStatus;
import com.betagent.provider.OddsDataProvider;
import com.betagent.provider.OddsProviderRegistry;
import com.betagent.provider.nesine.NesineBultenService;
import com.betagent.provider.oddsapiio.OddsApiBookmakerValidator;
import com.betagent.provider.oddsapiio.OddsApiErrors;
import com.betagent.provider.oddsapiio.OddsApiKeyPool;
import com.betagent.service.EventParser;
import com.betagent.service.OddsNormalizer;
import com.betagent.service.RequestBudget;
import com.betagent.service.SyncRunService;
import com.betagent.service.WarehouseService;
import com.betagent.service.model.CollectionResult;
import com.betagent.service.model.LeagueStat;
import com.betagent.service.model.PersistedEvents;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CollectorService {
    private static final Logger LOG = Logger.getLogger(CollectorService.class);
    public static final String API_QUOTA_EXCEEDED_MARKER = "api_quota_exceeded";
    public static final String API_RATE_LIMIT_MARKER = "api_rate_limited";
    private final AtomicBoolean running = new AtomicBoolean(false);
    @Inject
    OddsApiConfig config;
    @Inject
    OddsProviderRegistry providerRegistry;
    @Inject
    EventParser eventParser;
    @Inject
    OddsNormalizer oddsNormalizer;
    @Inject
    WarehouseService warehouse;
    @Inject
    ProviderSyncRunRepository syncRunRepository;
    @Inject
    SyncRunService syncRunService;
    @Inject
    OddsApiKeyPool oddsApiKeyPool;
    @Inject
    OddsApiBookmakerValidator bookmakerValidator;
    @Inject
    NesineBultenService nesineBultenService;
    @Inject
    ReactiveContextRunner contextRunner;
    private static final int MAX_BAD_REQUEST_CHUNKS_BEFORE_ABORT = 1;

    public boolean isRunning() {
        if (this.running.get()) {
            return true;
        }
        return (Boolean)this.contextRunner.await(this::isRunningOnContextUni);
    }

    public boolean isRunningOnContext() {
        return this.running.get();
    }

    public Uni<Boolean> isRunningOnContextUni() {
        if (this.running.get()) {
            return Uni.createFrom().item(true);
        }
        return Multi.createFrom().iterable(this.providerRegistry.configuredProviders()).onItem().transformToUniAndConcatenate(provider -> this.releaseStaleRunsReactive(provider.catalogName()).chain(() -> this.syncRunRepository.findRunning(provider.catalogName()).map(Optional::isPresent))).collect().asList().map(flags -> flags.stream().anyMatch(Boolean::booleanValue));
    }

    public ProviderSyncRunEntity startBackgroundCollect() {
        return this.startBackgroundCollect(false);
    }

    public ProviderSyncRunEntity startBackgroundCollect(boolean force) {
        return (ProviderSyncRunEntity)this.contextRunner.await(() -> this.startBackgroundCollectAsync(force));
    }

    public Uni<ProviderSyncRunEntity> startBackgroundCollectAsync(boolean force) {
        List<OddsDataProvider> providers = this.providerRegistry.configuredProviders();
        if (providers.isEmpty()) {
            return Uni.createFrom().failure((Throwable)new IllegalStateException("No odds providers configured"));
        }
        if (!this.running.compareAndSet(false, true)) {
            return Uni.createFrom().failure((Throwable)new IllegalStateException("Collector is already running"));
        }
        Uni prep = Uni.createFrom().voidItem();
        for (OddsDataProvider provider : providers) {
            prep = prep.chain(() -> this.releaseStaleRunsReactive(provider.catalogName()));
        }
        return prep.chain(() -> this.assertNotTooSoonAsync(providers, force)).chain(() -> this.syncRunService.start(((OddsDataProvider)providers.getFirst()).catalogName(), this.requestBudgetFor((OddsDataProvider)providers.getFirst()))).invoke(firstRun -> this.contextRunner.subscribe(() -> this.runCollectorPipeline(providers, (ProviderSyncRunEntity)firstRun).onFailure().invoke(err -> LOG.error("Multi-provider collector failed", err)).eventually(() -> {
            this.running.set(false);
            return Uni.createFrom().voidItem();
        }).replaceWithVoid())).onFailure().invoke(() -> this.running.set(false));
    }

    private Uni<Void> runCollectorPipeline(List<OddsDataProvider> providers, ProviderSyncRunEntity firstRun) {
        Uni pipeline = this.collectForRun(providers.getFirst(), firstRun);
        for (int i = 1; i < providers.size(); ++i) {
            OddsDataProvider provider = providers.get(i);
            pipeline = pipeline.chain(() -> this.syncRunService.start(provider.catalogName(), this.requestBudgetFor(provider)).chain(run -> this.collectForRun(provider, (ProviderSyncRunEntity)run)));
        }
        return pipeline.invoke(() -> LOG.info("Multi-provider collector finished"));
    }

    private Uni<Void> collectForRun(OddsDataProvider provider, ProviderSyncRunEntity run) {
        LOG.infof("Collector starting for provider %s (%s)", provider.id(), provider.catalogName());
        Uni<CollectionResult> collection = this.collect(provider);
        if ("nesine".equals(provider.id())) {
            collection = collection.ifNoItem().after(Duration.ofMinutes(6)).failWith(
                    () -> new IllegalStateException("Nesine bulten toplama zaman asimi (6 dk)"));
        }
        return collection.call(result -> this.syncRunService.finishAsync(run.id, (CollectionResult)result).replaceWith(result)).onFailure().call(ex -> {
            LOG.errorf(ex, "Collector failed for %s", provider.catalogName());
            return this.syncRunService.failAsync(run.id, this.normalizeFailureMessage((Throwable)ex));
        }).replaceWithVoid();
    }

    private int requestBudgetFor(OddsDataProvider provider) {
        if ("nesine".equals(provider.id())) {
            return 3;
        }
        return this.totalRequestBudget();
    }

    public Uni<ProviderSyncRunEntity> startBackgroundBackfillSettledOddsAsync(int limit) {
        OddsDataProvider provider = this.providerRegistry.active();
        return this.releaseStaleRunsReactive(provider.catalogName()).chain(() -> {
            if (!this.running.compareAndSet(false, true)) {
                return Uni.createFrom().failure((Throwable)new IllegalStateException("Collector is already running"));
            }
            return this.syncRunService.start(provider.catalogName(), this.totalRequestBudget()).chain(run -> this.backfillSettledOdds(provider, limit).call(result -> this.syncRunService.finishAsync(run.id, (CollectionResult)result).replaceWith(result)).onFailure().call(ex -> {
                LOG.error("Settled odds backfill failed", ex);
                return this.syncRunService.failAsync(run.id, ex.getMessage());
            }).eventually(() -> {
                this.running.set(false);
                return Uni.createFrom().voidItem();
            }).invoke(result -> LOG.infof("Settled odds backfill finished: %s (requests=%d)", run.id, result.requestCount())).replaceWith(run));
        });
    }

    public ProviderSyncRunEntity startBackgroundBackfillSettledOdds(int limit) {
        return (ProviderSyncRunEntity)this.contextRunner.await(() -> this.startBackgroundBackfillSettledOddsAsync(limit));
    }

    private Uni<Void> assertNotTooSoonAsync(List<OddsDataProvider> providers, boolean force) {
        if (force) {
            return Uni.createFrom().voidItem();
        }
        Uni chain = Uni.createFrom().voidItem();
        for (OddsDataProvider provider : providers) {
            String catalogName = provider.catalogName();
            chain = chain.chain(() -> this.syncRunRepository.findLatest(catalogName).invoke(latest -> latest.filter(run -> run.finishedAt != null).filter(run -> "succeeded".equals(run.status) || "partial".equals(run.status)).ifPresent(run -> {
                LocalDateTime threshold = LocalDateTime.now().minusMinutes(this.config.minMinutesBetweenRuns());
                if (run.finishedAt.isAfter(threshold)) {
                    throw new IllegalStateException("Son basarili toplama " + String.valueOf(run.finishedAt) + " \u2014 " + this.config.minMinutesBetweenRuns() + " dk bekle veya ?force=true kullan.");
                }
            })).replaceWithVoid());
        }
        return chain;
    }

    private Uni<Void> releaseStaleRunsReactive(String catalogName) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(this.config.staleRunMinutes());
        return this.syncRunRepository.failStaleRuns(catalogName, threshold).invoke(updated -> {
            if (updated > 0) {
                LOG.warnf("Marked %d stale collector runs as failed for %s", updated, catalogName);
            }
        }).replaceWithVoid();
    }

    private Uni<CollectionResult> collect(OddsDataProvider provider) {
        if (!provider.configured()) {
            return Uni.createFrom().failure((Throwable)new IllegalStateException("Odds provider is not configured: " + provider.id()));
        }
        boolean nesine = "nesine".equals(provider.id());
        if (nesine) {
            this.nesineBultenService.invalidateCache();
        } else {
            this.oddsApiKeyPool.beginRun();
            try {
                this.bookmakerValidator.beginRun(this.config.bookmakers());
            }
            catch (IllegalStateException ex) {
                return Uni.createFrom().failure((Throwable)ex);
            }
        }
        RequestBudget budget = new RequestBudget(this.requestBudgetFor(provider));
        String catalogName = provider.catalogName();
        return provider.fetchEvents(EventStatus.SETTLED).invoke(ignored -> budget.consume()).chain(settledRaw -> this.fetchPending(provider, budget).map(pendingRaw -> Map.of(EventStatus.SETTLED, nesine ? this.dedupeEvents((List<JsonNode>)settledRaw) : this.filter((List<JsonNode>)settledRaw, LeagueCatalog.DEFAULT_KEYWORDS), EventStatus.PENDING, nesine ? this.dedupeEvents((List<JsonNode>)pendingRaw) : this.filter((List<JsonNode>)pendingRaw, LeagueCatalog.DEFAULT_KEYWORDS)))).chain(filtered -> this.persistEvents(catalogName, (Map<EventStatus, List<JsonNode>>)filtered)).chain(persisted -> this.warehouse.bridgePendingOddsToSettled(catalogName, persisted.settledEligibleForOddsIds()).replaceWith(persisted)).chain(persisted -> this.selectEventsForOddsAsync(catalogName, (PersistedEvents)persisted, budget).chain(selection -> {
            LOG.infof("Odds plan: mode=%s candidates=%d skipRecent=%d fetch=%d maxOddsReq=%d", new Object[]{this.config.oddsFetchMode(), selection.candidatesTotal(), selection.skippedRecent(), selection.eventIds().size(), selection.maxOddsRequests()});
            return this.fetchOdds(provider, (OddsSelection)selection, persisted.statusById(), budget).chain(outcome -> this.storeSnapshots(catalogName, outcome.payloads(), persisted.statusById()).map(snapshotsInserted -> this.toResult(catalogName, (PersistedEvents)persisted, outcome.payloads(), (int)snapshotsInserted, budget.used(), (OddsSelection)selection, outcome.guard())));
        }));
    }

    private Uni<List<JsonNode>> fetchPending(OddsDataProvider provider, RequestBudget budget) {
        if (!budget.hasRemaining()) {
            return Uni.createFrom().item(List.of());
        }
        return provider.fetchEvents(EventStatus.PENDING).invoke(ignored -> budget.consume());
    }

    private int totalRequestBudget() {
        return this.oddsApiKeyPool.totalHourlyBudget(this.config.requestBudget());
    }

    private int maxOddsRequestsPerRunTotal() {
        int keys = Math.max(1, this.oddsApiKeyPool.keyCount());
        int perKeyCap = Math.max(0, this.config.requestBudget() - this.config.reservedEventRequests());
        return Math.min(this.config.maxOddsRequestsPerRun() * keys, perKeyCap * keys);
    }

    private Uni<OddsSelection> selectEventsForOddsAsync(String catalogName, PersistedEvents persisted, RequestBudget budget) {
        if ("Nesine".equals(catalogName)) {
            List<String> pendingIds = persisted.pendingIds();
            LocalDateTime since = LocalDateTime.now().minusHours(this.config.skipOddsSnapshotWithinHours());
            return this.warehouse.findMatchIdsWithRecentSnapshot(catalogName, "hourly_pending", since, pendingIds).map(pendingWithRecent -> {
                List<String> selected = pendingIds.stream().filter(id -> !pendingWithRecent.contains(id)).toList();
                int skippedRecent = pendingIds.size() - selected.size();
                int maxOddsRequests = Math.min(1, Math.max(0, budget.remaining()));
                return new OddsSelection(pendingIds.size(), skippedRecent, 0, maxOddsRequests, selected, 0);
            });
        }
        int maxOddsTotal = this.maxOddsRequestsPerRunTotal();
        int maxEventsCandidateWindow = Math.max(this.config.oddsChunkSize(), maxOddsTotal * this.config.oddsChunkSize() * 4);
        List<String> pendingIds = persisted.pendingIds();
        LocalDateTime since = LocalDateTime.now().minusHours(this.config.skipOddsSnapshotWithinHours());
        Uni<List<String>> settledMissingUni = this.fetchSettledOdds() ? this.warehouse.findSettledIdsMissingOdds(catalogName, persisted.settledEligibleForOddsIds()).chain(settledMissing -> this.warehouse.findScoredMatchIdsMissingOdds(catalogName, maxEventsCandidateWindow).map(scoredMissing -> {
            ArrayList<String> combined = new ArrayList<>(settledMissing);
            combined.addAll(scoredMissing);
            return combined;
        })) : Uni.createFrom().item(List.of());
        return settledMissingUni.chain((List<String> settledMissingOdds) -> this.warehouse.findMatchIdsWithRecentSnapshot(catalogName, "hourly_pending", since, pendingIds).map(pendingWithRecent -> {
            ArrayList<String> ordered = new ArrayList<>();
            if (this.fetchSettledOdds()) {
                ordered.addAll(settledMissingOdds);
            }
            pendingIds.stream().filter(id -> !pendingWithRecent.contains(id)).forEach(ordered::add);
            if (this.fetchSettledOdds()) {
                persisted.settledEligibleForOddsIds().stream().filter(id -> !settledMissingOdds.contains(id)).forEach(id -> {
                    if (!ordered.contains(id)) {
                        ordered.add(id);
                    }
                });
            }
            List<String> candidates = ordered.stream().distinct().toList();
            int maxOddsRequests = Math.min(maxOddsTotal, Math.max(0, budget.remaining()));
            int maxEvents = maxOddsRequests * this.config.oddsChunkSize();
            List<String> selected = candidates.stream().limit(maxEvents).toList();
            int skippedRecent = pendingIds.size() - (int)pendingIds.stream().filter(id -> !pendingWithRecent.contains(id)).count();
            int skippedBudget = Math.max(0, candidates.size() - selected.size());
            return new OddsSelection(candidates.size(), skippedRecent, skippedBudget, maxOddsRequests, selected, settledMissingOdds.size());
        }));
    }

    private Uni<CollectionResult> backfillSettledOdds(OddsDataProvider provider, int limit) {
        if (!provider.configured()) {
            return Uni.createFrom().failure((Throwable)new IllegalStateException("Odds provider API key is not configured"));
        }
        String catalogName = provider.catalogName();
        this.oddsApiKeyPool.beginRun();
        try {
            this.bookmakerValidator.beginRun(this.config.bookmakers());
        }
        catch (IllegalStateException ex) {
            return Uni.createFrom().failure((Throwable)ex);
        }
        RequestBudget budget = new RequestBudget(this.totalRequestBudget());
        int target = Math.max(1, limit);
        return this.warehouse.findScoredMatchIdsMissingOdds(catalogName, target).chain(missing -> {
            int maxOddsRequests = Math.min(this.maxOddsRequestsPerRunTotal(), Math.max(0, budget.remaining()));
            int maxEvents = maxOddsRequests * this.config.oddsChunkSize();
            List<String> selected = missing.stream().limit(maxEvents).toList();
            HashMap<String, String> statusById = new HashMap<String, String>();
            selected.forEach(id -> statusById.put((String)id, "settled"));
            OddsSelection selection = new OddsSelection(missing.size(), 0, Math.max(0, missing.size() - selected.size()), maxOddsRequests, selected, selected.size());
            return this.fetchOdds(provider, selection, statusById, budget).chain(outcome -> this.storeSnapshots(catalogName, outcome.payloads(), statusById).map(inserted -> {
                LinkedHashMap<String, Object> optimization = new LinkedHashMap<String, Object>();
                optimization.put("odds_fetch_mode", "backfill_settled");
                optimization.put("max_odds_requests_per_run", this.maxOddsRequestsPerRunTotal());
                optimization.put("api_key_count", this.oddsApiKeyPool.keyCount());
                optimization.put("total_request_budget", this.totalRequestBudget());
                optimization.put("odds_candidates", selection.candidatesTotal());
                optimization.put("odds_skipped_recent", 0);
                optimization.put("odds_skipped_budget", selection.skippedBudget());
                optimization.put("odds_events_fetched", selection.eventIds().size());
                optimization.put("settled_missing_odds_priority", selection.settledMissingOdds());
                optimization.put("reserved_event_requests", 0);
                optimization.put("resolved_bookmakers", this.bookmakerValidator.activeBookmakers());
                optimization.put("odds_bad_request_chunks", outcome.guard().badRequestChunks());
                optimization.put("odds_fetch_aborted", outcome.guard().aborted());
                return new CollectionResult(budget.used(), selected.size(), selected.size(), 0, outcome.payloads().size(), (int)inserted, 0, List.of(), (Map<String, Object>)optimization, List.of());
            }));
        });
    }

    private boolean fetchSettledOdds() {
        return "all".equalsIgnoreCase(this.config.oddsFetchMode());
    }

    private Uni<PersistedEvents> persistEvents(String catalogName, Map<EventStatus, List<JsonNode>> filtered) {
        List<JsonNode> settled = filtered.getOrDefault(EventStatus.SETTLED, List.of());
        List<JsonNode> pending = filtered.getOrDefault(EventStatus.PENDING, List.of());
        EventPersistState state = new EventPersistState();
        Uni settledUni = Multi.createFrom().iterable(settled).onItem().transformToUniAndConcatenate(event -> this.persistSettledEvent(catalogName, (JsonNode)event, state)).collect().last().replaceWithVoid();
        return settledUni.chain(() -> Multi.createFrom().iterable((Iterable)pending).onItem().transformToUniAndConcatenate(event -> this.persistPendingEvent(catalogName, (JsonNode)event, state)).collect().last().replaceWithVoid()).replaceWith(state.toPersistedEvents(settled, pending));
    }

    private Uni<Void> persistSettledEvent(String catalogName, JsonNode event, EventPersistState state) {
        state.incrementLeague(this.eventParser.leagueLabel(event), true);
        return this.warehouse.upsertProviderEvent(this.eventParser.toProviderEvent(event, catalogName)).chain(() -> {
            Optional<EventParser.MatchBundle> bundle = this.eventParser.toSettledMatch(event, catalogName);
            if (bundle.isEmpty()) {
                return Uni.createFrom().voidItem();
            }
            state.recordSettledMatch(event.get("id").asText());
            return this.warehouse.upsertMatch(bundle.get());
        });
    }

    private Uni<Void> persistPendingEvent(String catalogName, JsonNode event, EventPersistState state) {
        state.incrementLeague(this.eventParser.leagueLabel(event), false);
        return this.warehouse.upsertProviderEvent(this.eventParser.toProviderEvent(event, catalogName));
    }

    private static void incrementLeague(Map<String, int[]> leagueCounts, String league, boolean settled) {
        int[] counts = leagueCounts.computeIfAbsent(league, ignored -> new int[2]);
        if (settled) {
            counts[0] = counts[0] + 1;
        } else {
            counts[1] = counts[1] + 1;
        }
    }

    private static List<LeagueStat> toLeagueStats(Map<String, int[]> leagueCounts) {
        return leagueCounts.entrySet().stream().map(e -> new LeagueStat((String)e.getKey(), ((int[])e.getValue())[0], ((int[])e.getValue())[1])).sorted(Comparator.comparingInt(LeagueStat::total).reversed()).toList();
    }

    private Uni<OddsFetchOutcome> fetchOdds(OddsDataProvider provider, OddsSelection selection, Map<String, String> statusById, RequestBudget budget) {
        List<String> eventIds = selection.eventIds();
        if (eventIds.isEmpty() || !budget.hasRemaining()) {
            return Uni.createFrom().item(new OddsFetchOutcome(List.of(), new OddsFetchGuard()));
        }
        OddsFetchGuard guard = new OddsFetchGuard();
        if ("nesine".equals(provider.id())) {
            budget.consume();
            return provider.fetchMultiOdds(eventIds).map(payloads -> new OddsFetchOutcome((List<JsonNode>)payloads, guard));
        }
        int chunkSize = this.config.oddsChunkSize();
        List<List<String>> chunks = CollectorService.partition(eventIds, chunkSize);
        return Multi.createFrom().iterable(chunks).onItem().transformToUni(chunk -> {
            if (guard.shouldAbort() || !budget.hasRemaining()) {
                return Uni.createFrom().item(List.of());
            }
            return this.fetchOddsWithRetry(provider, (List<String>)chunk, budget, this.config.oddsRetryAttemptsOn429(), guard).onFailure(ex -> this.isNotFound((Throwable)ex) && !this.isBadRequest((Throwable)ex)).recoverWithItem(ex -> {
                LOG.warnf("Skipping odds chunk due to client error. chunk=%s error=%s", String.join((CharSequence)",", chunk), ex.getMessage());
                return List.of();
            });
        }).concatenate().collect().asList().map(nested -> {
            @SuppressWarnings("unchecked")
            List<List<JsonNode>> batches = (List<List<JsonNode>>) (List<?>) nested;
            return new OddsFetchOutcome(batches.stream().flatMap(Collection::stream).toList(), guard);
        });
    }

    private Uni<List<JsonNode>> fetchOddsWithRetry(OddsDataProvider provider, List<String> chunk, RequestBudget budget, int retryAttemptsRemaining, OddsFetchGuard guard) {
        if (!budget.hasRemaining()) {
            return Uni.createFrom().item(List.of());
        }
        budget.consume();
        Uni<List<JsonNode>> fetch = chunk.size() == 1 ? provider.fetchOdds(chunk.getFirst()).map(List::of) : provider.fetchMultiOdds(chunk);
        return fetch.onFailure(ex -> this.isRetriableOddsError(ex)).recoverWithUni(ex -> {
            if (retryAttemptsRemaining <= 0) {
                return Uni.createFrom().failure(ex);
            }
            Duration delay = Duration.ofSeconds(this.config.oddsRetryDelaySecondsOn429());
            return Uni.createFrom().voidItem().onItem().delayIt().by(delay).chain(() -> this.fetchOddsWithRetry(provider, chunk, budget, retryAttemptsRemaining - 1, guard));
        }).onFailure(this::isQuotaExhausted).recoverWithItem(ex -> {
            guard.recordQuotaExhausted((Throwable)ex);
            return List.of();
        }).onFailure(this::isBadRequest).recoverWithUni(ex -> {
            if (chunk.size() > 1) {
                LOG.warnf("odds/multi bad request for %d ids; retrying one-by-one. chunk=%s", chunk.size(), String.join((CharSequence)",", chunk));
                return this.fetchChunkIndividually(provider, chunk, budget, guard);
            }
            LOG.warnf("Skipping odds for invalid eventId: %s (%s)", chunk.getFirst(), ex.getMessage());
            return Uni.createFrom().item(List.of());
        }).onFailure(ex -> chunk.size() > 1 && this.isNotFound((Throwable)ex)).recoverWithUni(ex -> {
            LOG.warnf("odds/multi returned not-found for %d ids; retrying one-by-one. chunk=%s", chunk.size(), String.join((CharSequence)",", chunk));
            return this.fetchChunkIndividually(provider, chunk, budget, guard);
        });
    }

    private boolean isRetriableOddsError(Throwable ex) {
        if (OddsApiErrors.isQuotaExceeded(ex) || OddsApiErrors.shouldFailoverToNextKey(ex)) {
            return false;
        }
        if (ex == null) {
            return false;
        }
        String msg = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("connection was closed") || msg.contains("connection reset") || msg.contains("connection refused") || msg.contains("read timed out") || msg.contains("timeout");
    }

    private boolean isQuotaExhausted(Throwable ex) {
        return OddsApiErrors.isQuotaExceeded(ex) || OddsApiErrors.shouldFailoverToNextKey(ex);
    }

    private boolean isBadRequest(Throwable ex) {
        if (ex == null) {
            return false;
        }
        String msg = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("400") || msg.contains("bad request");
    }

    private boolean isNotFound(Throwable ex) {
        if (ex == null) {
            return false;
        }
        String msg = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("404") || msg.contains("not found");
    }

    private String normalizeFailureMessage(Throwable ex) {
        String msg;
        String string = msg = ex == null ? "unknown error" : String.valueOf(ex.getMessage());
        if (OddsApiErrors.isQuotaExceeded(ex)) {
            return "api_quota_exceeded: " + msg;
        }
        if (this.isRateLimited(ex)) {
            return "api_rate_limited: " + msg;
        }
        return msg;
    }

    private boolean isRateLimited(Throwable ex) {
        WebApplicationException webEx;
        if (ex == null) {
            return false;
        }
        String msg = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        if (msg.contains("429") || msg.contains("too many requests")) {
            return true;
        }
        if (ex instanceof WebApplicationException && (webEx = (WebApplicationException)ex).getResponse() != null) {
            return webEx.getResponse().getStatus() == 429;
        }
        return false;
    }

    private Uni<List<JsonNode>> fetchChunkIndividually(OddsDataProvider provider, List<String> chunk, RequestBudget budget, OddsFetchGuard guard) {
        return Multi.createFrom().iterable(chunk).onItem().transformToUniAndConcatenate(eventId -> {
            if (guard.shouldAbort() || !budget.hasRemaining()) {
                return Uni.createFrom().item(List.of());
            }
            return this.fetchOddsWithRetry(provider, List.of(eventId), budget, this.config.oddsRetryAttemptsOn429(), guard).onFailure(ex -> this.isNotFound((Throwable)ex) || this.isBadRequest((Throwable)ex)).recoverWithItem(ex -> {
                LOG.warnf("Skipping invalid eventId for odds fetch: %s (%s)", eventId, ex.getMessage());
                return List.of();
            });
        }).collect().asList().map(nested -> {
            @SuppressWarnings("unchecked")
            List<List<JsonNode>> batches = (List<List<JsonNode>>) (List<?>) nested;
            return batches.stream().flatMap(Collection::stream).toList();
        });
    }

    private Uni<Integer> storeSnapshots(String catalogName, List<JsonNode> oddsPayloads, Map<String, String> statusById) {
        LocalDateTime collectedAt = LocalDateTime.now();
        List<OddsSnapshotEntity> snapshots = new ArrayList<OddsSnapshotEntity>();
        for (JsonNode payload : oddsPayloads) {
            String eventId = payload.path("id").asText(payload.path("eventId").asText(""));
            String snapshotType = "settled".equals(statusById.get(eventId)) ? "hourly_settled" : "hourly_pending";
            snapshots.addAll(this.oddsNormalizer.normalize(payload, collectedAt, snapshotType, catalogName));
        }
        snapshots = snapshots.stream()
                .filter(s -> !Markets.HTFT.equals(s.market) || Markets.htftOddsFromProvider(catalogName))
                .toList();
        if (LeagueCatalog.NESINE_CATALOG.equals(catalogName)) {
            snapshots = snapshots.stream().filter(this::isNesineCandidateSnapshot).toList();
        }
        return this.warehouse.insertSnapshots(snapshots);
    }

    private boolean isNesineCandidateSnapshot(OddsSnapshotEntity snapshot) {
        return "HTFT".equals(snapshot.market) && Markets.CANDIDATE_OUTCOMES.get("HTFT").contains(snapshot.outcome);
    }

    private CollectionResult toResult(String catalogName, PersistedEvents persisted, List<JsonNode> oddsPayloads, int snapshotsInserted, int requestCount, OddsSelection selection, OddsFetchGuard guard) {
        int settled = persisted.settledIds().size();
        int pending = persisted.pendingIds().size();
        LinkedHashMap<String, Object> optimization = new LinkedHashMap<String, Object>();
        optimization.put("odds_fetch_mode", this.config.oddsFetchMode());
        optimization.put("max_odds_requests_per_run", this.maxOddsRequestsPerRunTotal());
        optimization.put("api_key_count", this.oddsApiKeyPool.keyCount());
        optimization.put("total_request_budget", this.totalRequestBudget());
        optimization.put("usable_api_keys", this.oddsApiKeyPool.usableKeyCount());
        optimization.put("odds_candidates", selection.candidatesTotal());
        optimization.put("odds_skipped_recent", selection.skippedRecent());
        optimization.put("odds_skipped_budget", selection.skippedBudget());
        optimization.put("odds_events_fetched", selection.eventIds().size());
        optimization.put("settled_missing_odds_priority", selection.settledMissingOdds());
        optimization.put("reserved_event_requests", this.config.reservedEventRequests());
        optimization.put("resolved_bookmakers", this.bookmakerValidator.activeBookmakers());
        optimization.put("odds_bad_request_chunks", guard.badRequestChunks());
        optimization.put("odds_fetch_aborted", guard.aborted());
        optimization.put("odds_quota_exhausted", guard.quotaExhausted());
        if (LeagueCatalog.NESINE_CATALOG.equals(catalogName)) {
            optimization.put("htft_source", "Nesine bulten HT/FT (1/2, 2/1, 1/X, 2/X)");
        } else {
            optimization.put("markets", "FIRST_HALF_BTTS, FIRST_HALF_1X2, FIRST_HALF_KG_TARAF");
        }
        return new CollectionResult(requestCount, settled + pending, settled, pending, oddsPayloads.size(), snapshotsInserted, persisted.matchesInserted(), persisted.leagueStats(), optimization, List.of());
    }

    private List<JsonNode> dedupeEvents(List<JsonNode> events) {
        ArrayList<JsonNode> deduped = new ArrayList<JsonNode>();
        HashSet<String> seen = new HashSet<String>();
        for (JsonNode event : events) {
            String id = event.path("id").asText("");
            if (id.isBlank() || !seen.add(id)) continue;
            deduped.add(event);
        }
        return deduped;
    }

    private List<JsonNode> filter(List<JsonNode> events, List<String> keywords) {
        ArrayList<JsonNode> filtered = new ArrayList<JsonNode>();
        HashSet<String> seen = new HashSet<String>();
        for (JsonNode event : events) {
            String id;
            if (!this.eventParser.isTargetLeague(event, keywords) || !seen.add(id = event.get("id").asText())) continue;
            filtered.add(event);
        }
        return filtered;
    }

    private static List<List<String>> partition(List<String> items, int size) {
        ArrayList<List<String>> chunks = new ArrayList<List<String>>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return chunks;
    }

    private static final class EventPersistState {
        private int matchesInserted;
        private final Map<String, int[]> leagueCounts = new TreeMap<String, int[]>();
        private final Set<String> settledEligibleForOdds = new HashSet<String>();

        private EventPersistState() {
        }

        void incrementLeague(String league, boolean settled) {
            CollectorService.incrementLeague(this.leagueCounts, league, settled);
        }

        void recordSettledMatch(String eventId) {
            ++this.matchesInserted;
            this.settledEligibleForOdds.add(eventId);
        }

        PersistedEvents toPersistedEvents(List<JsonNode> settled, List<JsonNode> pending) {
            HashMap<String, String> statusById = new HashMap<String, String>();
            List<String> settledIds = settled.stream().map(e -> e.get("id").asText()).toList();
            List<String> pendingIds = pending.stream().map(e -> e.get("id").asText()).toList();
            settledIds.forEach(id -> statusById.put((String)id, "settled"));
            pendingIds.forEach(id -> statusById.put((String)id, "pending"));
            return new PersistedEvents(settledIds, List.copyOf(this.settledEligibleForOdds), pendingIds, statusById, this.matchesInserted, CollectorService.toLeagueStats(this.leagueCounts));
        }
    }

    private record OddsSelection(int candidatesTotal, int skippedRecent, int skippedBudget, int maxOddsRequests, List<String> eventIds, int settledMissingOdds) {
    }

    private record OddsFetchOutcome(List<JsonNode> payloads, OddsFetchGuard guard) {
    }

    private static final class OddsFetchGuard {
        private final AtomicInteger badRequestChunks = new AtomicInteger(0);
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private final AtomicBoolean quotaExhausted = new AtomicBoolean(false);

        private OddsFetchGuard() {
        }

        boolean shouldAbort() {
            return this.aborted.get() || this.quotaExhausted.get();
        }

        void recordBadRequest(int chunkSize, Throwable ex) {
            int count = this.badRequestChunks.incrementAndGet();
            LOG.errorf("Odds API bad request (%d/%d) for chunk of %d events \u2014 not retrying one-by-one. error=%s", new Object[]{count, 1, chunkSize, ex.getMessage()});
            if (count >= 1) {
                this.aborted.set(true);
                LOG.errorf("Aborting remaining odds fetches after %d bad-request chunk(s). Check ODDS_API_IO_BOOKMAKERS.", count);
            }
        }

        int badRequestChunks() {
            return this.badRequestChunks.get();
        }

        boolean aborted() {
            return this.aborted.get();
        }

        boolean quotaExhausted() {
            return this.quotaExhausted.get();
        }

        void recordQuotaExhausted(Throwable ex) {
            this.quotaExhausted.set(true);
            this.aborted.set(true);
            LOG.warnf("All API keys exhausted; saving odds fetched so far. error=%s", ex.getMessage());
        }
    }
}

