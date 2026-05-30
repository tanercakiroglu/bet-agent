/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Uni
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 *  org.eclipse.microprofile.rest.client.inject.RestClient
 */
package com.betagent.provider.nesine;

import com.betagent.provider.nesine.client.NesineLiveScoreRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class NesineLiveScoreService {
    private static final int FOOTBALL = 1;
    private static final long CACHE_TTL_MS = 60000L;
    @Inject
    @RestClient
    NesineLiveScoreRestClient client;
    private volatile JsonNode cached;
    private volatile long cachedAtMs;

    public Uni<JsonNode> liveScoreFeed() {
        return this.liveScoreFeed(false);
    }

    public Uni<JsonNode> liveScoreFeed(boolean includeFinished) {
        JsonNode hit;
        if (!includeFinished && (hit = this.cached) != null && System.currentTimeMillis() - this.cachedAtMs < 60000L) {
            return Uni.createFrom().item(hit);
        }
        return this.client.liveMatches(1, includeFinished ? Boolean.TRUE : null, "https://www.nesine.com", "https://www.nesine.com/iddaa/canli-skor/futbol", "identity").invoke(node -> {
            if (!includeFinished) {
                this.cached = node;
                this.cachedAtMs = System.currentTimeMillis();
            }
        });
    }

    public void invalidateCache() {
        this.cached = null;
        this.cachedAtMs = 0L;
    }
}

