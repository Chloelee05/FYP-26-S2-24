import com.auction.model.Role;
import com.auction.model.admin.Announcement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the system-wide announcement model: input rules, audience/severity parsing,
 * text normalization and the notification/email rendering every broadcast depends on.
 */
@DisplayName("Announcement — system-wide announcement model")
class TestAnnouncementModel {

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder(times);
        for (int i = 0; i < times; i++) sb.append(c);
        return sb.toString();
    }

    @Nested
    @DisplayName("title validation")
    class TitleRules {

        @Test
        @DisplayName("a normal title is accepted")
        void accepted() {
            assertNull(Announcement.violationForTitle("Scheduled maintenance"));
        }

        @Test
        @DisplayName("null, blank and whitespace-only titles are rejected as required")
        void required() {
            assertTrue(Announcement.violationForTitle(null).contains("required"));
            assertTrue(Announcement.violationForTitle("").contains("required"));
            assertTrue(Announcement.violationForTitle("   \t ").contains("required"));
        }

        @Test
        @DisplayName("a title at the limit passes, one character over fails")
        void lengthBoundary() {
            assertNull(Announcement.violationForTitle(repeat('a', Announcement.TITLE_MAX_LENGTH)));
            String violation = Announcement.violationForTitle(repeat('a', Announcement.TITLE_MAX_LENGTH + 1));
            assertNotNull(violation);
            assertTrue(violation.contains(String.valueOf(Announcement.TITLE_MAX_LENGTH)));
        }

        @Test
        @DisplayName("a multi-line title is rejected — it has to fit one notification line")
        void singleLine() {
            assertTrue(Announcement.violationForTitle("Maintenance\nwindow").contains("single line"));
        }
    }

    @Nested
    @DisplayName("message validation")
    class MessageRules {

        @Test
        @DisplayName("a multi-line body is accepted")
        void accepted() {
            assertNull(Announcement.violationForMessage("We are upgrading.\n\nExpect 30 minutes of downtime."));
        }

        @Test
        @DisplayName("null and blank messages are rejected as required")
        void required() {
            assertTrue(Announcement.violationForMessage(null).contains("required"));
            assertTrue(Announcement.violationForMessage("  ").contains("required"));
        }

        @Test
        @DisplayName("a message at the limit passes, one character over fails")
        void lengthBoundary() {
            assertNull(Announcement.violationForMessage(repeat('x', Announcement.MESSAGE_MAX_LENGTH)));
            assertNotNull(Announcement.violationForMessage(repeat('x', Announcement.MESSAGE_MAX_LENGTH + 1)));
        }
    }

    @Nested
    @DisplayName("link validation")
    class LinkRules {

        @Test
        @DisplayName("the link is optional")
        void optional() {
            assertNull(Announcement.violationForLink(null));
            assertNull(Announcement.violationForLink("   "));
        }

        @Test
        @DisplayName("an in-app path is accepted")
        void internalPath() {
            assertNull(Announcement.violationForLink("/profile"));
            assertNull(Announcement.violationForLink("/admin/rules?tab=fees"));
        }

        @Test
        @DisplayName("an absolute URL is rejected — the bell routes in-app only")
        void absoluteUrlRejected() {
            assertNotNull(Announcement.violationForLink("https://evil.example.com"));
            assertNotNull(Announcement.violationForLink("http://example.com/status"));
            assertNotNull(Announcement.violationForLink("javascript:alert(1)"));
        }

        @Test
        @DisplayName("a protocol-relative link is rejected — it leaves the site")
        void protocolRelativeRejected() {
            String violation = Announcement.violationForLink("//evil.example.com/phish");
            assertNotNull(violation);
            assertTrue(violation.contains("in-app path"));
        }

        @Test
        @DisplayName("an over-long link is rejected")
        void tooLong() {
            assertNotNull(Announcement.violationForLink("/" + repeat('a', Announcement.LINK_MAX_LENGTH)));
        }

        @Test
        @DisplayName("a link containing a space is rejected")
        void noSpaces() {
            assertNotNull(Announcement.violationForLink("/my page"));
        }
    }

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("trims and returns null for empty input")
        void trimsAndNulls() {
            assertEquals("hello", Announcement.normalize("  hello  "));
            assertNull(Announcement.normalize(null));
            assertNull(Announcement.normalize("   "));
        }

        @Test
        @DisplayName("CRLF becomes LF and control characters are dropped")
        void stripsControlCharacters() {
            assertEquals("a\nb", Announcement.normalize("a\r\nb"));
            assertEquals("a\nb", Announcement.normalize("a\rb"));
            assertEquals("ab", Announcement.normalize("a\0\7b"));   // NUL + BEL
            assertEquals("a\tb", Announcement.normalize("a\tb"));
        }

        @Test
        @DisplayName("apostrophes and angle brackets survive verbatim — escaping happens on output")
        void doesNotEscape() {
            String typed = "We'll pause bidding & \"finalise\" <all> orders";
            assertEquals(typed, Announcement.normalize(typed));
        }

        @Test
        @DisplayName("is idempotent")
        void idempotent() {
            String once = Announcement.normalize("  a\r\n b \0 ");
            assertEquals(once, Announcement.normalize(once));
        }
    }

    @Nested
    @DisplayName("audience")
    class Audiences {

        @Test
        @DisplayName("parses case-insensitively and rejects anything else")
        void parsing() {
            assertEquals(Announcement.Audience.ALL, Announcement.Audience.parse("all"));
            assertEquals(Announcement.Audience.BUYERS, Announcement.Audience.parse("Buyers"));
            assertEquals(Announcement.Audience.SELLERS, Announcement.Audience.parse(" SELLERS "));
            assertNull(Announcement.Audience.parse("everyone"));
            assertNull(Announcement.Audience.parse(""));
            assertNull(Announcement.Audience.parse(null));
        }

        @Test
        @DisplayName("maps to the role the fan-out filters on; ALL means no filter")
        void roleMapping() {
            assertNull(Announcement.Audience.ALL.role());
            assertEquals(Role.BUYER, Announcement.Audience.BUYERS.role());
            assertEquals(Role.SELLER, Announcement.Audience.SELLERS.role());
        }

        @Test
        @DisplayName("describes itself for the confirmation message")
        void describes() {
            assertEquals("all users", Announcement.Audience.ALL.describe());
            assertEquals("buyers", Announcement.Audience.BUYERS.describe());
        }
    }

    @Nested
    @DisplayName("severity")
    class Severities {

        @Test
        @DisplayName("parses case-insensitively and rejects anything else")
        void parsing() {
            assertEquals(Announcement.Severity.INFO, Announcement.Severity.parse("info"));
            assertEquals(Announcement.Severity.CRITICAL, Announcement.Severity.parse("CRITICAL"));
            assertNull(Announcement.Severity.parse("urgent"));
            assertNull(Announcement.Severity.parse(null));
        }

        @Test
        @DisplayName("only escalated severities prefix the email subject")
        void emailPrefix() {
            assertNull(Announcement.Severity.INFO.emailPrefix());
            assertEquals("Important", Announcement.Severity.WARNING.emailPrefix());
            assertEquals("Urgent", Announcement.Severity.CRITICAL.emailPrefix());
        }
    }

    @Nested
    @DisplayName("composition and rendering")
    class Rendering {

        private Announcement composed(Announcement.Severity severity) {
            return Announcement.compose("Scheduled maintenance", "Bidding pauses at 02:00.",
                    Announcement.Audience.ALL, severity, "/profile", 7);
        }

        @Test
        @DisplayName("compose starts unsent: no id, no timestamp, no recipients")
        void composeDefaults() {
            Announcement a = composed(Announcement.Severity.INFO);
            assertEquals(0L, a.getId());
            assertNull(a.getCreatedAt());
            assertEquals(0, a.getRecipientCount());
            assertEquals(Integer.valueOf(7), a.getCreatedBy());
        }

        @Test
        @DisplayName("compose falls back to ALL / INFO when unspecified")
        void composeFallbacks() {
            Announcement a = Announcement.compose("T", "M", null, null, null, null);
            assertEquals(Announcement.Audience.ALL, a.getAudience());
            assertEquals(Announcement.Severity.INFO, a.getSeverity());
            assertNull(a.getLink());
            assertNull(a.getCreatedBy());
        }

        @Test
        @DisplayName("the constructor normalizes the text it is given")
        void constructorNormalizes() {
            Announcement a = Announcement.compose("  Padded title  ", "  Body\r\ntext  ",
                    Announcement.Audience.BUYERS, Announcement.Severity.WARNING, "  /profile  ", 1);
            assertEquals("Padded title", a.getTitle());
            assertEquals("Body\ntext", a.getMessage());
            assertEquals("/profile", a.getLink());
        }

        @Test
        @DisplayName("stored keeps the content and adds the id, timestamp and reach")
        void storedCopy() {
            Instant at = Instant.parse("2026-07-27T09:00:00Z");
            Announcement sent = composed(Announcement.Severity.WARNING).stored(42L, at, 130);

            assertEquals(42L, sent.getId());
            assertEquals(at, sent.getCreatedAt());
            assertEquals(130, sent.getRecipientCount());
            assertEquals("Scheduled maintenance", sent.getTitle());
            assertEquals("Bidding pauses at 02:00.", sent.getMessage());
            assertEquals(Announcement.Severity.WARNING, sent.getSeverity());
            assertEquals("/profile", sent.getLink());
            assertEquals(7, sent.getCreatedBy());
        }

        @Test
        @DisplayName("a negative recipient count is clamped to zero")
        void clampsRecipients() {
            assertEquals(0, composed(Announcement.Severity.INFO).stored(1L, Instant.now(), -5)
                    .getRecipientCount());
        }

        @Test
        @DisplayName("the notification line folds the title into the message")
        void notificationMessage() {
            String line = composed(Announcement.Severity.INFO).toNotificationMessage();
            assertTrue(line.startsWith("Scheduled maintenance"));
            assertTrue(line.contains("Bidding pauses at 02:00."));
        }

        @Test
        @DisplayName("the email subject carries the title, and the urgency when escalated")
        void emailSubject() {
            assertEquals("AuctionHub announcement: Scheduled maintenance",
                    composed(Announcement.Severity.INFO).toEmailSubject());
            assertEquals("AuctionHub announcement (Urgent): Scheduled maintenance",
                    composed(Announcement.Severity.CRITICAL).toEmailSubject());
        }

        @Test
        @DisplayName("the email body carries the title, message and audience")
        void emailBody() {
            String body = composed(Announcement.Severity.INFO).toEmailBody();
            assertTrue(body.contains("Scheduled maintenance"));
            assertTrue(body.contains("Bidding pauses at 02:00."));
            assertTrue(body.contains("all users"));
        }

        @Test
        @DisplayName("the fan-out type is stable — the notification feed filters on it")
        void notificationType() {
            assertEquals("ANNOUNCEMENT", Announcement.NOTIFICATION_TYPE);
        }
    }
}
