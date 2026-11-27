package com.api_rate_limiting.demo.apikey;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis layout:
 * <pre>
 *   rl:apikey:h:{hmac}   HASH   id, owner, plan, status, createdAt   (lookup on every request)
 *   rl:apikey:id:{id}    STRING hmac                                (admin lookups by id)
 *   rl:apikey:index      ZSET   every key id, score 0               (cursor pagination by id)
 * </pre>
 * Active keys never expire. Revoked keys expire after a retention period.
 */
@Repository
public class ApiKeyRepository {

    static final String BY_HASH = "rl:apikey:h:";
    static final String BY_ID = "rl:apikey:id:";
    static final String INDEX = "rl:apikey:index";

    /**
     * Writes one field, and only if the record still exists. Updates never rewrite the whole record,
     * so two admin operations on different fields (status, plan) can't overwrite each other.
     */
    private static final RedisScript<Long> SET_FIELD_IF_EXISTS = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

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
                ops.opsForZSet().add(INDEX, key.id(), 0);
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

    /** @return false if the record no longer exists */
    public boolean setField(String hash, String field, String value) {
        Long updated = redis.execute(SET_FIELD_IF_EXISTS, List.of(BY_HASH + hash), field, value);
        return updated != null && updated == 1;
    }

    /** Starts the retention countdown for a revoked key. */
    public void expire(String hash, String id, Duration retention) {
        redis.expire(BY_HASH + hash, retention);
        redis.expire(BY_ID + id, retention);
    }

    /**
     * One page of keys ordered by id, starting after {@code afterId}. Ids whose records have
     * expired are dropped from the index on the way.
     */
    public Page findPage(String afterId, int limit) {
        Range<String> range = afterId == null
                ? Range.unbounded()
                : Range.rightUnbounded(Range.Bound.exclusive(afterId));
        Set<String> found = redis.opsForZSet().rangeByLex(INDEX, range, Limit.limit().count(limit + 1));
        List<String> ids = found == null ? List.of() : new ArrayList<>(found);
        boolean more = ids.size() > limit;
        if (more) {
            ids = ids.subList(0, limit);
        }

        List<ApiKey> keys = new ArrayList<>(ids.size());
        for (String id : ids) {
            Optional<ApiKey> key = findHashById(id).flatMap(this::findByHash);
            if (key.isPresent()) {
                keys.add(key.get());
            } else {
                redis.opsForZSet().remove(INDEX, id);
            }
        }
        return new Page(keys, more ? ids.get(ids.size() - 1) : null);
    }

    /** Adds keys created before the index existed. Cheap no-op once the index is populated. */
    public int backfillIndex() {
        if (Boolean.TRUE.equals(redis.hasKey(INDEX))) {
            return 0;
        }
        int added = 0;
        ScanOptions options = ScanOptions.scanOptions().match(BY_ID + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                redis.opsForZSet().add(INDEX, cursor.next().substring(BY_ID.length()), 0);
                added++;
            }
        }
        return added;
    }

    public record Page(List<ApiKey> keys, String nextCursor) {
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
