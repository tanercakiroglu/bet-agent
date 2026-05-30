/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.inject.Inject
 */
package com.betagent.service;

import com.betagent.domain.LeagueCatalog;
import com.betagent.domain.MatchScore;
import com.betagent.persistence.entity.MatchEntity;
import com.betagent.persistence.entity.MatchScoreEntity;
import com.betagent.persistence.entity.ProviderEventEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class EventParser {
    private final ObjectMapper mapper;

    @Inject
    public EventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String leagueLabel(JsonNode event) {
        String name;
        JsonNode league = event.get("league");
        String string = name = league != null && league.has("name") ? league.get("name").asText() : "";
        if (!name.isBlank()) {
            return name;
        }
        String slug = league != null && league.has("slug") ? league.get("slug").asText() : "";
        return slug.isBlank() ? "Bilinmeyen lig" : slug;
    }

    public boolean isTargetLeague(JsonNode event, List<String> keywords) {
        JsonNode league = event.get("league");
        String name = league != null && league.has("name") ? league.get("name").asText() : "";
        String slug = league != null && league.has("slug") ? league.get("slug").asText() : "";
        return LeagueCatalog.matchesLeague(name, slug, keywords);
    }

    public ProviderEventEntity toProviderEvent(JsonNode event, String catalogName) {
        LocalDateTime now;
        ProviderEventEntity entity = new ProviderEventEntity();
        entity.provider = catalogName;
        entity.providerMatchId = EventParser.text(event, "id");
        JsonNode league = event.get("league");
        entity.leagueName = league != null && league.has("name") ? league.get("name").asText() : "";
        entity.leagueSlug = league != null && league.has("slug") ? league.get("slug").asText() : "";
        entity.competitionCode = entity.leagueName.isBlank() ? entity.leagueSlug : entity.leagueName;
        entity.sport = event.path("sport").path("slug").asText("");
        entity.homeTeam = EventParser.text(event, "home");
        entity.awayTeam = EventParser.text(event, "away");
        entity.status = EventParser.text(event, "status");
        entity.eventDate = EventParser.parseDateTime(EventParser.text(event, "date"));
        entity.scoresJson = event.has("scores") ? event.get("scores").toString() : "{}";
        try {
            entity.rawJson = this.mapper.writeValueAsString(event);
        }
        catch (Exception ex) {
            entity.rawJson = "{}";
        }
        entity.firstSeenAt = now = LocalDateTime.now();
        entity.lastSeenAt = now;
        return entity;
    }

    public Optional<MatchBundle> toSettledMatch(JsonNode event, String catalogName) {
        String status = EventParser.text(event, "status").toLowerCase();
        if (!List.of("settled", "finished", "complete", "ft").contains(status)) {
            return Optional.empty();
        }
        Optional<MatchScore> score = this.parseScores(event.get("scores"));
        if (score.isEmpty()) {
            return Optional.empty();
        }
        MatchEntity match = new MatchEntity();
        match.provider = catalogName;
        match.providerMatchId = EventParser.text(event, "id");
        JsonNode league = event.get("league");
        match.competitionCode = league != null && league.has("name") ? league.get("name").asText() : catalogName;
        String dateText = EventParser.text(event, "date");
        match.matchDate = dateText.length() >= 10 ? LocalDate.parse(dateText.substring(0, 10)) : LocalDate.now();
        match.season = String.valueOf(match.matchDate.getYear());
        match.homeTeam = EventParser.text(event, "home");
        match.awayTeam = EventParser.text(event, "away");
        MatchScoreEntity scoreEntity = new MatchScoreEntity();
        scoreEntity.provider = match.provider;
        scoreEntity.providerMatchId = match.providerMatchId;
        MatchScore s = score.get();
        scoreEntity.hthg = s.hthg();
        scoreEntity.htag = s.htag();
        scoreEntity.fthg = s.fthg();
        scoreEntity.ftag = s.ftag();
        scoreEntity.htResult = s.htResult();
        scoreEntity.ftResult = s.ftResult();
        scoreEntity.htftCode = s.htftCode();
        scoreEntity.firstHalfKg = s.firstHalfKg();
        scoreEntity.firstHalfKgTarafCode = s.firstHalfKgTarafCode();
        return Optional.of(new MatchBundle(match, scoreEntity));
    }

    private Optional<MatchScore> parseScores(JsonNode scores) {
        if (scores == null || scores.isNull()) {
            return Optional.empty();
        }
        JsonNode periods = scores.get("periods");
        if (periods != null && periods.isObject()) {
            JsonNode ht = this.firstPeriod(periods, "1", "p1", "ht", "halftime", "first_half", "firsthalf");
            JsonNode ft = this.firstPeriod(periods, "ft", "fulltime", "regular", "full_time");
            JsonNode p2 = this.firstPeriod(periods, "p2");
            if (ht != null && ht.has("home") && ht.has("away")) {
                int hthg = ht.get("home").asInt();
                int htag = ht.get("away").asInt();
                if (ft != null && ft.has("home") && ft.has("away")) {
                    return Optional.of(new MatchScore(hthg, htag, ft.get("home").asInt(), ft.get("away").asInt()));
                }
                if (p2 != null && p2.has("home") && p2.has("away")) {
                    return Optional.of(new MatchScore(hthg, htag, hthg + p2.get("home").asInt(), htag + p2.get("away").asInt()));
                }
                if (scores.has("home") && scores.has("away")) {
                    return Optional.of(new MatchScore(hthg, htag, scores.get("home").asInt(), scores.get("away").asInt()));
                }
            }
        }
        JsonNode halftime = scores.get("halftime");
        JsonNode fulltime = scores.get("fulltime");
        if (halftime != null && fulltime != null && halftime.has("home") && fulltime.has("home")) {
            return Optional.of(new MatchScore(halftime.get("home").asInt(), halftime.get("away").asInt(), fulltime.get("home").asInt(), fulltime.get("away").asInt()));
        }
        return Optional.empty();
    }

    private JsonNode firstPeriod(JsonNode periods, String ... keys) {
        for (String key : keys) {
            if (!periods.has(key)) continue;
            return periods.get(key);
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        }
        catch (Exception ignored) {
            return null;
        }
    }

    public record MatchBundle(MatchEntity match, MatchScoreEntity score) {
    }
}

