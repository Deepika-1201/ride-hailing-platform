package com.ridehailing.platform.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ridehailing.platform.AccessTokens;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** ES256 access tokens with the claims of LLD §12.2. */
@Component
class JwtAccessTokens implements AccessTokens {

    static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final String keyId;
    private final JwtProperties properties;
    private final Clock clock;

    JwtAccessTokens(JwtKeys keys, JwtProperties properties, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
        this.keyId = keys.signingKey().getKeyID();
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AccessToken issue(UUID userId, Set<UserRole> roles) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(JwtProperties.AUDIENCE))
                .subject(userId.toString())
                .claim(ROLES_CLAIM, roles.stream().map(UserRole::name).sorted().toList())
                .id(Ids.newId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keyId).type("JWT").build();
        return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(),
                properties.accessTtl());
    }
}
