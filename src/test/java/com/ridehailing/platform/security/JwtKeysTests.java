package com.ridehailing.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** LLD §12.3: configured keys, the first signing and all verifying; generated keys only in local and test. */
class JwtKeysTests {

    @Test
    void theFirstConfiguredKeySignsAndAllVerifyWithoutTheirPrivateParts() throws JOSEException {
        ECKey current = key("2026-10");
        ECKey previous = key("2026-07");

        JwtKeys keys = new JwtKeys(properties(current.toJSONString(), previous.toJSONString()), new MockEnvironment());

        assertThat(keys.signingKey().getKeyID()).isEqualTo("2026-10");
        assertThat(keys.verificationKeys().getKeys()).extracting(JWK::getKeyID).containsExactly("2026-10", "2026-07");
        assertThat(keys.verificationKeys().getKeys()).noneMatch(JWK::isPrivate);
    }

    @Test
    void keysMustBePrivateP256WithAKidAndErrorsNeverShowThem() throws JOSEException {
        ECKey valid = key("k1");
        List<String> invalid = List.of(
                valid.toPublicJWK().toJSONString(),
                new ECKeyGenerator(Curve.P_384).keyID("k2").generate().toJSONString(),
                new ECKeyGenerator(Curve.P_256).generate().toJSONString(),
                "not json");

        for (String entry : invalid) {
            assertThatThrownBy(() -> new JwtKeys(properties(entry), new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(valid.getD().toString()));
        }
    }

    @Test
    void withoutKeysTheLocalAndTestProfilesGenerateOne() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        JwtKeys keys = new JwtKeys(properties(), local);

        assertThat(keys.signingKey().isPrivate()).isTrue();
        assertThat(keys.verificationKeys().getKeys()).hasSize(1);
    }

    @Test
    void withoutKeysAnyOtherProfileRefusesToStart() {
        assertThatThrownBy(() -> new JwtKeys(properties(), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ride.security.jwt.keys");
    }

    private static JwtProperties properties(String... keys) {
        return new JwtProperties("ride-hailing", Duration.ofMinutes(15), List.of(keys));
    }

    private static ECKey key(String keyId) throws JOSEException {
        return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
    }
}
