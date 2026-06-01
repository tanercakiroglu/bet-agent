package com.betagent.provider.nesine;

import com.betagent.util.TeamNameMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Parses Nesine live-score ES periods.
 * <p>
 * T=1 is full-time, T=19 is half-time, T=2 is second-half goals when consistent.
 * When T=19 duplicates T=1 but T=2 adds 2H goals, HT is derived as FT minus T=2.
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
        Optional<int[]> fullTime = periodGoals(row, PERIOD_FULL_TIME);
        if (fullTime.isEmpty()) {
            LOG.debugf("Rejecting Nesine score feed=%s: missing T=1", feedId);
            return Optional.empty();
        }
        int fthg = fullTime.get()[0];
        int ftag = fullTime.get()[1];
        Optional<int[]> halfTime = periodGoals(row, PERIOD_HALF_TIME);
        Optional<int[]> secondHalf = periodGoals(row, PERIOD_SECOND_HALF);

        int hthg;
        int htag;
        boolean secondHalfConsistent = false;

        if (halfTime.isEmpty()) {
            if (secondHalf.isEmpty()) {
                LOG.debugf("Rejecting Nesine score feed=%s: missing T=19 and T=2", feedId);
                return Optional.empty();
            }
            int[] sh = secondHalf.get();
            if (fthg < sh[0] || ftag < sh[1]) {
                LOG.debugf("Rejecting Nesine score feed=%s: T=2 exceeds T=1", feedId);
                return Optional.empty();
            }
            hthg = fthg - sh[0];
            htag = ftag - sh[1];
            secondHalfConsistent = true;
        } else {
            hthg = halfTime.get()[0];
            htag = halfTime.get()[1];
            if (secondHalf.isPresent()) {
                int[] sh = secondHalf.get();
                secondHalfConsistent = hthg + sh[0] == fthg && htag + sh[1] == ftag;
                if (!secondHalfConsistent && fthg >= sh[0] && ftag >= sh[1]) {
                    int derivedH = fthg - sh[0];
                    int derivedA = ftag - sh[1];
                    boolean derivedValid = isValidScore(derivedH, derivedA, fthg, ftag);
                    boolean htEqualsFt = hthg == fthg && htag == ftag;
                    boolean derivedFromSecondHalf = derivedValid
                            && sh[0] > 0
                            && (derivedH < hthg || derivedA < htag);
                    if (derivedFromSecondHalf && htEqualsFt) {
                        LOG.infof(
                                "Nesine HT corrected from T=2 feed=%s T19=%d-%d T1=%d-%d T2=%d-%d -> HT %d-%d",
                                feedId, hthg, htag, fthg, ftag, sh[0], sh[1], derivedH, derivedA);
                        hthg = derivedH;
                        htag = derivedA;
                        secondHalfConsistent = true;
                    }
                }
                if (!secondHalfConsistent) {
                    LOG.debugf(
                            "Nesine T=2 ignored feed=%s HT=%d-%d T1=%d-%d T2=%d-%d",
                            feedId, hthg, htag, fthg, ftag, sh[0], sh[1]);
                }
            }
        }

        if (!isValidScore(hthg, htag, fthg, ftag)) {
            LOG.warnf("Rejecting invalid Nesine score feed=%s HT=%d-%d FT=%d-%d", feedId, hthg, htag, fthg, ftag);
            return Optional.empty();
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
        return TeamNameMatcher.teamsMatch(trackedHome, trackedAway, feedHome, feedAway);
    }

    public static String normalizeTeam(String value) {
        return TeamNameMatcher.normalize(value);
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
