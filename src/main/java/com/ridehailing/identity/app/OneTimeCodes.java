package com.ridehailing.identity.app;

import com.ridehailing.platform.DevelopmentProfiles;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Makes six-digit codes and their HMACs (LLD §12.1). A fixed code, or a secret generated at startup, is refused
 * outside the local and test profiles, so neither can reach production by accident.
 */
@Component
class OneTimeCodes {

    private static final Logger log = LoggerFactory.getLogger(OneTimeCodes.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final int MIN_SECRET_BYTES = 32;

    private final String fixedCode;
    private final SecretKeySpec key;

    OneTimeCodes(SignInProperties properties, Environment environment) {
        boolean development = DevelopmentProfiles.active(environment);
        if (properties.fixedCode() != null && (!development || !CODE.matcher(properties.fixedCode()).matches())) {
            throw new IllegalStateException(
                    "ride.security.otp.fixed-code must be six digits and is allowed only in the local and test profiles");
        }
        this.fixedCode = properties.fixedCode();
        this.key = new SecretKeySpec(secret(properties.hmacSecret(), development), "HmacSHA256");
    }

    String newCode() {
        return fixedCode != null ? fixedCode : "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    /** Covers the challenge ID too, so the same code in two challenges has two different HMACs. */
    byte[] hmac(UUID challengeId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** Compares in constant time, so response timing reveals nothing about the code. */
    boolean matches(UUID challengeId, String code, byte[] expected) {
        return MessageDigest.isEqual(hmac(challengeId, code), expected);
    }

    private static byte[] secret(String configured, boolean development) {
        if (configured != null) {
            byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
            if (secret.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("ride.security.otp.hmac-secret must be at least 32 bytes");
            }
            return secret;
        }
        if (!development) {
            throw new IllegalStateException("ride.security.otp.hmac-secret is required outside the local and test profiles");
        }
        log.warn("Using a one-time-code secret generated in memory; codes sent before a restart are refused");
        byte[] secret = new byte[MIN_SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }
}
