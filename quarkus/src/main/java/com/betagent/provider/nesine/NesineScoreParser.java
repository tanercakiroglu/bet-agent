package com.betagent.provider.nesine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Parses Nesine live-score ES periods.
 * <p>
 * Verified against live feed samples: T=19 is half-time, T=1 is full-time.
 * T=2 is NOT reliably "second-half goals only" (often duplicates HT or FT).
 */
public final class NesineScoreParser {
    private static final Logger LOG = Logger.getLogger(NesineScoreParser.class);
    private static final int PERIOD_HALF_TIME = 19;
    private static final int PERIOD_FULL_TIME = 1;
    private static final int PERIOD_SECOND_HALF = 2;
    private static final int STATUS_FINISHED = 4;
    private static final int MAX_GOALS_PER_TEAM = 20;

    private NesineScoreParser() {
    }

    public record ResolvedScore(int hthg, int htag, int fthg, int ftag, String feedId, boolean secondHalfConsistent) {
    }

    public static Optional<ResolvedScore> resolveFinishedRow(JsonNode row) {
        if (row == null || row.isNull()) {
            return Optional.empty();
        }
        if (row.path("S").asInt(-1) != STATUS_FINISHED) {
            return Optional.empty();
        }
        String feedId = feedId(row);
        Optional<int[]> halfTime = periodGoals(row, PERIOD_HALF_TIME);
        Optional<int[]> fullTime = periodGoals(row, PERIOD_FULL_TIME);
        if (halfTime.isEmpty() || fullTime.isEmpty()) {
            LOG.debugf("Rejecting Nesine score feed=%s: missing T=19 or T=1", feedId);
            return Optional.empty();
        }
        int hthg = halfTime.get()[0];
        int htag = halfTime.get()[1];
        int fthg = fullTime.get()[0];
        int ftag = fullTime.get()[1];
        if (!isValidScore(hthg, htag, fthg, ftag)) {
            LOG.warnf("Rejecting invalid Nesine score feed=%s HT=%d-%d FT=%d-%d", feedId, hthg, htag, fthg, ftag);
            return Optional.empty();
        }
        boolean secondHalfConsistent = true;
        Optional<int[]> secondHalf = periodGoals(row, PERIOD_SECOND_HALF);
        if (secondHalf.isPresent()) {
            int[] sh = secondHalf.get();
            int calcHome = hthg + sh[0];
            int calcAway = htag + sh[1];
            secondHalfConsistent = calcHome == fthg && calcAway == ftag;
            if (!secondHalfConsistent) {
                LOG.debugf(
                        "Nesine T=2 ignored feed=%s HT=%d-%d T1=%d-%d T2=%d-%d (T2 is not 2H-only in this feed)",
                        feedId, hthg, htag, fthg, ftag, sh[0], sh[1]);
            }
        }
        return Optional.of(new ResolvedScore(hthg, htag, fthg, ftag, feedId, secondHalfConsistent));
    }

    public static boolean isValidScore(int hthg, int htag, int fthg, int ftag) {
        if (hthg < 0 || htag < 0 || fthg < 0 || ftag < 0) {
            return false;
        }
        if (fthg < hthg || ftag < htag) {
            return false;
        }
        if (fthg > MAX_GOALS_PER_TEAM || ftag > MAX_GOALS_PER_TEAM) {
            return false;
        }
        return true;
    }

    public static String feedId(JsonNode row) {
        long nid = row.path("NID").asLong(0L);
        if (nid > 0L) {
            return String.valueOf(nid);
        }
        return String.valueOf(row.path("C").asLong());
    }

    public static String feedEventId(JsonNode row) {
        return String.valueOf(row.path("C").asLong());
    }

    public static boolean teamsMatch(String trackedHome, String trackedAway, String feedHome, String feedAway) {
        return sideMatches(trackedHome, feedHome) && sideMatches(trackedAway, feedAway);
    }

    static boolean sideMatches(String tracked, String feed) {
        String left = normalizeTeam(tracked);
        String right = normalizeTeam(feed);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        if (left.length() >= 4 && right.length() >= 4) {
            return left.startsWith(right) || right.startsWith(left);
        }
        return false;
    }

    public static String normalizeTeam(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace('\u0131', 'i').replace('\u0130', 'i')
                .replace('\u015f', 's').replace('\u015e', 's')
                .replace('\u011f', 'g').replace('\u011e', 'g')
                .replace('\u00fc', 'u').replace('\u00dc', 'u')
                .replace('\u00f6', 'o').replace('\u00d6', 'o')
                .replace('\u00e7', 'c').replace('\u00c7', 'c');
        return normalized.replaceAll("[^a-z0-9]+", "").trim();
    }

    private static Optional<int[]> periodGoals(JsonNode row, int periodType) {
        for (JsonNode period : row.path("ES")) {
            if (period.path("T").asInt(-1) != periodType) {
                continue;
            }
            return Optional.of(new int[]{period.path("H").asInt(), period.path("A").asInt()});
        }
        return Optional.empty();
    }
}
