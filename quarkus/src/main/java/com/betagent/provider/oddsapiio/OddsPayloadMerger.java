/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.node.ArrayNode
 *  com.fasterxml.jackson.databind.node.ObjectNode
 */
package com.betagent.provider.oddsapiio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class OddsPayloadMerger {
    private OddsPayloadMerger() {
    }

    static List<JsonNode> mergeByEventId(List<JsonNode> primary, List<JsonNode> supplemental) {
        if (supplemental == null || supplemental.isEmpty()) {
            return primary == null ? List.of() : primary;
        }
        LinkedHashMap<String, JsonNode> byId = new LinkedHashMap<String, JsonNode>();
        if (primary != null) {
            for (JsonNode node : primary) {
                byId.put(OddsPayloadMerger.eventId(node), node.deepCopy());
            }
        }
        for (JsonNode node : supplemental) {
            String id = OddsPayloadMerger.eventId(node);
            JsonNode existing = (JsonNode)byId.get(id);
            if (existing == null) {
                byId.put(id, node.deepCopy());
                continue;
            }
            if (!(existing instanceof ObjectNode)) continue;
            ObjectNode objectNode = (ObjectNode)existing;
            OddsPayloadMerger.mergeBookmakers(objectNode, node);
        }
        return new ArrayList<JsonNode>(byId.values());
    }

    private static void mergeBookmakers(ObjectNode target, JsonNode source) {
        JsonNode targetBookmakers = target.get("bookmakers");
        JsonNode sourceBookmakers = source.get("bookmakers");
        if (targetBookmakers == null || !targetBookmakers.isObject()) {
            if (sourceBookmakers != null) {
                target.set("bookmakers", sourceBookmakers.deepCopy());
            }
            return;
        }
        if (sourceBookmakers == null || !sourceBookmakers.isObject()) {
            return;
        }
        ObjectNode targetObj = (ObjectNode)targetBookmakers;
        sourceBookmakers.fields().forEachRemaining(entry -> {
            String bookmaker = (String)entry.getKey();
            JsonNode incomingMarkets = (JsonNode)entry.getValue();
            if (!incomingMarkets.isArray()) {
                return;
            }
            if (!targetObj.has(bookmaker) || !targetObj.get(bookmaker).isArray()) {
                targetObj.set(bookmaker, incomingMarkets.deepCopy());
                return;
            }
            ArrayNode existingMarkets = (ArrayNode)targetObj.get(bookmaker);
            HashSet marketNames = new HashSet();
            existingMarkets.forEach(market -> marketNames.add(OddsPayloadMerger.normalizedMarketName(market.path("name").asText(""))));
            incomingMarkets.forEach(market -> {
                String name = OddsPayloadMerger.normalizedMarketName(market.path("name").asText(""));
                if (!marketNames.contains(name)) {
                    existingMarkets.add(market.deepCopy());
                    marketNames.add(name);
                }
            });
        });
    }

    private static String normalizedMarketName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String eventId(JsonNode node) {
        return node.path("id").asText(node.path("eventId").asText(""));
    }
}

