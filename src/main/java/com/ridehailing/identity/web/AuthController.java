package com.ridehailing.identity.web;

import com.ridehailing.identity.app.Sessions;
import com.ridehailing.identity.app.SignIn;
import com.ridehailing.identity.app.SignIn.CodeSent;
import com.ridehailing.identity.app.SignedIn;
import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.PublicEndpoint;
import com.ridehailing.shared.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** Sign-in, refresh and logout (LLD §12.1, §12.2): public, rate-limited, and without idempotency keys (§5.1). */
@ApiController
@PublicEndpoint
@RequestMapping("/v1/auth")
class AuthController {

    private static final String PHONE = "\\+[1-9][0-9]{7,14}";

    private final SignIn signIn;
    private final Sessions sessions;

    AuthController(SignIn signIn, Sessions sessions) {
        this.signIn = signIn;
        this.sessions = sessions;
    }

    @PostMapping("/otp")
    ResponseEntity<CodeSentResponse> requestCode(@Valid @RequestBody OtpRequest request, HttpServletRequest http) {
        CodeSent sent = signIn.requestCode(request.phone(), http.getRemoteAddr());
        return ResponseEntity.accepted().body(new CodeSentResponse(sent.expiresAt(), sent.resendAfter().toSeconds()));
    }

    @PostMapping("/token")
    TokenResponse exchangeCode(@Valid @RequestBody TokenRequest request) {
        return TokenResponse.of(signIn.exchange(request.phone(), request.code()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(sessions.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        sessions.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    record OtpRequest(@NotNull @Pattern(regexp = PHONE) String phone) {
    }

    record TokenRequest(@NotNull @Pattern(regexp = PHONE) String phone, @NotNull @Pattern(regexp = "[0-9]{6}") String code) {
    }

    record RefreshRequest(@NotNull @Size(min = 20, max = 200) String refreshToken) {
    }

    record CodeSentResponse(Instant expiresAt, long resendAfterS) {
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
            long refreshExpiresIn, UserView user) {

        static TokenResponse of(SignedIn signedIn) {
            return new TokenResponse(signedIn.accessToken(), "Bearer", signedIn.accessExpiresIn().toSeconds(),
                    signedIn.refreshToken(), signedIn.refreshExpiresIn().toSeconds(),
                    new UserView(signedIn.userId(), signedIn.roles().stream().sorted().toList()));
        }
    }

    record UserView(UUID id, List<UserRole> roles) {
    }
}
