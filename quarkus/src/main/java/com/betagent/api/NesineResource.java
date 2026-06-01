/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Uni
 *  jakarta.inject.Inject
 *  jakarta.ws.rs.DefaultValue
 *  jakarta.ws.rs.GET
 *  jakarta.ws.rs.POST
 *  jakarta.ws.rs.Path
 *  jakarta.ws.rs.Produces
 *  jakarta.ws.rs.QueryParam
 */
package com.betagent.api;

import com.betagent.provider.nesine.NesineAdapter;
import com.betagent.provider.nesine.NesineBultenService;
import com.betagent.service.NesineScoreSettlementService;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@Path(value="/api/nesine")
@Produces(value={"application/json"})
public class NesineResource {
    @Inject
    NesineBultenService bultenService;
    @Inject
    NesineAdapter adapter;
    @Inject
    NesineScoreSettlementService scoreSettlementService;

    @POST
    @Path(value="/scores/reconcile")
    public Uni<Map<String, Object>> reconcileScores() {
        return this.scoreSettlementService.reconcileFromOddsApi().map(result -> result);
    }

    @POST
    @Path(value="/scores/sync")
    public Uni<Map<String, Object>> syncScores() {
        return this.scoreSettlementService.syncScores(true, "manual").map(NesineScoreSettlementService.SettlementResult::toMap);
    }

    @POST
    @Path(value="/scores/repair")
    public Uni<Map<String, Object>> repairScores() {
        return this.scoreSettlementService.repairScoresFromLiveFeed();
    }

    @GET
    @Path(value="/htft")
    public Uni<Map<String, Object>> htft(@QueryParam(value="home") @DefaultValue(value="PSG") String home, @QueryParam(value="away") @DefaultValue(value="Arsenal") String away) {
        return this.bultenService.bulten().map(bulten -> {
            JsonNode events = bulten.path("sg").path("EA");
            String homeKey = home.trim().toLowerCase();
            String awayKey = away.trim().toLowerCase();
            JsonNode match = null;
            if (events.isArray()) {
                for (JsonNode event : events) {
                    String hn = event.path("HN").asText("").toLowerCase();
                    String an = event.path("AN").asText("").toLowerCase();
                    if (!hn.contains(homeKey) || !an.contains(awayKey)) continue;
                    match = event;
                    break;
                }
            }
            if (match == null) {
                return Map.of("found", false, "home", home, "away", away);
            }
            String eventId = String.valueOf(match.path("C").asLong());
            JsonNode oddsPayload = this.adapter.toOddsPayload((JsonNode)bulten, eventId);
            ArrayList<Map<String, Object>> htftLines = new ArrayList<>();
            JsonNode markets = oddsPayload.path("bookmakers").path(0).path("markets");
            if (markets.isArray()) {
                for (JsonNode market : markets) {
                    if (!"Half Time/Full Time".equals(market.path("name").asText())) continue;
                    for (JsonNode line : market.path("odds")) {
                        LinkedHashMap<String, Object> htftLine = new LinkedHashMap<>();
                        htftLine.put("outcome", line.path("label").asText());
                        htftLine.put("odds", line.path("odds").asDouble());
                        htftLines.add(htftLine);
                    }
                }
            }
            LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("found", true);
            payload.put("event_id", eventId);
            payload.put("home", match.path("HN").asText());
            payload.put("away", match.path("AN").asText());
            payload.put("match", match.path("ENO").asText());
            payload.put("htft", htftLines);
            return payload;
        });
    }
}

