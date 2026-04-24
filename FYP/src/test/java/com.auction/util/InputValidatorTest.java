package com.auction.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SCRUM-198 — JUnit 5 tests for {@link InputValidator} (regex email + password policy / NFR1).
 */
@DisplayName("InputValidator (SCRUM-198)")
class InputValidatorTest {

    @Nested
    @DisplayName("Email — parameterized valid/invalid (SCRUM-199)")
    class EmailParameterizedTests {

        @ParameterizedTest(name = "valid: {0}")
        @ValueSource(strings = {
                "lee170@mymail.sim.edu.sg",
                "test.user@example.com",
                "user+tag@domain.co",
                "a@b.co",
                "Jane_Doe99@sub.mail.edu.sg",
                "user.name+auction@company.org"
        })
        void isValidEmail_acceptsExpectedPatterns(String email) {
            assertTrue(InputValidator.isValidEmail(email), () -> "should accept: " + email);
            assertNull(InputValidator.getEmailFormatViolation(email));
        }

        @ParameterizedTest(name = "invalid: {0}")
        @ValueSource(strings = {
                "plainaddress",
                "@nodomain.com",
                "user@",
                "user@.com",
                "user name@test.com",
                "user@test",
                "..@x.com",
                "user@test.c",
                "user@test..com"
        })
        void isValidEmail_rejectsExpectedPatterns(String email) {
            assertFalse(InputValidator.isValidEmail(email), () -> "should reject: " + email);
            assertNotNull(InputValidator.getEmailFormatViolation(email));
        }

        @ParameterizedTest
        @CsvSource({
                "user@test.com, true",
                "bad, false",
                "missing@at, false"
        })
        void isValidEmail_matchesViolationNull(String email, boolean expectValid) {
            if (expectValid) {
                assertNull(InputValidator.getEmailFormatViolation(email));
            } else {
                assertNotNull(InputValidator.getEmailFormatViolation(email));
            }
        }
    }

    @Nested
    @DisplayName("Password policy — NFR1 strength (SCRUM-200)")
    class PasswordPolicyTests {

        @Test
        @DisplayName("valid password satisfies length, upper, lower, digit, special")
        void meetsPolicy_typicalStrongPassword() {
            String pw = "Str0ng!Pass";
            assertTrue(InputValidator.meetsPasswordPolicy(pw));
            assertNull(InputValidator.getPasswordPolicyViolation(pw));
        }

        @Test
        @DisplayName("minimum length (8) with all character classes")
        void meetsPolicy_exactlyMinLength() {
            String pw = "Aa1!xxxx";
            assertEquals(8, pw.length());
            assertTrue(InputValidator.meetsPasswordPolicy(pw));
        }

        @Test
        @DisplayName("reject when too short")
        void rejects_tooShort() {
            String pw = "Aa1!x";
            assertFalse(InputValidator.meetsPasswordPolicy(pw));
            assertTrue(InputValidator.getPasswordPolicyViolation(pw).contains("at least"));
        }

        @Test
        @DisplayName("reject without uppercase")
        void rejects_missingUppercase() {
            String pw = "weakpw1!";
            assertFalse(InputValidator.meetsPasswordPolicy(pw));
            assertEquals("Password must include at least one uppercase letter.",
                    InputValidator.getPasswordPolicyViolation(pw));
        }

        @Test
        @DisplayName("reject without lowercase")
        void rejects_missingLowercase() {
            String pw = "WEAKPW1!";
            assertFalse(InputValidator.meetsPasswordPolicy(pw));
            assertEquals("Password must include at least one lowercase letter.",
                    InputValidator.getPasswordPolicyViolation(pw));
        }

        @Test
        @DisplayName("reject without digit")
        void rejects_missingDigit() {
            String pw = "WeakPw!!";
            assertFalse(InputValidator.meetsPasswordPolicy(pw));
            assertEquals("Password must include at least one digit.",
                    InputValidator.getPasswordPolicyViolation(pw));
        }

        @Test
        @DisplayName("reject without allowed special character")
        void rejects_missingSpecial() {
            String pw = "WeakPw12";
            assertFalse(InputValidator.meetsPasswordPolicy(pw));
            assertTrue(InputValidator.getPasswordPolicyViolation(pw).contains("special character"));
        }

        @Test
        @DisplayName("each allowed special from policy class is accepted")
        void accepts_eachListedSpecialChar() {
            String[] specials = {"!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "_", "+", "-",
                    "=", "[", "]", "{", "}", "|", ";", ":", ",", ".", "?"};
            for (String s : specials) {
                String pw = "Aa1" + s + "xxxx";
                assertTrue(InputValidator.meetsPasswordPolicy(pw),
                        () -> "should accept special: " + s + " in " + pw);
            }
        }

        @Test
        @DisplayName("getPasswordPolicySummary mentions bounds and rules")
        void summary_nonEmptyAndConsistent() {
            String summary = InputValidator.getPasswordPolicySummary();
            assertNotNull(summary);
            assertTrue(summary.contains(String.valueOf(InputValidator.PASSWORD_MIN_LENGTH)));
            assertTrue(summary.contains(String.valueOf(InputValidator.PASSWORD_MAX_LENGTH)));
        }
    }

    @Nested
    @DisplayName("Edge cases (SCRUM-201)")
    class EdgeCaseTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("email: null, empty, blank — required message")
        void email_nullBlank(String email) {
            assertFalse(InputValidator.isValidEmail(email));
            assertEquals("Email is required.", InputValidator.getEmailFormatViolation(email));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("password: null, empty, blank — required message")
        void password_nullBlank(String password) {
            assertFalse(InputValidator.meetsPasswordPolicy(password));
            assertEquals("Password is required.", InputValidator.getPasswordPolicyViolation(password));
        }

        @Test
        @DisplayName("email longer than 254 characters after trim")
        void email_tooLong() {
            String local = "a".repeat(250);
            String email = local + "@x.co";
            assertEquals(255, email.length());
            assertEquals("Email is too long.", InputValidator.getEmailFormatViolation(email));
        }

        @Test
        @DisplayName("password longer than 128 characters")
        void password_tooLong() {
            String pw = "Aa1!" + "x".repeat(125);
            assertEquals(129, pw.length());
            assertFalse(InputValidator.meetsPasswordPolicy(pw));
            assertTrue(InputValidator.getPasswordPolicyViolation(pw).contains("at most"));
        }

        @Test
        @DisplayName("password exactly max length (128) still valid when policy met")
        void password_boundaryMaxValid() {
            String pw = "Aa1!" + "x".repeat(124);
            assertEquals(128, pw.length());
            assertTrue(InputValidator.meetsPasswordPolicy(pw));
        }

        @Test
        @DisplayName("email trim: leading/trailing spaces on valid address")
        void email_trimsWhitespace() {
            assertTrue(InputValidator.isValidEmail("  user@mail.com  "));
            assertNull(InputValidator.getEmailFormatViolation("  user@mail.com  "));
        }
    }
}
