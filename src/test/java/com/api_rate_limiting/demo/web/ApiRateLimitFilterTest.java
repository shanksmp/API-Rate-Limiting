package com.api_rate_limiting.demo.web;

import com.api_rate_limiting.demo.api.DemoController;
import com.api_rate_limiting.demo.apikey.ApiKey;
import com.api_rate_limiting.demo.apikey.ApiKeyService;
import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.api_rate_limiting.demo.ratelimit.Plan;
import com.api_rate_limiting.demo.ratelimit.PlanRegistry;
import com.api_rate_limiting.demo.ratelimit.RateLimitDecision;
import com.api_rate_limiting.demo.ratelimit.RateLimitDecision.Outcome;
import com.api_rate_limiting.demo.ratelimit.RateLimitService;
import com.api_rate_limiting.demo.support.TestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Filter behaviour in isolation: no Spring context, no Redis. */
class ApiRateLimitFilterTest {

    private static final String SECRET = "rlk_" + "a".repeat(43);
    private static final ApiKey KEY = new ApiKey("key_0123456789abcdef", "acme", "free", ApiKey.Status.ACTIVE, Instant.now());

    private ApiKeyService apiKeys;
    private RateLimitService rateLimits;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = TestProperties.defaults();
        PlanRegistry plans = new PlanRegistry(props);
        apiKeys = mock(ApiKeyService.class);
        rateLimits = mock(RateLimitService.class);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(apiKeys, plans, rateLimits,
                new ProblemWriter(new ObjectMapper()), props, new SimpleMeterRegistry());
        mvc = MockMvcBuilders.standaloneSetup(new DemoController(plans)).addFilters(filter).build();
    }

    @Test
    void publicPathsSkipAuth() throws Exception {
        mvc.perform(get("/public/ping")).andExpect(status().isOk());
        verifyNoInteractions(apiKeys, rateLimits);
    }

    @Test
    void missingKeyIs401ProblemJson() throws Exception {
        mvc.perform(get("/api/hello"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-API-Key\""))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(rateLimits);
    }

    @Test
    void unknownPathsAreProtectedToo() throws Exception {
        mvc.perform(get("/internal/secret")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidKeyIs401() throws Exception {
        when(apiKeys.authenticate(anyString())).thenReturn(Optional.empty());
        mvc.perform(get("/api/hello").header("X-API-Key", "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid API key"));
        verifyNoInteractions(rateLimits);
    }

    @Test
    void authBackendDownIs503() throws Exception {
        when(apiKeys.authenticate(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        mvc.perform(get("/api/hello").header("X-API-Key", SECRET))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void allowedRequestGetsRateLimitHeaders() throws Exception {
        when(apiKeys.authenticate(SECRET)).thenReturn(Optional.of(KEY));
        when(rateLimits.check(eq(KEY.id()), any(Plan.class), anyInt()))
                .thenReturn(new RateLimitDecision(Outcome.ALLOWED, 10, 7, 0, 18_000));

        mvc.perform(get("/api/hello").header("X-API-Key", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("hello, acme"))
                .andExpect(header().string("RateLimit-Limit", "10"))
                .andExpect(header().string("RateLimit-Remaining", "7"))
                .andExpect(header().string("RateLimit-Reset", "18"))
                .andExpect(header().string("RateLimit-Policy", "10;w=60"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void deniedRequestIs429WithRetryAfter() throws Exception {
        when(apiKeys.authenticate(SECRET)).thenReturn(Optional.of(KEY));
        when(rateLimits.check(eq(KEY.id()), any(Plan.class), anyInt()))
                .thenReturn(new RateLimitDecision(Outcome.DENIED, 10, 0, 5_400, 60_000));

        mvc.perform(get("/api/hello").header("X-API-Key", SECRET))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "6"))
                .andExpect(header().string("RateLimit-Remaining", "0"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void failOpenLetsRequestThroughWithoutLimitHeaders() throws Exception {
        when(apiKeys.authenticate(SECRET)).thenReturn(Optional.of(KEY));
        when(rateLimits.check(eq(KEY.id()), any(Plan.class), anyInt()))
                .thenReturn(RateLimitDecision.unavailable(Outcome.FAIL_OPEN, 10));

        mvc.perform(get("/api/hello").header("X-API-Key", SECRET))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("RateLimit-Remaining"));
    }

    @Test
    void failClosedIs503() throws Exception {
        when(apiKeys.authenticate(SECRET)).thenReturn(Optional.of(KEY));
        when(rateLimits.check(eq(KEY.id()), any(Plan.class), anyInt()))
                .thenReturn(RateLimitDecision.unavailable(Outcome.FAIL_CLOSED, 10));

        mvc.perform(get("/api/hello").header("X-API-Key", SECRET))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void dotSegmentsCannotSneakPastTheFilter() throws Exception {
        mvc.perform(get("/public/../api/hello")).andExpect(status().isBadRequest());
        mvc.perform(get(java.net.URI.create("/public/%2e%2e/api/hello"))).andExpect(status().isBadRequest());
        verifyNoInteractions(apiKeys, rateLimits);
    }
}
