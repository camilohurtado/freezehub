package com.freezhub.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends an announcement by email (FZ-042).
 *
 * <p>Plain SMTP through {@link JavaMailSender} rather than the AWS SES SDK. SES exposes an
 * SMTP endpoint, so the same code runs against a local mail catcher in development and
 * against SES in a deployed environment (02-architecture.md) — no second implementation,
 * and no local/real split of the kind Cognito needed.
 *
 * <p>Only registered when a from-address is configured. Without one the dispatcher finds
 * no EMAIL sender and defers those notifications **without consuming an attempt**, which
 * is right: an unconfigured mail server is a configuration gap, not a delivery failure,
 * and it must not exhaust a notification's retry budget (FZ-044).
 */
@Component
@ConditionalOnProperty(name = "freezehub.notifications.email.from")
public class EmailNotificationSender implements NotificationSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailNotificationSender(JavaMailSender mailSender,
                                   @Value("${freezehub.notifications.email.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.EMAIL;
    }

    @Override
    public void send(Notification notification, ChangeRestriction restriction, Integration destination) {
        List<String> recipients = recipientsOf(destination);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipients.toArray(String[]::new));
        message.setSubject(NotificationMessage.headline(notification.getEvent(), restriction));
        message.setText(NotificationMessage.body(notification.getEvent(), restriction));

        try {
            mailSender.send(message);
        } catch (MailException failed) {
            // The message, not the exception's toString: the latter can include recipient
            // addresses and server detail that end up in notification.last_error.
            throw new NotificationDeliveryException(
                    "Email could not be sent: " + failed.getClass().getSimpleName());
        }
    }

    private List<String> recipientsOf(Integration destination) {
        try {
            JsonNode config = MAPPER.readTree(destination.getConfig());
            JsonNode recipients = config.path("recipients");
            if (!recipients.isArray() || recipients.isEmpty()) {
                throw new NotificationDeliveryException("Email integration has no recipients configured");
            }

            List<String> addresses = new ArrayList<>();
            recipients.forEach(recipient -> addresses.add(recipient.asText()));
            return addresses;
        } catch (JsonProcessingException unreadable) {
            throw new NotificationDeliveryException("Email integration config is not valid JSON");
        }
    }

}
