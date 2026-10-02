package com.nagorikseba.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nagorikseba.shared.outbox.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Production SMS sender against the Twilio Messages API (N7).
 *
 * <p>No Twilio SDK on the classpath by design: one {@code java.net.http} POST
 * to {@code /Accounts/{sid}/Messages.json} is the whole integration, and it
 * keeps the dependency footprint (and its CVEs) unchanged. Actually delivers
 * when {@code app.notifications.sms-enabled=true} with SID/token/from set;
 * otherwise logs and returns, so prod deploys without Twilio credentials
 * behave like the dev sender instead of parking every row FAILED. Non-2xx
 * responses throw, which is the worker's retry signal.
 */
@Component
@Profile("prod")
@Slf4j
public class TwilioSmsSender implements ChannelSender {

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String sid;
    private final String token;
    private final String from;
    private final HttpClient httpClient;

    public TwilioSmsSender(ObjectMapper objectMapper,
                           @Value("${app.notifications.sms-enabled:false}") boolean enabled,
                           @Value("${app.notifications.twilio-sid:}") String sid,
                           @Value("${app.notifications.twilio-token:}") String token,
                           @Value("${app.notifications.twilio-from:}") String from) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.sid = sid;
        this.token = token;
        this.from = from;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channel() {
        return "SMS";
    }

    @Override
    public void send(OutboxMessage message) throws Exception {
        JsonNode payload = readPayload(message);
        String to = payload.path("to").asText(null);
        if (!enabled || sid.isBlank() || token.isBlank() || from.isBlank()) {
            log.info("PROD SMS (not configured, logged) outbox_id={} to={} payload={}",
                    message.getId(), to, message.getPayload());
            return;
        }
        if (to == null || to.isBlank()) {
            throw new IllegalStateException("SMS_SEND row " + message.getId() + " has no recipient");
        }
        String body = "To=" + encode(to)
                + "&From=" + encode(from)
                + "&Body=" + encode(payload.path("text").asText(""));
        String credentials = Base64.getEncoder().encodeToString(
                (sid + ":" + token).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Twilio rejected SMS for outbox row " + message.getId()
                    + " (HTTP " + response.statusCode() + "): " + response.body());
        }
        log.info("PROD SMS sent outbox_id={} to={}", message.getId(), to);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode readPayload(OutboxMessage message) {
        try {
            return objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Outbox row " + message.getId() + " carries invalid JSON", e);
        }
    }
}
