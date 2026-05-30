/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 */
package com.betagent.provider.oddsapiio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

final class OddsApiIoJson {
    private OddsApiIoJson() {
    }

    static List<JsonNode> asEventList(JsonNode root) {
        return OddsApiIoJson.asNodeList(root);
    }

    static List<JsonNode> asOddsPayloadList(JsonNode root) {
        if (root == null || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            return OddsApiIoJson.asNodeList(root);
        }
        return List.of(root);
    }

    private static List<JsonNode> asNodeList(JsonNode root) {
        if (root == null || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            ArrayList<JsonNode> nodes = new ArrayList<JsonNode>();
            root.forEach(nodes::add);
            return nodes;
        }
        if (root.has("data") && root.get("data").isArray()) {
            ArrayList<JsonNode> nodes = new ArrayList<JsonNode>();
            root.get("data").forEach(nodes::add);
            return nodes;
        }
        return List.of();
    }
}

