package com.betagent.provider.nesine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NesineScoreParserTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void consistentSampleUsesHalfTimeAndFullTimePeriods() throws Exception {
        JsonNode row = sampleRow(2903307);
        Optional<NesineScoreParser.ResolvedScore> score = NesineScoreParser.resolveFinishedRow(row);
        assertTrue(score.isPresent());
        assertEquals(1, score.get().hthg());
        assertEquals(0, score.get().htag());
        assertEquals(1, score.get().fthg());
        assertEquals(0, score.get().ftag());
        assertTrue(score.get().secondHalfConsistent());
    }

    @Test
    void inconsistentSecondHalfStillUsesTrustedFullTimePeriod() throws Exception {
        JsonNode row = sampleRow(2903028);
        Optional<NesineScoreParser.ResolvedScore> score = NesineScoreParser.resolveFinishedRow(row);
        assertTrue(score.isPresent());
        assertEquals(0, score.get().hthg());
        assertEquals(1, score.get().htag());
        assertEquals(0, score.get().fthg());
        assertEquals(1, score.get().ftag());
        assertFalse(score.get().secondHalfConsistent());
    }

    @Test
    void rejectsImpossibleFullTime() throws Exception {
        assertFalse(NesineScoreParser.isValidScore(2, 0, 1, 0));
        assertFalse(NesineScoreParser.isValidScore(0, 1, 0, 0));
    }

    @Test
    void teamsMatchAllowsAbbreviations() {
        assertTrue(NesineScoreParser.teamsMatch("Devonport", "Ulverstone FC", "Devonport C.", "Ulverstone"));
        assertFalse(NesineScoreParser.teamsMatch("PSG", "Arsenal", "Lyon", "Arsenal"));
    }

    @Test
    void correctsHalfTimeWhenT19DuplicatesFullTimeButSecondHalfAddsGoals() throws Exception {
        JsonNode row = mapper.readTree("""
                {"S":4,"C":999001,"NID":999001,"HTTR":"Ostersunds FK","ATTR":"Orebro SK",
                 "ES":[{"T":1,"H":3,"A":2},{"T":19,"H":3,"A":2},{"T":2,"H":2,"A":0}]}
                """);
        Optional<NesineScoreParser.ResolvedScore> score = NesineScoreParser.resolveFinishedRow(row);
        assertTrue(score.isPresent());
        assertEquals(1, score.get().hthg());
        assertEquals(2, score.get().htag());
        assertEquals(3, score.get().fthg());
        assertEquals(2, score.get().ftag());
        assertTrue(score.get().secondHalfConsistent());
    }

    private JsonNode sampleRow(long eventId) throws Exception {
        for (JsonNode row : mapper.readTree("""
                {"d":[
                  {"S":4,"C":2903307,"NID":2903307,"HTTR":"Devonport C.","ATTR":"Ulverstone",
                   "ES":[{"T":1,"H":1,"A":0},{"T":19,"H":1,"A":0},{"T":2,"H":0,"A":0}]},
                  {"S":4,"C":2903028,"NID":2903028,"HTTR":"Gangneung City","ATTR":"Changwon City",
                   "ES":[{"T":1,"H":0,"A":1},{"T":19,"H":0,"A":1},{"T":2,"H":0,"A":1}]}
                ]}
                """).path("d")) {
            if (row.path("C").asLong() == eventId) {
                return row;
            }
        }
        throw new IllegalStateException("Sample row not found: " + eventId);
    }
}
