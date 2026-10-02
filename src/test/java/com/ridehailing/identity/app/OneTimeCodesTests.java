package com.ridehailing.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.shared.Ids;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** LLD §12.1: random six-digit codes, HMACs bound to their challenge, and no fixed code or weak secret in production. */
class OneTimeCodesTests {

    private static final String SECRET = "a-production-secret-of-at-least-32-bytes";

    @Test
    void codesAreSixRandomDigits() {
        OneTimeCodes codes = new OneTimeCodes(properties(null, SECRET), production());
        Set<String> seen = new HashSet<>();

        for (int index = 0; index < 1_000; index++) {
            String code = codes.newCode();
            assertThat(code).matches("[0-9]{6}");
            seen.add(code);
        }

        assertThat(seen).hasSizeGreaterThan(900);
    }

    @Test
    void anHmacMatchesOnlyItsCodeAndItsChallenge() {
        OneTimeCodes codes = new OneTimeCodes(properties(null, SECRET), production());
        UUID challenge = Ids.newId();
        byte[] stored = codes.hmac(challenge, "123456");

        assertThat(codes.matches(challenge, "123456", stored)).isTrue();
        assertThat(codes.matches(challenge, "123457", stored)).isFalse();
        assertThat(codes.matches(Ids.newId(), "123456", stored)).isFalse();
    }

    @Test
    void aFixedCodeIsUsedInTheTestProfile() {
        OneTimeCodes codes = new OneTimeCodes(properties("123456", null), profile("test"));

        assertThat(codes.newCode()).isEqualTo("123456");
    }

    @Test
    void aFixedCodeStopsStartupOutsideLocalAndTest() {
        assertThatThrownBy(() -> new OneTimeCodes(properties("123456", SECRET), production()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-code");
    }

    @Test
    void aFixedCodeMustBeSixDigits() {
        assertThatThrownBy(() -> new OneTimeCodes(properties("12345", null), profile("local")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionNeedsALongEnoughSecret() {
        assertThatThrownBy(() -> new OneTimeCodes(properties(null, null), production()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
        assertThatThrownBy(() -> new OneTimeCodes(properties(null, "too-short"), production()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private static SignInProperties properties(String fixedCode, String secret) {
        return new SignInProperties(Duration.ofMinutes(5), 5, Duration.ofSeconds(30), fixedCode, secret);
    }

    private static MockEnvironment production() {
        return new MockEnvironment();
    }

    private static MockEnvironment profile(String name) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(name);
        return environment;
    }
}
