/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Uni
 */
package com.betagent.provider;

import com.betagent.provider.EventStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import java.util.List;

public interface OddsDataProvider {
    public String id();

    public String catalogName();

    public boolean configured();

    public Uni<List<JsonNode>> fetchEvents(EventStatus var1);

    public Uni<JsonNode> fetchOdds(String var1);

    public Uni<List<JsonNode>> fetchMultiOdds(List<String> var1);
}

