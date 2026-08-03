package com.auction.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The flag is read straight from the environment, which a unit test cannot set, so these
 * pin down the property that actually matters: the deployment default. Every environment
 * that has not deliberately opted in — including Render, which sets nothing — must read as
 * disabled, and no other setting may switch it on as a side effect.
 */
@DisplayName("DevMode – development-only OTP disclosure")
class DevModeTest {

    @Test
    @DisplayName("is off when AUCTION_DEV_MODE is not set")
    void offByDefault() {
        assertNull(System.getenv("AUCTION_DEV_MODE"),
                "this test asserts the default, so the variable must be absent from the build env");
        assertFalse(DevMode.isEnabled());
    }

    @Test
    @DisplayName("does not track SMTP configuration")
    void independentOfSmtp() {
        // The original bug: an unset AUCTION_SMTP_HOST was read as "this is a dev box".
        assertFalse(MailConfig.isSmtpConfigured());
        assertFalse(DevMode.isEnabled(),
                "an unconfigured mailer must not enable OTP disclosure");
    }
}
