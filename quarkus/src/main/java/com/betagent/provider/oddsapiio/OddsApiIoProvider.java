/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 */
package com.betagent.provider.oddsapiio;

import com.betagent.config.OddsApiConfig;
import com.betagent.provider.EventStatus;
import com.betagent.provider.OddsDataProvider;
import com.betagent.provider.oddsapiio.OddsApiBookmakerValidator;
import com.betagent.provider.oddsapiio.OddsApiIoGateway;
import com.betagent.provider.oddsapiio.OddsApiKeyPool;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class OddsApiIoProvider
implements OddsDataProvider {
    public static final String ID = "odds-api-io";
    @Inject
    OddsApiConfig config;
    @Inject
    OddsApiKeyPool keyPool;
    @Inject
    OddsApiIoGateway gateway;
    @Inject
    OddsApiBookmakerValidator bookmakerValidator;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String catalogName() {
        return "Odds-API.io";
    }

    @Override
    public boolean configured() {
        return this.keyPool.keyCount() > 0;
    }

    @Override
    public Uni<List<JsonNode>> fetchEvents(EventStatus status) {
        return this.gateway.events(this.config.sport(), status.apiValue(), this.primaryBookmaker());
    }

    @Override
    public Uni<List<JsonNode>> fetchMultiOdds(List<String> eventIds) {
        if (eventIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        String joined = String.join((CharSequence)",", eventIds);
        return this.gateway.multiOdds(joined, this.bookmakerValidator.activeBookmakers());
    }

    @Override
    public Uni<JsonNode> fetchOdds(String eventId) {
        return this.gateway.odds(eventId, this.bookmakerValidator.activeBookmakers());
    }

    private String primaryBookmaker() {
        return this.bookmakerValidator.activeBookmakers().split(",")[0].trim();
    }
}

