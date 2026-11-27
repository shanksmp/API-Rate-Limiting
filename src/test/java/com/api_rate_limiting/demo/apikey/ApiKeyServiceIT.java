package com.api_rate_limiting.demo.apikey;

import com.api_rate_limiting.demo.support.AbstractRedisIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "ratelimit.api-key-cache.refresh-after=1s")
class ApiKeyServiceIT extends AbstractRedisIT {

    @Autowired
    ApiKeyService apiKeys;

    @Autowired
    ApiKeyHasher hasher;

    @Autowired
    StringRedisTemplate redis;

    /** Review finding 4: a revocation whose pub/sub message never arrives is still picked up. */
    @Test
    void missedInvalidationMessageIsCaughtByTheRefresh() {
        String secret = apiKeys.issue("missed-message", "free").secret();
        assertThat(apiKeys.authenticate(secret)).isPresent(); // now cached

        // Another instance revokes it, but its broadcast is lost
        redis.opsForHash().put("rl:apikey:h:" + hasher.hash(secret), "status", "REVOKED");

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> apiKeys.authenticate(secret).isEmpty());
    }

    /** Review finding 6: revoked keys don't stay in Redis forever. */
    @Test
    void revokedKeysExpireAfterRetention() {
        IssuedApiKey issued = apiKeys.issue("retention", "free");
        String id = issued.key().id();
        String hash = hasher.hash(issued.secret());
        assertThat(redis.getExpire("rl:apikey:h:" + hash)).isEqualTo(-1L); // active keys never expire

        apiKeys.revoke(id);

        long thirtyDays = Duration.ofDays(30).toSeconds();
        assertThat(redis.getExpire("rl:apikey:h:" + hash, TimeUnit.SECONDS)).isBetween(thirtyDays - 60, thirtyDays);
        assertThat(redis.getExpire("rl:apikey:id:" + id, TimeUnit.SECONDS)).isBetween(thirtyDays - 60, thirtyDays);
        assertThat(apiKeys.find(id)).get().extracting(ApiKey::status).isEqualTo(ApiKey.Status.REVOKED);
    }

    @Test
    void expiredKeysDropOutOfTheListing() {
        IssuedApiKey issued = apiKeys.issue("expired", "free");
        String id = issued.key().id();
        redis.delete(java.util.List.of("rl:apikey:h:" + hasher.hash(issued.secret()), "rl:apikey:id:" + id));

        String cursor = null;
        do {
            ApiKeyRepository.Page page = apiKeys.list(cursor, 500);
            assertThat(page.keys()).noneMatch(k -> k.id().equals(id));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(redis.opsForZSet().score("rl:apikey:index", id)).isNull(); // cleaned out of the index
    }

    @Test
    void unknownIdsReturnEmpty() {
        assertThat(apiKeys.revoke("key_AAAAAAAAAAAAAAAA")).isEmpty();
        assertThat(apiKeys.changePlan("key_AAAAAAAAAAAAAAAA", "pro")).isEmpty();
    }
}
