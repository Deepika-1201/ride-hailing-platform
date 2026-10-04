package com.ridehailing.payment.app;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** {@code X-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>." and the raw body>} (LLD §11.4). */
final class WebhookSignatures {

    private static final Pattern HEADER = Pattern.compile("t=([0-9]{1,18}),v1=([0-9a-f]{64})");
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec key;

    WebhookSignatures(byte[] secret) {
        this.key = new SecretKeySpec(secret, "HmacSHA256");
    }

    String sign(Instant at, byte[] body) {
        long seconds = at.getEpochSecond();
        return "t=" + seconds + ",v1=" + HEX.formatHex(hmac(seconds, body));
    }

    /** The signed time if the header is well formed and its HMAC matches, compared in constant time. */
    Optional<Instant> verify(String header, byte[] body) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher parts = HEADER.matcher(header);
        if (!parts.matches()) {
            return Optional.empty();
        }
        long seconds = Long.parseLong(parts.group(1));
        boolean matches = MessageDigest.isEqual(hmac(seconds, body), HEX.parseHex(parts.group(2)));
        return matches ? Optional.of(Instant.ofEpochSecond(seconds)) : Optional.empty();
    }

    private byte[] hmac(long seconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update((seconds + ".").getBytes(US_ASCII));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
