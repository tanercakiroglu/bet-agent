/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 *  org.jboss.logging.Logger
 */
package com.betagent.provider.oddsapiio;

import com.betagent.config.OddsApiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OddsApiBookmakerValidator {
    private static final Logger LOG = Logger.getLogger(OddsApiBookmakerValidator.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24L);
    @Inject
    OddsApiConfig config;
    @Inject
    ObjectMapper objectMapper;
    private volatile Map<String, String> canonicalByLowerName = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;
    private volatile String activeBookmakers = "";

    public String beginRun(String configured) {
        this.activeBookmakers = this.validateAndResolve(configured);
        LOG.infof("Odds bookmakers for this run: %s", this.activeBookmakers);
        return this.activeBookmakers;
    }

    public String activeBookmakers() {
        if (this.activeBookmakers == null || this.activeBookmakers.isBlank()) {
            return this.config.bookmakers();
        }
        return this.activeBookmakers;
    }

    public String validateAndResolve(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("ODDS_API_IO_BOOKMAKERS is empty");
        }
        Map<String, String> known = this.loadKnownBookmakers();
        ArrayList<String> invalid = new ArrayList<String>();
        ArrayList<String> resolved = new ArrayList<String>();
        for (String part : configured.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String canonical = known.get(trimmed.toLowerCase(Locale.ROOT));
            if (canonical == null) {
                invalid.add(trimmed);
                continue;
            }
            resolved.add(canonical);
        }
        if (resolved.isEmpty()) {
            throw new IllegalStateException("ODDS_API_IO_BOOKMAKERS has no valid entries");
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("Unknown bookmakers in ODDS_API_IO_BOOKMAKERS: " + String.valueOf(invalid) + ". Use exact names from https://api.odds-api.io/v3/bookmakers (e.g. Bet365, Sbobet)");
        }
        return String.join((CharSequence)",", resolved);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private Map<String, String> loadKnownBookmakers() {
        if (!this.canonicalByLowerName.isEmpty() && this.loadedAt.plus(CACHE_TTL).isAfter(Instant.now())) {
            return this.canonicalByLowerName;
        }
        OddsApiBookmakerValidator oddsApiBookmakerValidator = this;
        synchronized (oddsApiBookmakerValidator) {
            if (!this.canonicalByLowerName.isEmpty() && this.loadedAt.plus(CACHE_TTL).isAfter(Instant.now())) {
                return this.canonicalByLowerName;
            }
            this.canonicalByLowerName = this.fetchBookmakersFromApi();
            this.loadedAt = Instant.now();
            return this.canonicalByLowerName;
        }
    }

    private Map<String, String> fetchBookmakersFromApi() {
        try {
            String base = this.config.baseUrl().endsWith("/") ? this.config.baseUrl().substring(0, this.config.baseUrl().length() - 1) : this.config.baseUrl();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(base + "/bookmakers")).timeout(Duration.ofSeconds(20L)).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Could not load bookmakers list (HTTP " + response.statusCode() + ")");
            }
            JsonNode root = this.objectMapper.readTree(response.body());
            if (!root.isArray()) {
                throw new IllegalStateException("Unexpected bookmakers API response");
            }
            HashMap<String, String> map = new HashMap<String, String>();
            for (JsonNode node : root) {
                String name;
                if (!node.path("active").asBoolean(true) || (name = node.path("name").asText("")).isBlank()) continue;
                map.put(name.toLowerCase(Locale.ROOT), name);
            }
            if (map.isEmpty()) {
                throw new IllegalStateException("Bookmakers list from API is empty");
            }
            return Map.copyOf(map);
        }
        catch (IllegalStateException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to validate bookmakers: " + ex.getMessage(), ex);
        }
    }
}

