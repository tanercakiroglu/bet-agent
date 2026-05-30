/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 */
package com.betagent.provider.nesine;

import com.betagent.config.NesineConfig;
import com.betagent.persistence.repository.ProviderEventRepository;
import com.betagent.provider.EventStatus;
import com.betagent.provider.OddsDataProvider;
import com.betagent.provider.nesine.NesineAdapter;
import com.betagent.provider.nesine.NesineBultenService;
import com.betagent.provider.nesine.NesineLiveScoreService;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class NesineProvider
implements OddsDataProvider {
    public static final String ID = "nesine";
    @Inject
    NesineConfig config;
    @Inject
    NesineBultenService bultenService;
    @Inject
    NesineLiveScoreService liveScoreService;
    @Inject
    NesineAdapter adapter;
    @Inject
    ProviderEventRepository providerEventRepository;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String catalogName() {
        return "Nesine";
    }

    @Override
    public boolean configured() {
        return this.config.enabled();
    }

    @Override
    public Uni<List<JsonNode>> fetchEvents(EventStatus status) {
        if (status == EventStatus.SETTLED) {
            this.liveScoreService.invalidateCache();
            return this.providerEventRepository.list("provider", new Object[]{this.catalogName()}).chain(events -> {
                Set tracked = events.stream().map(event -> event.providerMatchId).collect(Collectors.toSet());
                return this.liveScoreService.liveScoreFeed(true).map(feed -> this.adapter.toSettledEvents((JsonNode)feed).stream().filter(event -> tracked.contains(event.path("id").asText())).toList());
            });
        }
        return this.bultenService.bulten().map(this.adapter::toPendingEvents);
    }

    @Override
    public Uni<JsonNode> fetchOdds(String eventId) {
        return this.bultenService.bulten().map(bulten -> this.adapter.toOddsPayload((JsonNode)bulten, eventId));
    }

    @Override
    public Uni<List<JsonNode>> fetchMultiOdds(List<String> eventIds) {
        if (eventIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return this.bultenService.bulten().map(bulten -> this.adapter.toOddsPayloads((JsonNode)bulten, eventIds));
    }
}

