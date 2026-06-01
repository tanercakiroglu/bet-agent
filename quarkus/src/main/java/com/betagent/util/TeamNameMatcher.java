package com.betagent.util;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Cross-provider team matching: same kickoff date + fuzzy name similarity
 * (Turkish/English spellings, abbreviations, club suffixes).
 */
public final class TeamNameMatcher {

    private static final double MIN_SIDE_SIMILARITY = 0.72;
    private static final double MIN_PAIR_SIMILARITY = 0.74;
    private static final double AMBIGUITY_GAP = 0.06;

    private static final Set<String> NOISE_TOKENS = Set.of(
            "fc", "fk", "sk", "cf", "sc", "ac", "afc", "as", "bk", "if", "ff", "sv",
            "united", "city", "town", "club", "kulubu", "kulübü", "spor", "basketbol",
            "basketball", "team", "the", "de", "la", "real", "sporting");

    private TeamNameMatcher() {}

    public static boolean teamsMatch(String home1, String away1, String home2, String away2) {
        return sideMatches(home1, home2) && sideMatches(away1, away2);
    }

    public static boolean sideMatches(String left, String right) {
        return sideSimilarity(left, right) >= MIN_SIDE_SIMILARITY;
    }

    public static double pairSimilarity(String home1, String away1, String home2, String away2) {
        return (sideSimilarity(home1, home2) + sideSimilarity(away1, away2)) / 2.0;
    }

    public static String normalize(String value) {
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

    public static <T> Optional<T> findBestByTeamsAndDate(
            List<T> candidates,
            Function<T, String> homeFn,
            Function<T, String> awayFn,
            Function<T, LocalDate> dateFn,
            String home,
            String away,
            LocalDate date) {
        if (date == null || home == null || away == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<Scored<T>> scored = new ArrayList<>();
        for (T candidate : candidates) {
            LocalDate candidateDate = dateFn.apply(candidate);
            if (candidateDate == null || !date.equals(candidateDate)) {
                continue;
            }
            double similarity = pairSimilarity(
                    home, away, homeFn.apply(candidate), awayFn.apply(candidate));
            if (similarity >= MIN_PAIR_SIMILARITY) {
                scored.add(new Scored<>(candidate, similarity));
            }
        }
        if (scored.isEmpty()) {
            return Optional.empty();
        }
        scored.sort(Comparator.comparingDouble((Scored<T> s) -> s.score).reversed());
        if (scored.size() == 1) {
            return Optional.of(scored.getFirst().item);
        }
        double top = scored.get(0).score;
        double second = scored.get(1).score;
        if (top - second < AMBIGUITY_GAP) {
            return Optional.empty();
        }
        return Optional.of(scored.getFirst().item);
    }

    static double sideSimilarity(String left, String right) {
        if (left == null || right == null) {
            return 0.0;
        }
        List<String> leftTokens = tokens(left);
        List<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        String leftBlob = String.join("", leftTokens);
        String rightBlob = String.join("", rightTokens);
        double blob = tokenSimilarity(leftBlob, rightBlob);
        double tokenScore = Math.max(
                averageBestTokenMatch(leftTokens, rightTokens),
                averageBestTokenMatch(rightTokens, leftTokens));
        return Math.max(blob, tokenScore);
    }

    private static double averageBestTokenMatch(List<String> from, List<String> to) {
        double sum = 0.0;
        for (String token : from) {
            double best = 0.0;
            for (String other : to) {
                best = Math.max(best, tokenSimilarity(token, other));
            }
            sum += best;
        }
        return sum / from.size();
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace('\u0131', 'i').replace('\u0130', 'i')
                .replace('\u015f', 's').replace('\u015e', 's')
                .replace('\u011f', 'g').replace('\u011e', 'g')
                .replace('\u00fc', 'u').replace('\u00dc', 'u')
                .replace('\u00f6', 'o').replace('\u00d6', 'o')
                .replace('\u00e7', 'c').replace('\u00c7', 'c');
        String[] parts = normalized.split("[^a-z0-9]+");
        ArrayList<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.length() < 2 || NOISE_TOKENS.contains(part)) {
                continue;
            }
            out.add(part);
        }
        if (out.isEmpty()) {
            String blob = normalize(value);
            if (!blob.isEmpty()) {
                out.add(blob);
            }
        }
        return out;
    }

    private static double tokenSimilarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (left.equals(right)) {
            return 1.0;
        }
        if (left.length() >= 4 && right.length() >= 4) {
            if (left.startsWith(right) || right.startsWith(left)) {
                return 0.95;
            }
            if (left.contains(right) || right.contains(left)) {
                return 0.9;
            }
        }
        return levenshteinRatio(left, right);
    }

    private static double levenshteinRatio(String left, String right) {
        int maxLen = Math.max(left.length(), right.length());
        if (maxLen == 0) {
            return 1.0;
        }
        int distance = levenshteinDistance(left, right);
        return 1.0 - ((double) distance / maxLen);
    }

    private static int levenshteinDistance(String left, String right) {
        int[] prev = new int[right.length() + 1];
        int[] curr = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[right.length()];
    }

    private record Scored<T>(T item, double score) {}
}
