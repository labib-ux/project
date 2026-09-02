package com.nagorikseba.identity.service;

import java.util.Locale;

/**
 * Canonical forms for the two login identifiers (§8.1).
 *
 * <p>Bangladeshi mobile numbers arrive as {@code +8801XXXXXXXXX},
 * {@code 8801XXXXXXXXX} or {@code 01XXXXXXXXX}, and users type separators. All of
 * them collapse to {@code 01XXXXXXXXX} so the unique index actually prevents the
 * same person registering twice. Emails are trimmed and lower-cased; the column is
 * CITEXT as well, which makes uniqueness case-insensitive at the index level.
 */
public final class IdentifierNormalizer {

    /** Digits, dots and colons only — enough to recognise a literal, never a hostname. */
    private static final String SEPARATORS = "[\\s\\-()]";

    private IdentifierNormalizer() {
    }

    /** @return lower-cased, trimmed email, or {@code null} when blank */
    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** @return {@code 01XXXXXXXXX}, or {@code null} when blank */
    public static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.trim().replaceAll(SEPARATORS, "");
        if (digits.startsWith("+880")) {
            return "0" + digits.substring(4);
        }
        if (digits.startsWith("880")) {
            return "0" + digits.substring(3);
        }
        return digits;
    }

    /**
     * Normalizes a login identifier without knowing which kind it is: anything
     * containing {@code @} is treated as an email, everything else as a phone.
     */
    public static String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        return identifier.contains("@") ? normalizeEmail(identifier) : normalizePhone(identifier);
    }
}
