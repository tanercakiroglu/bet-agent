package com.betagent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamNameMatcherTest {

    @Test
    void matchesAbbreviations() {
        assertTrue(TeamNameMatcher.teamsMatch("Devonport", "Ulverstone FC", "Devonport C.", "Ulverstone"));
    }

    @Test
    void matchesTurkishAndEnglishSpellings() {
        assertTrue(TeamNameMatcher.teamsMatch("Ostersunds FK", "Orebro SK", "Ostersund", "Orebro"));
        assertTrue(TeamNameMatcher.teamsMatch("Galatasaray", "Fenerbahce", "Galatasaray SK", "Fenerbahçe"));
    }

    @Test
    void rejectsDifferentOpponents() {
        assertFalse(TeamNameMatcher.teamsMatch("PSG", "Arsenal", "Lyon", "Arsenal"));
    }

    @Test
    void matchesClubSuffixVariants() {
        assertTrue(TeamNameMatcher.sideMatches("KAA Gent", "Gent"));
        assertTrue(TeamNameMatcher.sideMatches("KRC Genk", "Genk"));
    }
}
