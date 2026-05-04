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
 */
public final class OtpMailer {

    private OtpMailer() {
    }

    public static void sendPasswordResetCode(String toEmail, String otp) throws MessagingException {
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
        msg.setSubject(MailConfig.mailSubject(), "UTF-8");
        msg.setText(
                "Your AuctionHub password reset code is: " + otp + "\n\n"
                        + "This code expires in 5 minutes. If you did not request a reset, you can ignore this email.",
                "UTF-8");

        Transport.send(msg);
    }
}
