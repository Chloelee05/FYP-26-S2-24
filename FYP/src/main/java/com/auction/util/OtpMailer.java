package com.auction.util;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

/**
 * Sends the password-reset OTP over SMTP using {@link MailConfig}.
 *
 * <p>Every outgoing email in the system goes through the private {@code send} here, which
 * reads the server, port, credentials and From address from {@link MailConfig}, that is
 * from the {@code AUCTION_SMTP_HOST}, {@code AUCTION_SMTP_PORT}, {@code AUCTION_SMTP_USER},
 * {@code AUCTION_SMTP_PASSWORD} and {@code AUCTION_MAIL_FROM} environment variables. All
 * messages are plain text.</p>
 *
 * <p>Sending is synchronous and blocks for the whole SMTP conversation. That is acceptable
 * for the OTP methods, where the user is waiting on the code anyway, but not for bulk
 * notification mail: {@code NotificationService} therefore calls
 * {@link #sendNotification} from a background thread rather than a request thread.</p>
 */
public final class OtpMailer {

    private OtpMailer() {
    }

    /** Password reset code. The five minute expiry matches {@link OtpStore}'s TTL. */
    public static void sendPasswordResetCode(String toEmail, String otp) throws MessagingException {
        send(toEmail, MailConfig.mailSubject(),
                "Your AuctionHub password reset code is: " + otp + "\n\n"
                        + "This code expires in 5 minutes. If you did not request a reset, you can ignore this email.");
    }

    /** Second factor at login, for accounts with email-based 2FA rather than an app. */
    public static void sendTwoFactorCode(String toEmail, String otp) throws MessagingException {
        send(toEmail, "AuctionHub login verification code",
                "Your AuctionHub login verification code is: " + otp + "\n\n"
                        + "This code expires in 5 minutes. If you did not attempt to log in, please secure your account.");
    }

    /** Generic transactional email (e.g. bidding-result and account notifications). */
    public static void sendNotification(String toEmail, String subject, String body) throws MessagingException {
        send(toEmail, subject, body);
    }

    /**
     * Builds a session from the current {@link MailConfig} values and sends one message.
     *
     * <p>A fresh session per send rather than a shared one, so a rotated credential or a
     * changed host takes effect without a restart, and so nothing holds the SMTP password
     * in a static field. The transport security branch is exclusive: implicit SSL wraps the
     * connection from the start, STARTTLS upgrades it afterwards, and configuring both
     * would be contradictory.</p>
     *
     * <p>The authenticator is only attached when authentication is on and a user is
     * actually configured, since some relays reject an AUTH attempt they did not ask
     * for.</p>
     */
    private static void send(String toEmail, String subject, String body) throws MessagingException {
        Properties props = new Properties();
        String host = MailConfig.smtpHost();
        int port = MailConfig.smtpPort();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));

        boolean auth = MailConfig.smtpAuth();
        props.put("mail.smtp.auth", String.valueOf(auth));

        if (MailConfig.implicitSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if (MailConfig.startTls()) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        String user = MailConfig.smtpUser();
        String pass = MailConfig.smtpPassword();
        Authenticator authenticator = null;
        if (auth && user != null && !user.isBlank()) {
            authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass);
                }
            };
        }

        Session session = Session.getInstance(props, authenticator);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(MailConfig.mailFrom()));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
        msg.setSubject(subject, "UTF-8");
        msg.setText(body, "UTF-8");

        Transport.send(msg);
    }
}
