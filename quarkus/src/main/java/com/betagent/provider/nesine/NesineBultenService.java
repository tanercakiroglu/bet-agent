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
package com.betagent.provider.nesine;

import com.betagent.provider.nesine.client.NesineRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NesineBultenService {
    private static final Logger LOG = Logger.getLogger(NesineBultenService.class);
    private static final long CACHE_TTL_MS = 120000L;
    @Inject
    @RestClient
    NesineRestClient client;
    private volatile JsonNode cached;
    private volatile long cachedAtMs;

    public Uni<JsonNode> bulten() {
        JsonNode hit = this.cached;
        if (hit != null && System.currentTimeMillis() - this.cachedAtMs < 120000L) {
            return Uni.createFrom().item(hit);
        }
        return this.client.preBultenFull("https://www.nesine.com", "https://www.nesine.com/iddaa", "identity").invoke(node -> {
            this.cached = node;
            this.cachedAtMs = System.currentTimeMillis();
            LOG.infof("Nesine bulten loaded (events=%d)", NesineBultenService.eventCount(node));
        });
    }

    public void invalidateCache() {
        this.cached = null;
        this.cachedAtMs = 0L;
    }

    private static int eventCount(JsonNode root) {
        JsonNode events = root.path("sg").path("EA");
        return events.isArray() ? events.size() : 0;
    }
}

