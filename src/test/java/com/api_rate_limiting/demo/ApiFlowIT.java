package com.api_rate_limiting.demo;

import com.api_rate_limiting.demo.apikey.ApiKeyHasher;
import com.api_rate_limiting.demo.apikey.ApiKeyService;
import com.api_rate_limiting.demo.support.RedisTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** End to end through the real HTTP stack, a real Redis, and the real config. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@ActiveProfiles("test")
@AutoConfigureObservability // Boot disables metrics export in tests by default
class ApiFlowIT {

    static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdef0123";

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestSupport.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> RedisTestSupport.REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    ApiKeyService apiKeys;

    @Autowired
    ApiKeyHasher hasher;

    @Autowired
    StringRedisTemplate redis;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })   // assert on status ourselves
                .build();
    }

    @Test
    void fullKeyLifecycle() {
        ResponseEntity<JsonNode> created = createKey("acme corp", "strict");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getCacheControl()).contains("no-store");
        String id = created.getBody().get("id").asText();
        String secret = created.getBody().get("apiKey").asText();
        assertThat(secret).startsWith("rlk_").hasSize(47);

        // the secret is never stored in Redis, only its HMAC
        assertThat(redis.keys("rl:apikey:*")).noneMatch(k -> k.contains(secret));

        // strict plan: 3 requests, then 429
        for (int remaining = 2; remaining >= 0; remaining--) {
            ResponseEntity<JsonNode> ok = call("/api/hello", secret);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(ok.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("3");
            assertThat(ok.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo(String.valueOf(remaining));
        }
        ResponseEntity<JsonNode> limited = call("/api/hello", secret);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isBetween(3500L, 3600L);
        assertThat(limited.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        // upgrade the plan: takes effect immediately on this instance
        ResponseEntity<JsonNode> patched = admin().patch().uri("/admin/keys/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("plan", "pro"))
                .retrieve().toEntity(JsonNode.class);
        assertThat(patched.getBody().get("plan").asText()).isEqualTo("pro");
        assertThat(call("/api/me", secret).getBody().get("plan").asText()).isEqualTo("pro");

        // revoke: rejected right away
        assertThat(admin().delete().uri("/admin/keys/{id}", id).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call("/api/hello", secret).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(admin().get().uri("/admin/keys/{id}", id).retrieve().toEntity(JsonNode.class)
                .getBody().get("status").asText()).isEqualTo("REVOKED");
    }

    @Test
    void slidingWindowPlanIsEnforced() {
        String secret = createKey("window-user", "strict-window").getBody().get("apiKey").asText();
        for (int i = 0; i < 3; i++) {
            assertThat(call("/api/hello", secret).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(call("/api/hello", secret).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void rejectsMissingUnknownAndMalformedKeys() {
        assertThat(call("/api/hello", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call("/api/hello", "garbage").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call("/api/hello", "rlk_" + "x".repeat(43)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call("/public/ping", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminApiRequiresToken() {
        Map<String, String> body = Map.of("owner", "x", "plan", "free");
        assertThat(http.post().uri("/admin/keys").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.post().uri("/admin/keys").header("Authorization", "Bearer wrong")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminApiValidatesInput() {
        assertThat(createKey("acme", "no-such-plan").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createKey("<script>", "free").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin().get().uri("/admin/keys/{id}", "not-an-id").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void revocationOnAnotherInstanceReachesThisOneViaPubSub() {
        String secret = createKey("pubsub", "free").getBody().get("apiKey").asText();
        assertThat(apiKeys.authenticate(secret)).isPresent(); // now cached locally

        // Simulate a different instance revoking the key: write Redis directly, then broadcast
        String hash = hasher.hash(secret);
        redis.opsForHash().put("rl:apikey:h:" + hash, "status", "REVOKED");
        assertThat(apiKeys.authenticate(secret)).isPresent(); // still served from the local cache

        redis.convertAndSend(ApiKeyService.INVALIDATION_CHANNEL, hash);
        await().atMost(Duration.ofSeconds(5)).until(() -> apiKeys.authenticate(secret).isEmpty());
    }

    @Test
    void actuatorOnlyOnManagementPort() {
        assertThat(http.get().uri("/actuator/prometheus").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        String secret = createKey("metrics", "free").getBody().get("apiKey").asText();
        call("/api/hello", secret);
        String metrics = RestClient.create("http://localhost:" + managementPort)
                .get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(metrics).contains("ratelimiter_check_seconds_bucket", "ratelimiter_decisions_total",
                "resilience4j_circuitbreaker_state", "cache_gets_total");
        assertThat(RestClient.create("http://localhost:" + managementPort)
                .get().uri("/actuator/health/readiness").retrieve().body(String.class)).contains("UP");
    }

    private ResponseEntity<JsonNode> createKey(String owner, String plan) {
        return admin().post().uri("/admin/keys").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("owner", owner, "plan", plan)).retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> call(String path, String secret) {
        var req = http.get().uri(path);
        if (secret != null) {
            req = req.header("X-API-Key", secret);
        }
        return req.retrieve().toEntity(JsonNode.class);
    }

    private RestClient admin() {
        return http.mutate().defaultHeader("Authorization", "Bearer " + ADMIN_TOKEN).build();
    }
}
