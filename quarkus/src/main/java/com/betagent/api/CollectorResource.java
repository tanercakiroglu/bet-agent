/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.smallrye.mutiny.Multi
 *  io.smallrye.mutiny.Uni
 *  jakarta.inject.Inject
 *  jakarta.ws.rs.Consumes
 *  jakarta.ws.rs.DefaultValue
 *  jakarta.ws.rs.GET
 *  jakarta.ws.rs.POST
 *  jakarta.ws.rs.PUT
 *  jakarta.ws.rs.Path
 *  jakarta.ws.rs.Produces
 *  jakarta.ws.rs.QueryParam
 *  jakarta.ws.rs.core.Response
 *  jakarta.ws.rs.core.Response$Status
 */
package com.betagent.api;

import com.betagent.config.NesineConfig;
import com.betagent.config.OddsApiConfig;
import com.betagent.domain.LeagueCatalog;
import com.betagent.persistence.repository.ProviderSyncRunRepository;
import com.betagent.provider.OddsDataProvider;
import com.betagent.provider.OddsProviderRegistry;
import com.betagent.provider.oddsapiio.OddsApiKeyPool;
import com.betagent.service.CollectorService;
import com.betagent.service.JobHistoryService;
import com.betagent.service.PredictionSettingsService;
import com.betagent.service.SharpPredictionService;
import com.betagent.service.WarehouseService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path(value="/api")
@Produces(value={"application/json"})
public class CollectorResource {
    @Inject
    CollectorService collectorService;
    @Inject
    WarehouseService warehouseService;
    @Inject
    ProviderSyncRunRepository syncRunRepository;
    @Inject
    OddsProviderRegistry providerRegistry;
    @Inject
    OddsApiConfig oddsApiConfig;
    @Inject
    SharpPredictionService sharpPredictionService;
    @Inject
    PredictionSettingsService predictionSettingsService;
    @Inject
    JobHistoryService jobHistoryService;
    @Inject
    OddsApiKeyPool oddsApiKeyPool;
    @Inject
    NesineConfig nesineConfig;

    @GET
    @Path(value="/health")
    public Uni<Map<String, String>> health() {
        return Uni.createFrom().item(Map.of("status", "ok"));
    }

    @GET
    @Path(value="/dashboard")
    public Uni<Map<String, Object>> dashboard() {
        List<OddsDataProvider> providers = this.providerRegistry.configuredProviders();
        List<String> catalogs = this.providerRegistry.configuredCatalogNames();
        OddsDataProvider primary = this.providerRegistry.active();
        List<Map<String, Object>> providerInfos = this.buildProviderInfos(providers);
        Map<String, Object> providerInfo = this.buildPrimaryProviderInfo(primary, providers);
        return Multi.createFrom().iterable(catalogs).onItem().transformToUniAndConcatenate(this.warehouseService::dashboard).collect().asList().chain(parts -> this.warehouseService.aggregatedDataQuality(catalogs).chain(quality -> this.warehouseService.countMatchesMissingScores(catalogs).chain(missingCount -> this.warehouseService.listMatchesMissingScores(catalogs, 80).chain(missingScores -> this.syncRunRepository.findLatestAcross(catalogs).chain(latest -> this.predictionSettingsService.resolve(primary.catalogName()).chain(PredictionSettingsService::toMap).chain(settings -> this.jobHistoryService.latestRepairSummary().chain(repairSummary -> this.isAnyCollectorRunActive(catalogs).map(running -> {
            long totalMatches = parts.stream().mapToLong(part -> ((Number)part.getOrDefault("matches", 0L)).longValue()).sum();
            long totalHourlySnapshots = parts.stream().mapToLong(part -> ((Number)part.getOrDefault("hourly_snapshots", 0L)).longValue()).sum();
            long totalOddsSnapshots = parts.stream().mapToLong(part -> ((Number)part.getOrDefault("odds_snapshots", 0L)).longValue()).sum();
            HashMap<String, Object> payload = new HashMap<String, Object>();
            payload.put("matches", totalMatches);
            payload.put("hourly_snapshots", totalHourlySnapshots);
            payload.put("odds_snapshots", totalOddsSnapshots);
            payload.put("data_quality", quality);
            payload.put("missing_scores_count", missingCount);
            payload.put("missing_scores", missingScores);
            payload.put("nesine_score_repair", repairSummary);
            payload.put("provider", providerInfo);
            payload.put("providers", providerInfos);
            payload.put("collector_running", running);
            payload.put("collector", latest.orElse(null));
            payload.put("prediction_settings", settings);
            return payload;
        }))))))));
    }

    @POST
    @Path(value="/collector/run")
    public Uni<Response> runCollector(@QueryParam(value="force") @DefaultValue(value="false") boolean force) {
        return this.collectorService.startBackgroundCollectAsync(force).map(run -> Response.accepted(Map.of("status", "started", "run_id", run.id, "force", force)).build()).onFailure(IllegalStateException.class).recoverWithItem(ex -> Response.status((int)409).entity(Map.of("status", "rejected", "message", ex.getMessage())).build());
    }

