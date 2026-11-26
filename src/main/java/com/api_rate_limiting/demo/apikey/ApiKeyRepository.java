package com.api_rate_limiting.demo.apikey;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis layout:
 * <pre>
 *   rl:apikey:h:{hmac}   HASH  id, owner, plan, status, createdAt   (lookup on every request)
 *   rl:apikey:id:{id}    STRING hmac                                (admin lookups by id)
 * </pre>
 * Neither has a TTL, so with {@code maxmemory-policy volatile-ttl} Redis never evicts keys,
 * only rate limit buckets.
 */
@Repository
public class ApiKeyRepository {

    static final String BY_HASH = "rl:apikey:h:";
    static final String BY_ID = "rl:apikey:id:";

    private final StringRedisTemplate redis;

    public ApiKeyRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(ApiKey key, String hash) {
        redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public List<Object> execute(RedisOperations operations) {
                RedisOperations<String, String> ops = operations;
                ops.multi();
                ops.opsForHash().putAll(BY_HASH + hash, toMap(key));
                ops.opsForValue().set(BY_ID + key.id(), hash);
                return ops.exec();
            }
        });
    }

    public Optional<ApiKey> findByHash(String hash) {
        Map<Object, Object> fields = redis.opsForHash().entries(BY_HASH + hash);
        return fields.isEmpty() ? Optional.empty() : Optional.of(fromMap(fields));
    }

    public Optional<String> findHashById(String id) {
        return Optional.ofNullable(redis.opsForValue().get(BY_ID + id));
    }

    public void update(String hash, ApiKey key) {
        redis.opsForHash().putAll(BY_HASH + hash, toMap(key));
    }

    public List<ApiKey> findAll(int max) {
        List<ApiKey> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(BY_ID + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext() && keys.size() < max) {
                String hash = redis.opsForValue().get(cursor.next());
                if (hash != null) {
                    findByHash(hash).ifPresent(keys::add);
                }
            }
        }
        return keys;
    }

    private static Map<String, String> toMap(ApiKey k) {
        return Map.of(
                "id", k.id(),
                "owner", k.owner(),
                "plan", k.plan(),
                "status", k.status().name(),
                "createdAt", k.createdAt().toString());
    }

    private static ApiKey fromMap(Map<Object, Object> m) {
        return new ApiKey(
                (String) m.get("id"),
                (String) m.get("owner"),
                (String) m.get("plan"),
                ApiKey.Status.valueOf((String) m.get("status")),
                Instant.parse((String) m.get("createdAt")));
    }
}
