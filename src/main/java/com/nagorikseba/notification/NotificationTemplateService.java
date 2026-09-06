package com.nagorikseba.notification;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Renders notification templates (N12, §7.4).
 *
 * <p>Templates live in {@code messages_en.properties} /
 * {@code messages_bn.properties} with {@code {referenceCode}}, {@code {status}}
 * and {@code {note}} placeholders. Unknown codes fall back to a generic
 * English line so a missing key degrades instead of crashing the flow.
 */
@Service
public class NotificationTemplateService {

    public static final String DEFAULT_LOCALE = "bn";

    private static final Map<String, String> FALLBACK_EN = Map.of(
            "text", "Update on complaint {referenceCode}. Current status: {status}. {note}");

    public String render(String templateCode, String locale, Map<String, String> variables) {
        String template = lookup(templateCode, locale);
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}",
                    entry.getValue() != null ? entry.getValue() : "");
        }
        return rendered.replaceAll("\\s+", " ").trim();
    }

    /** Template codes this build can render (used by tests and the dispatcher). */
    public boolean supports(String templateCode) {
        try {
            ResourceBundle.getBundle("messages_en", Locale.ENGLISH);
            return lookupRaw(templateCode, "en") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String lookup(String templateCode, String locale) {
        String localized = lookupRaw(templateCode, locale);
        if (localized != null) {
            return localized;
        }
        String english = lookupRaw(templateCode, "en");
        if (english != null) {
            return english;
        }
        return FALLBACK_EN.get("text");
    }

    private String lookupRaw(String templateCode, String locale) {
        try {
            Locale bundleLocale = "bn".equalsIgnoreCase(locale)
                    ? new Locale("bn") : Locale.ENGLISH;
            String base = "bn".equalsIgnoreCase(locale) ? "messages_bn" : "messages_en";
            return ResourceBundle.getBundle(base, bundleLocale).getString(templateCode);
        } catch (Exception e) {
            return null;
        }
    }
}
