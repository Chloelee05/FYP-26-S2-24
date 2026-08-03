package com.auction.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LoginAttemptLimiter — brute-force lockout per account")
class LoginAttemptLimiterTest {

    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        // A fresh instance per test — never the process-wide getInstance() singleton — so
        // tests can never see another test's leftover state.
        limiter = new LoginAttemptLimiter();
    }

    @Test
    @DisplayName("N consecutive failures lock the account out")
    void thresholdFailuresLockOut() {
        String key = "victim@email.com";
        for (int i = 0; i < 4; i++) {
            assertFalse(limiter.recordFailure(key, 5, Duration.ofMinutes(15)),
                    "should not lock out before the threshold is reached");
        }
        assertFalse(limiter.isLockedOut(key));

        assertTrue(limiter.recordFailure(key, 5, Duration.ofMinutes(15)),
                "the 5th consecutive failure must trigger the lockout");
        assertTrue(limiter.isLockedOut(key));
    }

    @Test
    @DisplayName("fewer than the threshold does not lock the account out")
    void belowThresholdNoLockout() {
        String key = "user@email.com";
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(key, 5, Duration.ofMinutes(15));
        }
        assertFalse(limiter.isLockedOut(key));
        assertEquals(0, limiter.lockoutSecondsRemaining(key));
    }

    @Test
    @DisplayName("lockout returns a distinct, positive remaining time")
    void lockoutReportsRemainingTime() {
        String key = "user@email.com";
        Duration cooldown = Duration.ofMinutes(15);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(key, 5, cooldown);
        }
        long remaining = limiter.lockoutSecondsRemaining(key);
        assertTrue(remaining > 0 && remaining <= cooldown.getSeconds(),
                "remaining time should be positive and no more than the configured cooldown");
    }

    @Test
    @DisplayName("the cooldown expires and access is restored")
    void cooldownExpires() throws InterruptedException {
        String key = "user@email.com";
        Duration shortCooldown = Duration.ofMillis(50);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(key, 5, shortCooldown);
        }
        assertTrue(limiter.isLockedOut(key));

        Thread.sleep(120);

        assertFalse(limiter.isLockedOut(key), "the lockout must clear itself once the cooldown has passed");
        assertEquals(0, limiter.lockoutSecondsRemaining(key));
    }

    @Test
    @DisplayName("a successful login before the threshold resets the counter")
    void successResetsCounter() {
        String key = "user@email.com";
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(key, 5, Duration.ofMinutes(15));
        }
        limiter.recordSuccess(key);

        // A fresh run of failures after the reset must need the full threshold again,
        // not just one more to reach the old running total.
        for (int i = 0; i < 4; i++) {
            assertFalse(limiter.recordFailure(key, 5, Duration.ofMinutes(15)));
        }
        assertFalse(limiter.isLockedOut(key));
    }

    @Test
    @DisplayName("failures against one account never lock out, or leak into, a different account")
    void perAccountIsolation() {
        String attacker = "attacker@email.com";
        String victim = "victim@email.com";

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(attacker, 5, Duration.ofMinutes(15));
        }
        assertTrue(limiter.isLockedOut(attacker));
        assertFalse(limiter.isLockedOut(victim),
                "locking out one account must not affect a different account");

        limiter.recordFailure(victim, 5, Duration.ofMinutes(15));
        assertFalse(limiter.isLockedOut(victim));
    }

    @Test
    @DisplayName("account keys are matched case-insensitively")
    void caseInsensitiveKey() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("User@Email.com", 5, Duration.ofMinutes(15));
        }
        assertTrue(limiter.isLockedOut("user@email.com"));
    }

    @Test
    @DisplayName("repeated failures during an active lockout do not extend it")
    void lockoutIsNotExtendedByFurtherAttempts() {
        String key = "user@email.com";
        Duration cooldown = Duration.ofMillis(150);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(key, 5, cooldown);
        }
        long firstRemaining = limiter.lockoutSecondsRemaining(key);

        // Keep hammering the locked-out account with the same-ish cooldown; none of this
        // should push the expiry further into the future than the original trigger did.
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.recordFailure(key, 5, cooldown));
        }
        assertTrue(limiter.lockoutSecondsRemaining(key) <= firstRemaining,
                "an attacker must not be able to keep the victim locked out indefinitely");
    }
}
