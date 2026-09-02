package com.nagorikseba.identity.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The forensic columns on {@code refresh_tokens}: where a session was issued from.
 *
 * @param ipAddress client address, or {@code null} when it could not be parsed
 * @param userAgent request User-Agent, truncated to the column width
 */
public record ClientInfo(InetAddress ipAddress, String userAgent) {

    private static final Logger log = LoggerFactory.getLogger(ClientInfo.class);

    /** Only literals — never a name that would send the server off to resolve DNS. */
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F.:%]+$");

    private static final int USER_AGENT_MAX = 255;

    public static final ClientInfo UNKNOWN = new ClientInfo(null, null);

    public static ClientInfo of(String ip, String userAgent) {
        return new ClientInfo(parseAddress(ip), truncate(userAgent));
    }

    /**
     * Reads the peer address straight off the request.
     *
     * <p>Deliberately ignores {@code X-Forwarded-For}: it is client-controlled unless a
     * trusted proxy overwrites it, and this column is used for audit. Behind a reverse
     * proxy, configure {@code server.forward-headers-strategy} so
     * {@code getRemoteAddr()} already reports the real client.
     */
    public static ClientInfo from(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        return of(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    private static InetAddress parseAddress(String ip) {
        if (ip == null || ip.isBlank() || !IP_LITERAL.matcher(ip).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            log.debug("Unparseable remote address, storing null");
            return null;
        }
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String trimmed = userAgent.trim();
        return trimmed.length() <= USER_AGENT_MAX ? trimmed : trimmed.substring(0, USER_AGENT_MAX);
    }
}
