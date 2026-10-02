package com.ridehailing.platform.security;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ridehailing.platform.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security only authenticates bearer tokens here; every request then reaches the dispatcher, where each
 * endpoint's {@code @AllowedRoles} or {@code @PublicEndpoint} decides (LLD §12.3, §12.4).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final String AUTH_PATHS = "/v1/auth/";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder decoder, AuthenticationEntryPoint entryPoint)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .oauth2ResourceServer(server -> server
                        .bearerTokenResolver(bearerTokens())
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(decoder)))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(keys.verificationKeys()))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(properties.issuer()), new JwtAudienceValidator(JwtProperties.AUDIENCE)));
        return decoder;
    }

    /** Writes the same problem details as every other error, through the MVC exception handlers. */
    @Bean
    AuthenticationEntryPoint problemEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        return (request, response, failure) -> exceptionResolver.resolveException(request, response, null,
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid access token is required."));
    }

    /** Ignores tokens on the sign-in endpoints, so an expired access token sent along can't block a refresh. */
    private static BearerTokenResolver bearerTokens() {
        DefaultBearerTokenResolver headers = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) ->
                request.getRequestURI().startsWith(request.getContextPath() + AUTH_PATHS) ? null : headers.resolve(request);
    }
}
