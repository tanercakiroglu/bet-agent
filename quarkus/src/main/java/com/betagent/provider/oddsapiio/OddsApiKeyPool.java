/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 *  org.jboss.logging.Logger
 */
package com.betagent.provider.oddsapiio;

import com.betagent.config.OddsApiConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OddsApiKeyPool {
    private static final Logger LOG = Logger.getLogger(OddsApiKeyPool.class);
    private final List<String> keys;
    private final Map<Integer, Instant> exhaustedUntilByIndex = new ConcurrentHashMap<Integer, Instant>();
    private final AtomicInteger roundRobinStart = new AtomicInteger(0);
    private int activeIndex;

    @Inject
    public OddsApiKeyPool(OddsApiConfig config) {
        this.keys = OddsApiKeyPool.buildKeys(config);
        this.activeIndex = 0;
        LOG.infof("Odds-API.io key pool ready: %d key(s) configured (~%d requests/hour total)", this.keys.size(), (this.keys.size() * config.requestBudget()));
    }

    public void beginRun() {
        this.activeIndex = this.findFirstUsableKeyIndex();
        LOG.infof("Odds API run starting on key slot %d/%d", this.activeSlot(), this.keys.size());
    }

    public int keyCount() {
        return this.keys.size();
    }

    public int totalHourlyBudget(int perKeyBudget) {
        return perKeyBudget * Math.max(1, this.keys.size());
    }

    public String activeKey() {
        if (this.keys.isEmpty()) {
            return "";
        }
        return this.keys.get(this.activeIndex);
    }

    public int activeSlot() {
        return this.activeIndex + 1;
    }

    public boolean canFailover() {
        for (int i = this.activeIndex + 1; i < this.keys.size(); ++i) {
            if (!this.isUsable(i)) continue;
            return true;
        }
        return false;
    }

    public boolean failover() {
        for (int i = this.activeIndex + 1; i < this.keys.size(); ++i) {
            if (!this.isUsable(i)) continue;
            this.activeIndex = i;
            LOG.warnf("Odds-API.io switched to backup API key (slot %d/%d)", this.activeSlot(), this.keys.size());
            return true;
        }
        return false;
    }

    public void markCurrentKeyExhausted() {
        if (this.keys.isEmpty()) {
            return;
        }
        Instant resetAt = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1L, ChronoUnit.HOURS).plus(1L, ChronoUnit.MINUTES);
        this.exhaustedUntilByIndex.put(this.activeIndex, resetAt);
        LOG.warnf("Marked API key slot %d exhausted until next collection slot (%s)", this.activeSlot(), resetAt);
    }

    public int usableKeyCount() {
        int count = 0;
        for (int i = 0; i < this.keys.size(); ++i) {
            if (!this.isUsable(i)) continue;
            ++count;
        }
        return count;
    }

    private int findFirstUsableKeyIndex() {
        if (this.keys.isEmpty()) {
            return 0;
        }
        int start = Math.floorMod(this.roundRobinStart.getAndIncrement(), this.keys.size());
        for (int offset = 0; offset < this.keys.size(); ++offset) {
            int idx = (start + offset) % this.keys.size();
            if (!this.isUsable(idx)) continue;
            return idx;
        }
        return start;
    }

    private boolean isUsable(int index) {
        Instant until = this.exhaustedUntilByIndex.get(index);
        return until == null || Instant.now().isAfter(until);
    }

    private static List<String> buildKeys(OddsApiConfig config) {
        ArrayList<String> resolved = new ArrayList<String>();
        config.apiKey().ifPresent(key -> OddsApiKeyPool.addKey(resolved, key));
        config.apiKeySecondary().ifPresent(key -> OddsApiKeyPool.addKey(resolved, key));
        config.apiKeyTertiary().ifPresent(key -> OddsApiKeyPool.addKey(resolved, key));
        return List.copyOf(resolved);
    }

    private static void addKey(List<String> keys, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        String trimmed = candidate.trim();
        if (!keys.contains(trimmed)) {
            keys.add(trimmed);
        }
    }
}