    @GET
    @Path(value="/collector/history")
    public Uni<Map<String, Object>> collectorHistory(@QueryParam(value="limit") @DefaultValue(value="30") int limit) {
        return this.jobHistoryService.list(limit).map(items -> Map.of("items", items));
    }

    @GET
    @Path(value="/collector/status")
    public Uni<Map<String, Object>> collectorStatus() {
        OddsDataProvider provider = this.providerRegistry.active();
        return this.syncRunRepository.findLatest(provider.catalogName()).chain(latest -> this.isAnyCollectorRunActive(List.of(provider.catalogName())).map(running -> Map.of("provider_id", provider.id(), "enabled", this.oddsApiConfig.collectionEnabled(), "interval", this.oddsApiConfig.collectionInterval(), "is_running", running, "latest", latest.orElse(null))));
    }

    @POST
    @Path(value="/database/reset")
    public Uni<Map<String, String>> resetDatabase() {
        List<Uni<Void>> resets = this.providerRegistry.configuredProviders().stream().map(provider -> this.warehouseService.resetProviderData(provider.catalogName())).toList();
        if (resets.isEmpty()) {
            return Uni.createFrom().item(Map.of("status", "ok", "message", "Temizlenecek provider yok."));
        }
        return Uni.combine().all().unis(resets).discardItems().replaceWith(Map.of("status", "ok", "message", "Tum provider verileri temizlendi."));
    }

    @GET
    @Path(value="/bootstrap")
    public Uni<Map<String, Object>> bootstrap() {
        return this.dashboard().chain(dashboard -> this.jobHistoryService.list(30).chain(history -> this.warehouseService.listScores(this.providerRegistry.configuredCatalogNames(), 200).chain(scores -> this.sharpPredictionService.predict(30).map(predictions -> {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("dashboard", dashboard);
            payload.put("history", history);
            payload.put("scores", scores);
            payload.put("predictions", predictions);
            return payload;
        }))));
    }

    @GET
    @Path(value="/predictions")
    public Uni<Map<String, Object>> predictions(@QueryParam(value="limit") @DefaultValue(value="25") int limit) {
        return this.sharpPredictionService.predict(limit).map(items -> Map.of("items", items));
    }

    @GET
    @Path(value="/prediction-settings")
    public Uni<Map<String, Object>> predictionSettings() {
        OddsDataProvider provider = this.providerRegistry.active();
        return this.predictionSettingsService.resolve(provider.catalogName()).chain(PredictionSettingsService::toMap);
    }

    @PUT
    @Path(value="/prediction-settings")
    @Consumes(value={"application/json"})
    public Uni<Response> updatePredictionSettings(PredictionSettingsRequest request) {
        if (request == null || request.min_samples == null || request.min_edge == null || request.min_confidence_low == null) {
            return Uni.createFrom().item(Response.status((Response.Status)Response.Status.BAD_REQUEST).entity(Map.of("message", "min_samples, min_edge, min_confidence_low zorunlu.")).build());
        }
        if (request.min_samples < 1 || request.min_samples > 200) {
            return Uni.createFrom().item(Response.status((Response.Status)Response.Status.BAD_REQUEST).entity(Map.of("message", "min_samples 1-200 arasi olmali.")).build());
        }
        if (request.min_edge < 0.0 || request.min_edge > 1.0) {
            return Uni.createFrom().item(Response.status((Response.Status)Response.Status.BAD_REQUEST).entity(Map.of("message", "min_edge 0.00-1.00 arasi olmali.")).build());
        }
        if (request.min_confidence_low < 0.0 || request.min_confidence_low > 1.0) {
            return Uni.createFrom().item(Response.status((Response.Status)Response.Status.BAD_REQUEST).entity(Map.of("message", "min_confidence_low 0.00-1.00 arasi olmali.")).build());
        }
        OddsDataProvider provider = this.providerRegistry.active();
        return this.predictionSettingsService
                .update(
                        provider.catalogName(),
                        request.min_samples,
                        request.min_edge,
                        request.min_confidence_low,
                        request.wilson_scale_by_implied == null || request.wilson_scale_by_implied)
                .chain(PredictionSettingsService::toMap)
                .map(payload -> Response.ok(payload).build());
    }

    @POST
    @Path(value="/prediction-settings/reset")
    public Uni<Map<String, Object>> resetPredictionSettings() {
        OddsDataProvider provider = this.providerRegistry.active();
        return this.predictionSettingsService.resetToDefaults(provider.catalogName()).chain(PredictionSettingsService::toMap);
    }

    @GET
    @Path(value="/future-candidates")
    public Uni<Map<String, Object>> futureCandidates(@QueryParam(value="limit") @DefaultValue(value="25") int limit) {
        return this.sharpPredictionService.predict(limit).map(items -> Map.of("items", items));
    }

