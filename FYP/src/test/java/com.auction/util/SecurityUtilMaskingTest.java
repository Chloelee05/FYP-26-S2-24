package com.auction.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SCRUM-175: Identity masking matches project-defined patterns (proposal example + edge cases).
 */
@DisplayName("SecurityUtil — PDPA masking (SCRUM-175)")
class SecurityUtilMaskingTest {

    @Test
    @DisplayName("proposal example: lee170@mymail.sim.edu.sg → l***0@mymail.sim.edu.sg")
    void maskEmail_proposalExample() {
        assertEquals(
                "l***0@mymail.sim.edu.sg",
                SecurityUtil.maskEmail("lee170@mymail.sim.edu.sg"));
    }

    @ParameterizedTest
    @CsvSource({
            "a@b.co, *@b.co",
            "ab@x.org, a*@x.org",
            "abc@dom.example, a***c@dom.example",
            "  spaced@mail.edu  , s***d@mail.edu"
    })
    @DisplayName("maskEmail local-part rules by length; domain unchanged; trim")
    void maskEmail_patterns(String raw, String expected) {
        assertEquals(expected, SecurityUtil.maskEmail(raw));
    }

    @Test
    @DisplayName("maskEmail null returns null")
    void maskEmail_null() {
        assertNull(SecurityUtil.maskEmail(null));
    }

    @Test
    @DisplayName("maskEmail blank after trim returns empty")
    void maskEmail_blankTrimmed() {
        assertEquals("", SecurityUtil.maskEmail("   "));
    }

    @Test
    @DisplayName("maskEmail without @ applies token mask to whole string")
    void maskEmail_noAtSign() {
        assertEquals("l***e", SecurityUtil.maskEmail("lee170nope"));
    }

    @Test
    @DisplayName("maskUsername applies per-word masking; preserves single space between words")
    void maskUsername_multiWord() {
        assertEquals("J***n M***l", SecurityUtil.maskUsername("John Michael"));
    }

    @ParameterizedTest
    @CsvSource({
            "A, *",
            "Al, A*",
            "Ali, A***i",
            "  Bob  , B***b"
    })
    @DisplayName("maskUsername single-token lengths")
    void maskUsername_singleToken(String raw, String expected) {
        assertEquals(expected, SecurityUtil.maskUsername(raw));
    }

    @Test
    @DisplayName("maskUsername null returns null")
    void maskUsername_null() {
        assertNull(SecurityUtil.maskUsername(null));
    }

    @Test
    @DisplayName("maskUsername blank trimmed returns empty")
    void maskUsername_blank() {
        assertEquals("", SecurityUtil.maskUsername("\t  "));
    }
}
