package com.betagent.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class RepairRunContext {

    private final AtomicReference<RepairSession> active = new AtomicReference<>();

    public RepairSession begin() {
        RepairSession session = new RepairSession();
        this.active.set(session);
        return session;
    }

    public void end() {
        this.active.set(null);
    }

    public Optional<RepairSession> current() {
        return Optional.ofNullable(this.active.get());
    }

    public static final class RepairSession {
        private final List<Map<String, Object>> corrections = new ArrayList<>();

        public void record(
                String matchId,
                String homeTeam,
                String awayTeam,
                String matchDate,
                String method,
                Integer beforeHthg,
                Integer beforeHtag,
                Integer beforeFthg,
                Integer beforeFtag,
                int afterHthg,
                int afterHtag,
                int afterFthg,
                int afterFtag) {
            if (beforeHthg != null
                    && beforeHthg == afterHthg
                    && beforeHtag == afterHtag
                    && beforeFthg == afterFthg
                    && beforeFtag == afterFtag) {
                return;
            }
            this.corrections.add(Map.of(
                    "match_id", matchId,
                    "home_team", homeTeam != null ? homeTeam : "",
                    "away_team", awayTeam != null ? awayTeam : "",
                    "match_date", matchDate != null ? matchDate : "",
                    "method", method,
                    "before", formatScore(beforeHthg, beforeHtag, beforeFthg, beforeFtag),
                    "after", formatScore(afterHthg, afterHtag, afterFthg, afterFtag)));
        }

        public void recordCleared(
                String matchId,
                String homeTeam,
                String awayTeam,
                String matchDate,
                int hthg,
                int htag,
                int fthg,
                int ftag) {
            this.corrections.add(Map.of(
                    "match_id", matchId,
                    "home_team", homeTeam != null ? homeTeam : "",
                    "away_team", awayTeam != null ? awayTeam : "",
                    "match_date", matchDate != null ? matchDate : "",
                    "method", "cleared_suspicious",
                    "before", formatScore(hthg, htag, fthg, ftag),
                    "after", "—"));
        }

        public List<Map<String, Object>> corrections() {
            return List.copyOf(this.corrections);
        }

        private static String formatScore(Integer hthg, Integer htag, Integer fthg, Integer ftag) {
            if (hthg == null || htag == null || fthg == null || ftag == null) {
                return "—";
            }
            return "IY " + hthg + "-" + htag + " MS " + fthg + "-" + ftag;
        }
    }
}