    @GET
    @Path(value="/htft-odds")
    public Uni<Map<String, Object>> htftOdds(@QueryParam(value="bookmaker") String bookmaker, @QueryParam(value="page") @DefaultValue(value="1") int page, @QueryParam(value="page_size") @DefaultValue(value="20") int pageSize, @QueryParam(value="limit") Integer limit) {
        List<String> htftCatalogs = List.of(LeagueCatalog.NESINE_CATALOG);
        if (limit != null && limit > 0) {
            return this.warehouseService.latestHtftOddsAll(htftCatalogs, limit).map(items -> Map.of("items", items));
        }
        return this.warehouseService.htftOddsPage(htftCatalogs, bookmaker, page, pageSize);
    }

    @GET
    @Path(value="/scores")
    public Uni<Map<String, Object>> scores(@QueryParam(value="limit") @DefaultValue(value="200") int limit) {
        return this.warehouseService.listScores(this.providerRegistry.configuredCatalogNames(), limit).map(items -> Map.of("items", items));
    }

    private Uni<List<Map<String, Object>>> scoreItems(int limit) {
        return this.warehouseService.listScores(this.providerRegistry.configuredCatalogNames(), limit);
    }

    private Uni<Boolean> isAnyCollectorRunActive(List<String> catalogs) {
        return Multi.createFrom().iterable(catalogs).onItem().transformToUniAndConcatenate(catalog -> this.syncRunRepository.findRunning((String)catalog).map(Optional::isPresent)).collect().asList().map(flags -> flags.stream().anyMatch(Boolean::booleanValue));
    }

    private List<Map<String, Object>> buildProviderInfos(List<OddsDataProvider> providers) {
        ArrayList<Map<String, Object>> providerInfos = new ArrayList<Map<String, Object>>();
        for (OddsDataProvider provider : providers) {
            LinkedHashMap<String, Object> info = new LinkedHashMap<String, Object>();
            info.put("id", provider.id());
            info.put("name", provider.catalogName());
            info.put("configured", provider.configured());
            if ("nesine".equals(provider.id())) {
                info.put("source", "Nesine bulten");
                info.put("markets", "HT/FT 1/2 \u00b7 2/1 \u00b7 1/X \u00b7 2/X");
            } else {
                info.put("markets", "İY BTTS + İY 1X2 → KG+Taraf");
                info.put("request_budget_per_key", this.oddsApiConfig.requestBudget());
                info.put("request_budget", this.oddsApiKeyPool.totalHourlyBudget(this.oddsApiConfig.requestBudget()));
                info.put("api_key_count", this.oddsApiKeyPool.keyCount());
                info.put("usable_api_keys", this.oddsApiKeyPool.usableKeyCount());
                info.put("max_odds_requests_per_run", this.oddsApiConfig.maxOddsRequestsPerRun() * Math.max(1, this.oddsApiKeyPool.keyCount()));
                info.put("odds_fetch_mode", this.oddsApiConfig.oddsFetchMode());
                info.put("bookmakers", this.oddsApiConfig.bookmakers());
            }
            providerInfos.add(info);
        }
        return providerInfos;
    }

    private Map<String, Object> buildPrimaryProviderInfo(OddsDataProvider primary, List<OddsDataProvider> providers) {
        LinkedHashMap<String, Object> providerInfo = new LinkedHashMap<String, Object>();
        providerInfo.put("id", primary.id());
        providerInfo.put("name", primary.catalogName());
        providerInfo.put("configured", providers.stream().anyMatch(OddsDataProvider::configured));
        providerInfo.put("enabled_providers", this.providerRegistry.enabledIds());
        providerInfo.put("request_budget_per_key", this.oddsApiConfig.requestBudget());
        providerInfo.put("request_budget", this.oddsApiKeyPool.totalHourlyBudget(this.oddsApiConfig.requestBudget()));
        providerInfo.put("api_key_count", this.oddsApiKeyPool.keyCount());
        providerInfo.put("usable_api_keys", this.oddsApiKeyPool.usableKeyCount());
        providerInfo.put("max_odds_requests_per_run", this.oddsApiConfig.maxOddsRequestsPerRun() * Math.max(1, this.oddsApiKeyPool.keyCount()));
        providerInfo.put("odds_fetch_mode", this.oddsApiConfig.oddsFetchMode());
        providerInfo.put("bookmakers", this.oddsApiConfig.bookmakers());
        providerInfo.put("league_keywords", LeagueCatalog.DEFAULT_KEYWORDS.size());
        providerInfo.put("nesine_enabled", this.nesineConfig.enabled());
        return providerInfo;
    }

    public static class PredictionSettingsRequest {
        public Integer min_samples;
        public Double min_edge;
        public Double min_confidence_low;
        public Boolean wilson_scale_by_implied;
    }
}

