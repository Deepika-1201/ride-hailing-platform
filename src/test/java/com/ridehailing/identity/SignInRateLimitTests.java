package com.ridehailing.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestUsers;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** LLD §12.1: one address can't request codes for unlimited phones; in a context of its own, with a small limit. */
@TestPropertySource(properties = "ride.rate-limits.otp-per-ip.capacity=3")
class SignInRateLimitTests extends IntegrationTest {

    @Test
    void anAddressRequestingCodesForManyPhonesIsLimited() {
        for (int phone = 0; phone < 3; phone++) {
            assertThat(requestCode(TestUsers.randomPhone()).statusCode()).isEqualTo(202);
        }

        HttpResponse<String> limited = requestCode(TestUsers.randomPhone());

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(json(limited).get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    private HttpResponse<String> requestCode(String phone) {
        return postJson("/v1/auth/otp", Map.of(), "{\"phone\": \"" + phone + "\"}");
    }
}
