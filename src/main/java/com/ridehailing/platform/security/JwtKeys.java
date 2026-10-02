package com.ridehailing.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.ridehailing.platform.DevelopmentProfiles;
import com.ridehailing.shared.Ids;
import java.text.ParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The access-token keys (LLD §12.3): the configured ones, or, in the local and test profiles only, one generated in
 * memory at startup.
 */
@Component
class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    private final ECKey signingKey;
    private final JWKSet verificationKeys;

    JwtKeys(JwtProperties properties, Environment environment) {
        List<ECKey> keys = properties.keys().stream().map(JwtKeys::parse).toList();
        if (keys.isEmpty()) {
            if (!DevelopmentProfiles.active(environment)) {
                throw new IllegalStateException(
                        "ride.security.jwt.keys must list the signing keys outside the local and test profiles");
            }
            keys = List.of(generate());
            log.warn("Signing access tokens with a key generated in memory; tokens issued before a restart are refused");
        }
        this.signingKey = keys.getFirst();
        this.verificationKeys = new JWKSet(keys.stream().<JWK>map(ECKey::toPublicJWK).toList());
    }

    ECKey signingKey() {
        return signingKey;
    }

    JWKSet verificationKeys() {
        return verificationKeys;
    }

    // Messages never include the key material.
    private static ECKey parse(String json) {
        ECKey key;
        try {
            key = ECKey.parse(json);
        } catch (ParseException e) {
            throw new IllegalStateException("A ride.security.jwt.keys entry is not an EC JWK", e);
        }
        if (!key.isPrivate() || !Curve.P_256.equals(key.getCurve()) || key.getKeyID() == null) {
            throw new IllegalStateException("Signing keys must be EC P-256 private keys with a kid");
        }
        return key;
    }

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("generated-" + Ids.newId()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate a signing key", e);
        }
    }
}
