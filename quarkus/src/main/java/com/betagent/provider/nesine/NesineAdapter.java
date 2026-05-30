/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  com.fasterxml.jackson.databind.node.ArrayNode
 *  com.fasterxml.jackson.databind.node.ObjectNode
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 */
package com.betagent.provider.nesine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class NesineAdapter {
    private static final int FOOTBALL_TYPE = 1;
    private static final String BOOKMAKER = "Nesine";
    private static final int MTID_HTFT = 5;
    private static final String[] HTFT_OUTCOMES = new String[]{"1/1", "1/X", "1/2", "X/1", "X/X", "X/2", "2/1", "2/X", "2/2"};
    private static final Set<String> CANDIDATE_HTFT_OUTCOMES = Set.of("1/2", "2/1", "1/X", "2/X");
    @Inject
    ObjectMapper mapper;

    public List<JsonNode> toPendingEvents(JsonNode bulten) {
        JsonNode events = bulten.path("sg").path("EA");
        if (!events.isArray()) {
            return List.of();
        }
        Map<Integer, String> leagues = NesineAdapter.leagueNames(bulten);
        ArrayList<JsonNode> out = new ArrayList<JsonNode>();
        for (JsonNode event : events) {
            if (event.path("TYPE").asInt(-999) != 1 || event.path("P").asInt(0) != 0 || !this.hasCandidateHtftOdds(event)) continue;
            out.add(this.toEventNode(event, leagues, "pending"));
        }
        return out;
    }

    public boolean hasCandidateHtftOdds(JsonNode event) {
        JsonNode market = NesineAdapter.findMarketByMtid(event, 5);
        if (market == null) {
            return false;
        }
        for (JsonNode oca : market.path("OCA")) {
            int index = oca.path("N").asInt() - 1;
            if (index < 0 || index >= HTFT_OUTCOMES.length || !CANDIDATE_HTFT_OUTCOMES.contains(HTFT_OUTCOMES[index]) || !(oca.path("O").asDouble(0.0) > 1.0)) continue;
            return true;
        }
        return false;
    }

    public List<JsonNode> toSettledEvents(JsonNode liveScore) {
        JsonNode rows = liveScore.path("d");
        if (!rows.isArray()) {
            return List.of();
        }
        ArrayList<JsonNode> out = new ArrayList<JsonNode>();
        for (JsonNode row : rows) {
            if (row.path("S").asInt(-1) != 4) continue;
            Optional<int[]> ht = NesineAdapter.halfTimeScore(row);
            Optional<int[]> secondHalf = NesineAdapter.secondHalfScore(row);
            if (ht.isEmpty() || secondHalf.isEmpty()) continue;
            int[] htGoals = ht.get();
            int[] shGoals = secondHalf.get();
            int fthg = htGoals[0] + shGoals[0];
            int ftag = htGoals[1] + shGoals[1];
            out.add(this.toSettledEventNode(row, htGoals[0], htGoals[1], fthg, ftag));
        }
        return out;
    }

    private JsonNode toSettledEventNode(JsonNode row, int hthg, int htag, int fthg, int ftag) {
        ObjectNode node = this.mapper.createObjectNode();
        String id = String.valueOf(row.path("C").asLong());
        node.put("id", id);
        node.put("home", NesineAdapter.text(row, "HTTR", "HT"));
        node.put("away", NesineAdapter.text(row, "ATTR", "AT"));
        node.put("status", "finished");
        node.put("date", NesineAdapter.text(row, "MD", "matchDate"));
        ObjectNode league = this.mapper.createObjectNode();
        String leagueName = row.path("L").asText("Football");
        league.put("name", leagueName);
        league.put("slug", NesineAdapter.slugify(leagueName));
        node.set("league", (JsonNode)league);
        ObjectNode sport = this.mapper.createObjectNode();
        sport.put("slug", "football");
        node.set("sport", (JsonNode)sport);
        ObjectNode scores = this.mapper.createObjectNode();
        ObjectNode halftime = this.mapper.createObjectNode();
        halftime.put("home", hthg);
        halftime.put("away", htag);
        ObjectNode fulltime = this.mapper.createObjectNode();
        fulltime.put("home", fthg);
        fulltime.put("away", ftag);
        scores.set("halftime", (JsonNode)halftime);
        scores.set("fulltime", (JsonNode)fulltime);
        node.set("scores", (JsonNode)scores);
        return node;
    }

    private static Optional<int[]> halfTimeScore(JsonNode row) {
        return NesineAdapter.scoreFromEs(row, 19);
    }

    private static Optional<int[]> secondHalfScore(JsonNode row) {
        return NesineAdapter.scoreFromEs(row, 2);
    }

    private static Optional<int[]> scoreFromEs(JsonNode row, int periodType) {
        for (JsonNode period : row.path("ES")) {
            if (period.path("T").asInt(-1) != periodType) continue;
            return Optional.of(new int[]{period.path("H").asInt(), period.path("A").asInt()});
        }
        return Optional.empty();
    }

    public JsonNode toOddsPayload(JsonNode bulten, String eventId) {
        JsonNode raw = NesineAdapter.findRawEvent(bulten, eventId);
        if (raw == null) {
            return this.mapper.createObjectNode();
        }
        ObjectNode root = this.mapper.createObjectNode();
        root.put("id", eventId);
        ArrayNode bookmakers = this.mapper.createArrayNode();
        ObjectNode bookmaker = this.mapper.createObjectNode();
        bookmaker.put("name", BOOKMAKER);
        bookmaker.set("markets", (JsonNode)this.buildMarkets(raw));
        bookmakers.add((JsonNode)bookmaker);
        root.set("bookmakers", (JsonNode)bookmakers);
        return root;
    }

    public List<JsonNode> toOddsPayloads(JsonNode bulten, List<String> eventIds) {
        ArrayList<JsonNode> payloads = new ArrayList<JsonNode>();
        for (String eventId : eventIds) {
            JsonNode payload = this.toOddsPayload(bulten, eventId);
            if (!payload.has("bookmakers") || payload.get("bookmakers").size() <= 0) continue;
            payloads.add(payload);
        }
        return payloads;
    }

    private JsonNode toEventNode(JsonNode event, Map<Integer, String> leagues, String status) {
        ObjectNode node = this.mapper.createObjectNode();
        String id = String.valueOf(event.path("C").asLong());
        node.put("id", id);
        node.put("home", NesineAdapter.text(event, "HN", "home"));
        node.put("away", NesineAdapter.text(event, "AN", "away"));
        node.put("status", status);
        node.put("date", NesineAdapter.formatDate(event.path("ESD").asLong(0L)));
        ObjectNode league = this.mapper.createObjectNode();
        int lc = event.path("LC").asInt();
        String leagueName = leagues.getOrDefault(lc, "Football");
        league.put("name", leagueName);
        league.put("slug", NesineAdapter.slugify(leagueName));
        node.set("league", (JsonNode)league);
        ObjectNode sport = this.mapper.createObjectNode();
        sport.put("slug", "football");
        node.set("sport", (JsonNode)sport);
        return node;
    }

    private ArrayNode buildMarkets(JsonNode event) {
        ArrayNode markets = this.mapper.createArrayNode();
        this.appendMarketIfPresent(markets, this.extractHtft(event));
        return markets;
    }

    private void appendMarketIfPresent(ArrayNode markets, ObjectNode market) {
        if (market != null) {
            markets.add((JsonNode)market);
        }
    }

    private ObjectNode extractHtft(JsonNode event) {
        JsonNode market = NesineAdapter.findMarketByMtid(event, 5);
        if (market == null) {
            return null;
        }
        ArrayNode odds = this.mapper.createArrayNode();
        for (JsonNode oca : market.path("OCA")) {
            String outcome;
            int index = oca.path("N").asInt() - 1;
            if (index < 0 || index >= HTFT_OUTCOMES.length || !CANDIDATE_HTFT_OUTCOMES.contains(outcome = HTFT_OUTCOMES[index])) continue;
            ObjectNode line = this.mapper.createObjectNode();
            line.put("label", outcome);
            double price = oca.path("O").asDouble(0.0);
            if (price <= 1.0) continue;
            line.put("odds", price);
            odds.add((JsonNode)line);
        }
        if (odds.isEmpty()) {
            return null;
        }
        ObjectNode node = this.mapper.createObjectNode();
        node.put("name", "Half Time/Full Time");
        node.set("odds", (JsonNode)odds);
        return node;
    }

    private static JsonNode findMarketByMtid(JsonNode event, int mtid) {
        for (JsonNode market : NesineAdapter.allMarkets(event)) {
            if (market.path("MTID").asInt() != mtid) continue;
            return market;
        }
        return null;
    }

    private static Iterable<JsonNode> allMarkets(JsonNode event) {
        JsonNode msa;
        ArrayList<JsonNode> markets = new ArrayList<JsonNode>();
        JsonNode ma = event.get("MA");
        if (ma != null && ma.isArray()) {
            ma.forEach(markets::add);
        }
        if ((msa = event.get("MSA")) != null && msa.isArray()) {
            msa.forEach(markets::add);
        }
        return markets;
    }

    private static JsonNode findRawEvent(JsonNode bulten, String eventId) {
        JsonNode events = bulten.path("sg").path("EA");
        if (!events.isArray()) {
            return null;
        }
        for (JsonNode event : events) {
            if (!String.valueOf(event.path("C").asLong()).equals(eventId)) continue;
            return event;
        }
        return null;
    }

    private static Map<Integer, String> leagueNames(JsonNode bulten) {
        HashMap<Integer, String> map = new HashMap<Integer, String>();
        JsonNode la = bulten.path("sg").path("LA");
        if (!la.isArray()) {
            return map;
        }
        for (JsonNode row : la) {
            map.put(row.path("LID").asInt(), row.path("N").asText(""));
        }
        return map;
    }

    private static String slugify(String value) {
        if (value == null || value.isBlank()) {
            return "football";
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('\u0131', 'i').replace('\u0130', 'i').replace('\u015f', 's').replace('\u015e', 's').replace('\u011f', 'g').replace('\u011e', 'g').replace('\u00fc', 'u').replace('\u00dc', 'u').replace('\u00f6', 'o').replace('\u00d6', 'o').replace('\u00e7', 'c').replace('\u00c7', 'c');
        return normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String text(JsonNode node, String ... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || value.asText("").isBlank()) continue;
            return value.asText();
        }
        return "";
    }

    private static String formatDate(long epochMillis) {
        if (epochMillis <= 0L) {
            return "";
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC));
    }
}

