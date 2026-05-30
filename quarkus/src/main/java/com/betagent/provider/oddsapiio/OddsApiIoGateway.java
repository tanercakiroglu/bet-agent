/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 *  org.eclipse.microprofile.rest.client.inject.RestClient
 *  org.jboss.logging.Logger
 */
package com.betagent.provider.oddsapiio;

import com.betagent.provider.oddsapiio.OddsApiErrors;
import com.betagent.provider.oddsapiio.OddsApiIoJson;
import com.betagent.provider.oddsapiio.OddsApiKeyPool;
import com.betagent.provider.oddsapiio.client.OddsApiIoRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.Function;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OddsApiIoGateway {
    private static final Logger LOG = Logger.getLogger(OddsApiIoGateway.class);
    @Inject
    OddsApiKeyPool keyPool;
    @Inject
    @RestClient
    OddsApiIoRestClient client;

    public Uni<List<JsonNode>> events(String sport, String status, String bookmaker) {
        return this.withKeyFailover(apiKey -> this.client.events((String)apiKey, sport, status, bookmaker).map(OddsApiIoJson::asEventList));
    }

    public Uni<JsonNode> odds(String eventId, String bookmakers) {
        return this.withKeyFailover(apiKey -> this.client.odds((String)apiKey, eventId, bookmakers));
    }

    public Uni<List<JsonNode>> multiOdds(String eventIds, String bookmakers) {
        return this.withKeyFailover(apiKey -> this.client.multiOdds((String)apiKey, eventIds, bookmakers).map(OddsApiIoJson::asOddsPayloadList));
    }

    private <T> Uni<T> withKeyFailover(Function<String, Uni<T>> call) {
        return this.attemptWithFailover(call);
    }

    private <T> Uni<T> attemptWithFailover(Function<String, Uni<T>> call) {
        return call.apply(this.keyPool.activeKey()).onFailure(ex -> OddsApiErrors.shouldFailoverToNextKey(ex) && this.keyPool.canFailover()).recoverWithUni(ex -> {
            LOG.warnf("Odds-API.io hourly quota hit on key slot %d; failing over to slot %d", this.keyPool.activeSlot(), (this.keyPool.activeSlot() + 1));
            this.keyPool.markCurrentKeyExhausted();
            this.keyPool.failover();
            return this.attemptWithFailover(call);
        }).onFailure(ex -> OddsApiErrors.shouldFailoverToNextKey(ex) && !this.keyPool.canFailover()).invoke(ex -> this.keyPool.markCurrentKeyExhausted());
    }
}

