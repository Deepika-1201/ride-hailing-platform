package com.ridehailing.platform.security;

import static com.ridehailing.shared.UserRole.ADMIN;
import static com.ridehailing.shared.UserRole.DRIVER;
import static com.ridehailing.shared.UserRole.OPS;
import static com.ridehailing.shared.UserRole.RIDER;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * LLD §12.3, §12.4: every endpoint admits exactly the roles it declares, ownership hides other users' resources
 * behind 404, and every kind of bad token is refused with 401.
 */
class AccessTests extends IntegrationTest {

    private static final Map<String, Set<UserRole>> ROLE_ENDPOINTS = Map.of(
            "/test/access/staff", Set.of(OPS, ADMIN),
            "/test/access/admin", Set.of(ADMIN),
            "/test/access/driver", Set.of(DRIVER));

    @Autowired
    private TestUsers users;

    @Autowired
    private JwtKeys keys;

    @Autowired
    private JwtProperties properties;

    @Test
    void withoutATokenAProtectedEndpointAnswers401AsAProblem() {
        HttpResponse<String> response = get(port, "/test/access/staff");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(json(response).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(json(response).get("request_id").asString()).isNotBlank();
    }

    @Test
    void eachEndpointAdmitsExactlyTheRolesItDeclares() {
        for (UserRole role : UserRole.values()) {
            TestUser user = users.create(role);
            ROLE_ENDPOINTS.forEach((path, allowed) -> {
                HttpResponse<String> response = getAs(user.authorization(), path);
                assertThat(response.statusCode()).as("%s on %s", role, path).isEqualTo(allowed.contains(role) ? 200 : 403);
                if (!allowed.contains(role)) {
                    assertThat(json(response).get("code").asString()).isEqualTo("FORBIDDEN");
                }
            });
            assertThat(getAs(user.authorization(), "/test/access/rider/things/" + user.id()).statusCode())
                    .as("%s on its own rider resource", role)
                    .isEqualTo(role == RIDER ? 200 : 403);
        }
    }

    @Test
    void aRiderAsksForAnotherRidersResourceAndGets404() {
        TestUser rider = users.create(RIDER);
        TestUser other = users.create(RIDER);

        HttpResponse<String> response = getAs(rider.authorization(), "/test/access/rider/things/" + other.id());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void aUserWithTwoRolesReachesTheEndpointsOfBoth() {
        TestUser riderAndDriver = users.create(RIDER, DRIVER);

        assertThat(getAs(riderAndDriver.authorization(), "/test/access/driver").statusCode()).isEqualTo(200);
        assertThat(getAs(riderAndDriver.authorization(), "/test/access/rider/things/" + riderAndDriver.id())
                .statusCode()).isEqualTo(200);
        assertThat(getAs(riderAndDriver.authorization(), "/test/access/admin").statusCode()).isEqualTo(403);
    }

    @Test
    void theCallerIsTheSubjectOfTheToken() {
        TestUser ops = users.create(OPS);

        assertThat(json(getAs(ops.authorization(), "/test/access/staff")).get("user_id").asString())
                .isEqualTo(ops.id().toString());
    }

    @Test
    void aHandlerThatDeclaresNothingRefusesEveryone() {
        assertThat(get(port, "/test/access/undeclared").statusCode()).isEqualTo(401);
        assertThat(getAs(users.create(ADMIN).authorization(), "/test/access/undeclared").statusCode()).isEqualTo(403);
    }

    @Test
    void publicEndpointsNeedNoTokenAndAMethodDeclarationOverridesItsClass() {
        assertThat(get(port, "/test/access/public").statusCode()).isEqualTo(200);
        assertThat(get(port, "/test/access/driver/help").statusCode()).isEqualTo(200);
        assertThat(get(port, "/test/access/driver").statusCode()).isEqualTo(401);
    }

    @Test
    void everyKindOfBadTokenIsRefused() {
        TestUser admin = users.create(ADMIN);
        ECKey impostor = generateKey(keys.signingKey().getKeyID());
        Map<String, String> badTokens = new LinkedHashMap<>();
        badTokens.put("expired", token(keys.signingKey(), admin, claims -> claims
                .issuedAt(Instant.now().minus(Duration.ofMinutes(20)))
                .expiresAt(Instant.now().minus(Duration.ofMinutes(2)))));
        badTokens.put("another issuer", token(keys.signingKey(), admin, claims -> claims.issuer("someone-else")));
        badTokens.put("another audience", token(keys.signingKey(), admin, claims -> claims.audience(List.of("other"))));
        badTokens.put("signed by another key with our kid", token(impostor, admin, claims -> { }));
        badTokens.put("tampered roles", tamper(token(keys.signingKey(), users.create(RIDER), claims -> { })));
        badTokens.put("unsigned", unsigned(admin));
        badTokens.put("not a JWT", "not-a-token");

        badTokens.forEach((kind, token) -> {
            HttpResponse<String> response = getAs("Bearer " + token, "/test/access/admin");
            assertThat(response.statusCode()).as(kind).isEqualTo(401);
            assertThat(json(response).get("code").asString()).as(kind).isEqualTo("UNAUTHENTICATED");
        });
        assertThat(getAs("Bearer " + token(keys.signingKey(), admin, claims -> { }), "/test/access/admin").statusCode())
                .as("the same token, untouched")
                .isEqualTo(200);
    }

    @Test
    void aTokenExpiredWithinTheClockSkewIsStillAccepted() {
        TestUser admin = users.create(ADMIN);
        String justExpired = token(keys.signingKey(), admin, claims -> claims
                .issuedAt(Instant.now().minus(Duration.ofMinutes(15)))
                .expiresAt(Instant.now().minus(Duration.ofSeconds(10))));

        assertThat(getAs("Bearer " + justExpired, "/test/access/admin").statusCode()).isEqualTo(200);
    }

    @Test
    void signInEndpointsIgnoreBearerTokensSoAStaleOneCantBlockThem() {
        HttpResponse<String> response = postJson("/v1/auth/logout", Map.of("Authorization", "Bearer stale-or-broken"),
                "{\"refresh_token\": \"" + "x".repeat(43) + "\"}");

        assertThat(response.statusCode()).isEqualTo(204);
    }

    @Test
    void anUnknownPathIsStill404WithoutAToken() {
        assertThat(get(port, "/v1/nothing-here").statusCode()).isEqualTo(404);
    }

    private String token(ECKey key, TestUser user, Consumer<JwtClaimsSet.Builder> change) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(JwtProperties.AUDIENCE))
                .subject(user.id().toString())
                .claim("roles", user.roles().stream().map(UserRole::name).sorted().toList())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(10)));
        change.accept(claims);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(key.getKeyID()).type("JWT").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
    }

    /** Swaps the payload's role for ADMIN and keeps the original signature. */
    private static String tamper(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"RIDER\"", "\"ADMIN\"");
        return parts[0] + "." + base64Url(payload) + "." + parts[2];
    }

    private String unsigned(TestUser user) {
        String signed = token(keys.signingKey(), user, claims -> { });
        return base64Url("{\"alg\":\"none\"}") + "." + signed.split("\\.")[1] + ".";
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ECKey generateKey(String keyId) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
