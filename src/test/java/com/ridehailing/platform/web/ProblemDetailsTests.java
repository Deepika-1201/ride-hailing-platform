package com.ridehailing.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridehailing.platform.ApiController;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.PublicEndpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The error model of LLD §13: RFC 9457 problem details with a stable {@code code} and the {@code request_id}. Spring
 * Security is left out of this slice; its 401 is covered with real tokens in {@code AccessTests}.
 */
@WebMvcTest(controllers = ProblemDetailsTests.ProblemFixtureController.class, excludeAutoConfiguration = {
    SecurityAutoConfiguration.class,
    ServletWebSecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class,
    UserDetailsServiceAutoConfiguration.class,
    OAuth2ResourceServerAutoConfiguration.class,
    OAuth2ResourceServerWebSecurityAutoConfiguration.class
})
@Import(ProblemDetailsTests.ProblemFixtureController.class)
class ProblemDetailsTests {

    private static final String GENERATED_ID = "req_[0-9a-f]{32}";

    @Autowired
    private MockMvc mvc;

    @Test
    void unknownRouteIsANotFoundProblemCarryingTheGeneratedRequestId() throws Exception {
        MvcResult result = mvc.perform(get("/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.request_id").value(matchesPattern(GENERATED_ID)))
                .andReturn();

        String headerId = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(result.getResponse().getContentAsString()).contains("\"request_id\":\"" + headerId + "\"");
    }

    @Test
    void keepsAWellFormedClientRequestId() throws Exception {
        mvc.perform(get("/test/problems/ok").header(RequestIdFilter.HEADER, "booking-42.retry_1"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, "booking-42.retry_1"));
    }

    @Test
    void replacesAMalformedClientRequestId() throws Exception {
        mvc.perform(get("/test/problems/ok").header(RequestIdFilter.HEADER, "not ok; drop table"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(GENERATED_ID)));
    }

    @Test
    void validationFailureListsEveryFieldInSnakeCase() throws Exception {
        mvc.perform(post("/test/problems/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ride_pin\": \"\", \"stars\": 9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("ride_pin", "stars")));
    }

    @Test
    void missingParameterIsAValidationFailure() throws Exception {
        mvc.perform(get("/test/problems/needs-parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unreadableBodyIsAMalformedRequest() throws Exception {
        mvc.perform(post("/test/problems/validated").contentType(MediaType.APPLICATION_JSON).content("{\"ride_pin\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void wrongContentTypeIsUnsupported() throws Exception {
        mvc.perform(post("/test/problems/validated").contentType(MediaType.TEXT_PLAIN).content("ride_pin=1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void wrongMethodIsNotAllowed() throws Exception {
        mvc.perform(delete("/test/problems/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void moduleErrorKeepsItsStatusCodeAndDetail() throws Exception {
        mvc.perform(get("/test/problems/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RIDE_EXISTS"))
                .andExpect(jsonPath("$.detail").value("You already have an active ride."));
    }

    @Test
    void moduleErrorCanAddRetryAfterAndExtraMembers() throws Exception {
        mvc.perform(get("/test/problems/retry-later"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
                .andExpect(jsonPath("$.current_status").value("COMPLETED"))
                .andExpect(jsonPath("$.current_version").value(7));
    }

    @Test
    void unexpectedErrorHidesItsMessage() throws Exception {
        MvcResult result = mvc.perform(get("/test/problems/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("hunter2");
    }

    @TestComponent
    @ApiController
    @PublicEndpoint
    static class ProblemFixtureController {

        @GetMapping("/test/problems/ok")
        Map<String, Object> ok() {
            return Map.of("ok", true);
        }

        @PostMapping("/test/problems/validated")
        Map<String, Object> validated(@Valid @RequestBody RatingRequest request) {
            return Map.of("stars", request.stars());
        }

        @GetMapping("/test/problems/needs-parameter")
        Map<String, Object> needsParameter(@RequestParam int limit) {
            return Map.of("limit", limit);
        }

        @GetMapping("/test/problems/conflict")
        Map<String, Object> conflict() {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_RIDE_EXISTS", "You already have an active ride.");
        }

        @GetMapping("/test/problems/retry-later")
        Map<String, Object> retryLater() {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "The ride is completed.",
                    Duration.ofMillis(1_500), Map.of("current_status", "COMPLETED", "current_version", 7));
        }

        @GetMapping("/test/problems/boom")
        Map<String, Object> boom() {
            throw new IllegalStateException("database password is hunter2");
        }
    }

    record RatingRequest(@NotBlank String ridePin, @Min(1) @Max(5) int stars) {
    }
}
