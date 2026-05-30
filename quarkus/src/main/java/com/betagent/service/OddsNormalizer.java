/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  jakarta.enterprise.context.ApplicationScoped
 */
package com.betagent.service;

import com.betagent.domain.LeagueCatalog;
import com.betagent.domain.Markets;
import com.betagent.persistence.entity.OddsSnapshotEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class OddsNormalizer {
    public List<OddsSnapshotEntity> normalize(JsonNode payload, LocalDateTime snapshotAt, String snapshotType, String catalogName) {
        String eventId;
        String string = eventId = payload.has("id") ? payload.get("id").asText() : payload.path("eventId").asText();
        if (eventId == null || eventId.isBlank()) {
            return List.of();
        }
        ArrayList<OddsSnapshotEntity> snapshots = new ArrayList<OddsSnapshotEntity>();
        for (Map.Entry<String, JsonNode> bookmakerEntry : this.bookmakerNodes(payload)) {
            JsonNode marketsNode;
            String bookmaker = bookmakerEntry.getKey();
            if (OddsNormalizer.shouldSkipBookmaker(bookmaker) || !(marketsNode = bookmakerEntry.getValue()).isArray()) continue;
            for (JsonNode marketNode : marketsNode) {
                JsonNode odds;
                String marketName = marketNode.path("name").asText(marketNode.path("market").asText(""));
                String canonicalMarket = OddsNormalizer.canonicalMarket(marketName);
                if (canonicalMarket == null
                        || Markets.HTFT.equals(canonicalMarket) && !LeagueCatalog.NESINE_CATALOG.equals(catalogName)
                        || (odds = marketNode.get("odds")) == null
                        || odds.isNull()) {
                    continue;
                }
                for (JsonNode odd : OddsNormalizer.oddLines(odds)) {
                    this.addOutcomes(snapshots, eventId, bookmaker, canonicalMarket, odd, snapshotAt, snapshotType, catalogName);
                }
            }
        }
        this.addSyntheticKgTaraf(snapshots, eventId, snapshotAt, snapshotType, catalogName);
        return snapshots;
    }

    private void addOutcomes(List<OddsSnapshotEntity> snapshots, String eventId, String bookmaker, String market, JsonNode odd, LocalDateTime snapshotAt, String snapshotType, String catalogName) {
        if (market.equals("FIRST_HALF_1X2")) {
            if (odd.has("label")) {
                String outcome = OddsNormalizer.sideCode(odd.get("label").asText());
                JsonNode price = OddsNormalizer.firstPriceNode(odd);
                this.putIfPresent(snapshots, eventId, bookmaker, market, outcome, price, snapshotAt, snapshotType, catalogName);
                return;
            }
            this.putIfPresent(snapshots, eventId, bookmaker, market, "1", odd.get("home"), snapshotAt, snapshotType, catalogName);
            this.putIfPresent(snapshots, eventId, bookmaker, market, "X", odd.get("draw"), snapshotAt, snapshotType, catalogName);
            this.putIfPresent(snapshots, eventId, bookmaker, market, "2", odd.get("away"), snapshotAt, snapshotType, catalogName);
            this.putIfPresent(snapshots, eventId, bookmaker, market, "1", odd.get("1"), snapshotAt, snapshotType, catalogName);
            this.putIfPresent(snapshots, eventId, bookmaker, market, "X", odd.get("x"), snapshotAt, snapshotType, catalogName);
            this.putIfPresent(snapshots, eventId, bookmaker, market, "2", odd.get("2"), snapshotAt, snapshotType, catalogName);
            return;
        }
        if (market.equals("FIRST_HALF_BTTS")) {
            this.putIfPresent(snapshots, eventId, bookmaker, market, "VAR", odd.get("yes"), snapshotAt, snapshotType, catalogName);
            this.putIfPresent(snapshots, eventId, bookmaker, market, "YOK", odd.get("no"), snapshotAt, snapshotType, catalogName);
            return;
        }
        if (market.equals("HTFT")) {
            this.addHtftOutcomes(snapshots, eventId, bookmaker, market, odd, snapshotAt, snapshotType, catalogName);
        }
    }

    private void addHtftOutcomes(List<OddsSnapshotEntity> snapshots, String eventId, String bookmaker, String market, JsonNode odd, LocalDateTime snapshotAt, String snapshotType, String catalogName) {
        if (odd.has("label")) {
            String outcome = OddsNormalizer.canonicalHtftOutcome(odd.get("label").asText());
            this.putIfPresent(snapshots, eventId, bookmaker, market, outcome, OddsNormalizer.htftPriceNode(odd), snapshotAt, snapshotType, catalogName);
            return;
        }
        if (odd.has("name")) {
            String outcome = OddsNormalizer.canonicalHtftOutcome(odd.get("name").asText());
            this.putIfPresent(snapshots, eventId, bookmaker, market, outcome, OddsNormalizer.htftPriceNode(odd), snapshotAt, snapshotType, catalogName);
            return;
        }
        String[] keys = new String[]{"11", "1X", "12", "X1", "XX", "X2", "21", "2X", "22", "home_home", "home_draw", "home_away", "draw_home", "draw_draw", "draw_away", "away_home", "away_draw", "away_away"};
        String[] outcomes = new String[]{"1/1", "1/X", "1/2", "X/1", "X/X", "X/2", "2/1", "2/X", "2/2", "1/1", "1/X", "1/2", "X/1", "X/X", "X/2", "2/1", "2/X", "2/2"};
        for (int i = 0; i < keys.length; ++i) {
            if (!odd.has(keys[i])) continue;
            this.putIfPresent(snapshots, eventId, bookmaker, market, outcomes[i], odd.get(keys[i]), snapshotAt, snapshotType, catalogName);
        }
        Iterator fieldNames = odd.fieldNames();
        while (fieldNames.hasNext()) {
            String field = (String)fieldNames.next();
            String outcome = OddsNormalizer.canonicalHtftOutcome(field);
            if (outcome == null) continue;
            this.putIfPresent(snapshots, eventId, bookmaker, market, outcome, odd.get(field), snapshotAt, snapshotType, catalogName);
        }
    }

    private void putIfPresent(List<OddsSnapshotEntity> snapshots, String eventId, String bookmaker, String market, String outcome, JsonNode value, LocalDateTime snapshotAt, String snapshotType, String catalogName) {
        if (outcome == null || value == null || value.isNull()) {
            return;
        }
        if ("HTFT".equals(market) && !Markets.CANDIDATE_OUTCOMES.get("HTFT").contains(outcome)) {
            return;
        }
        try {
            double odds = Double.parseDouble(value.asText());
            if (odds <= 0.0) {
                return;
            }
            OddsSnapshotEntity entity = new OddsSnapshotEntity();
            entity.provider = catalogName;
            entity.providerMatchId = eventId;
            entity.bookmaker = bookmaker;
            entity.market = market;
            entity.outcome = outcome;
            entity.decimalOdds = BigDecimal.valueOf(odds);
            entity.snapshotType = snapshotType;
            entity.snapshotAt = snapshotAt;
            snapshots.add(entity);
        }
        catch (NumberFormatException numberFormatException) {
            // empty catch block
        }
    }

    private void addSyntheticKgTaraf(List<OddsSnapshotEntity> snapshots, String eventId, LocalDateTime snapshotAt, String snapshotType, String catalogName) {
        HashMap<String, Map> byBookmaker = new HashMap<String, Map>();
        for (OddsSnapshotEntity oddsSnapshotEntity : snapshots) {
            if (!oddsSnapshotEntity.providerMatchId.equals(eventId)) continue;
            byBookmaker.computeIfAbsent(oddsSnapshotEntity.bookmaker, ignored -> new HashMap()).put(oddsSnapshotEntity.market + ":" + oddsSnapshotEntity.outcome, oddsSnapshotEntity.decimalOdds.doubleValue());
        }
        for (Map.Entry entry : byBookmaker.entrySet()) {
            Double bttsVar = (Double)((Map)entry.getValue()).get("FIRST_HALF_BTTS:VAR");
            if (bttsVar == null) continue;
            for (String side : List.of("1", "X", "2")) {
                Double sideOdds = (Double)((Map)entry.getValue()).get("FIRST_HALF_1X2:" + side);
                if (sideOdds == null) continue;
                OddsSnapshotEntity entity = new OddsSnapshotEntity();
                entity.provider = catalogName;
                entity.providerMatchId = eventId;
                entity.bookmaker = (String)entry.getKey();
                entity.market = "FIRST_HALF_KG_TARAF";
                entity.outcome = "KG_VAR_" + side;
                entity.decimalOdds = BigDecimal.valueOf(OddsNormalizer.round4(bttsVar * sideOdds));
                entity.snapshotType = snapshotType;
                entity.snapshotAt = snapshotAt;
                snapshots.add(entity);
            }
        }
    }

    private static boolean shouldSkipBookmaker(String bookmaker) {
        if (bookmaker == null || bookmaker.isBlank()) {
            return true;
        }
        String lower = bookmaker.toLowerCase(Locale.ROOT);
        return lower.contains("no latency") || lower.contains("bot365");
    }

    private List<Map.Entry<String, JsonNode>> bookmakerNodes(JsonNode payload) {
        JsonNode bookmakers = payload.get("bookmakers");
        if (bookmakers == null) {
            return List.of();
        }
        ArrayList<Map.Entry<String, JsonNode>> nodes = new ArrayList<Map.Entry<String, JsonNode>>();
        if (bookmakers.isObject()) {
            Iterator fields = bookmakers.fields();
            while (fields.hasNext()) {
                nodes.add((Map.Entry)fields.next());
            }
            return nodes;
        }
        if (bookmakers.isArray()) {
            for (JsonNode item : bookmakers) {
                String name = item.path("name").asText(item.path("bookmaker").asText(item.path("key").asText("unknown")));
                JsonNode markets = item.get("markets");
                if (markets == null) {
                    markets = item.get("odds");
                }
                if (markets == null) continue;
                nodes.add(Map.entry(name, markets));
            }
        }
        return nodes;
    }

    private static double round4(double value) {
        return (double)Math.round(value * 10000.0) / 10000.0;
    }

    private static String canonicalHtftOutcome(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("halftime", "half time").replace("fulltime", "full time");
        for (String separator : new String[]{"/", "-", "_", " "}) {
            String[] parts = normalized.split(Pattern.quote(separator.equals(" ") ? " " : separator));
            if (parts.length != 2) continue;
            String first = OddsNormalizer.sideCode(parts[0].trim());
            String second = OddsNormalizer.sideCode(parts[1].trim());
            if (first == null || second == null) continue;
            return first + "/" + second;
        }
        String upper = normalized.toUpperCase(Locale.ROOT).replace("-", "/");
        if (Markets.CANDIDATE_OUTCOMES.get("HTFT").contains(upper)) {
            return upper;
        }
        return null;
    }

    private static String sideCode(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1", "h", "home" -> "1";
            case "x", "d", "draw", "tie" -> "X";
            case "2", "a", "away" -> "2";
            default -> null;
        };
    }

    private static List<JsonNode> oddLines(JsonNode odds) {
        if (odds.isArray()) {
            ArrayList<JsonNode> lines = new ArrayList<JsonNode>();
            odds.forEach(lines::add);
            return lines;
        }
        return List.of(odds);
    }

    private static JsonNode htftPriceNode(JsonNode odd) {
        if (odd.has("odds") && !odd.get("odds").isNull()) {
            return odd.get("odds");
        }
        if (odd.has("price") && !odd.get("price").isNull()) {
            return odd.get("price");
        }
        return OddsNormalizer.firstPriceNode(odd);
    }

    private static JsonNode firstPriceNode(JsonNode odd) {
        for (String field : new String[]{"odds", "price", "under", "over", "odd", "value", "decimal", "home", "draw", "away"}) {
            if (!odd.has(field) || odd.get(field).isNull()) continue;
            return odd.get(field);
        }
        return null;
    }

    private static String canonicalMarket(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("-", " ").trim();
        if (OddsNormalizer.isFirstHalfResultMarket(normalized)) {
            return "FIRST_HALF_1X2";
        }
        if (OddsNormalizer.isFirstHalfBttsMarket(normalized)) {
            return "FIRST_HALF_BTTS";
        }
        if (OddsNormalizer.isHtftMarket(normalized)) {
            return "HTFT";
        }
        return null;
    }

    private static boolean isHtftMarket(String normalized) {
        if (normalized.contains("half time result") || normalized.contains("halftime result")) {
            return false;
        }
        String compact = normalized.replaceAll("\\s*/\\s*", "/").replaceAll("\\s+", " ").trim();
        return compact.equals("half time/full time") || compact.equals("htft");
    }

    private static boolean isFirstHalfResultMarket(String normalized) {
        if (normalized.contains("handicap") || normalized.contains("corner") || normalized.contains("spread")) {
            return false;
        }
        return normalized.equals("ml ht") || normalized.equals("moneyline ht") || normalized.equals("winner ht") || normalized.contains("half time result") || normalized.contains("halftime result") || normalized.contains("1st half result") || normalized.contains("first half winner") || normalized.equals("ht result");
    }

    private static boolean isFirstHalfBttsMarket(String normalized) {
        if (!normalized.contains("both teams to score") && !normalized.contains("btts")) {
            return false;
        }
        if (normalized.contains("2h") || normalized.contains("2nd half") || normalized.contains("second half")) {
            return false;
        }
        return normalized.contains(" ht") || normalized.contains("1st half") || normalized.contains("first half") || normalized.contains("half time");
    }
}

