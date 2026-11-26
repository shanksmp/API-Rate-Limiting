package com.api_rate_limiting.demo.apikey;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Issues, validates and revokes API keys.
 * <p>
 * Lookups go through a local Caffeine cache, so the hot path normally costs one Redis call (the
 * rate limit script), not two. Unknown keys are cached too (briefly) so floods of bad keys don't
 * hit Redis. Revocations are broadcast over Redis pub/sub so every instance drops the key at once;
 * {@code refresh-after} bounds staleness if a message is missed.
 */
@Service
public class ApiKeyService {

    public static final String SECRET_PREFIX = "rlk_";
    public static final String ID_PREFIX = "key_";
    public static final String INVALIDATION_CHANNEL = "rl:apikey:invalidate";

    private static final Pattern SECRET_FORMAT = Pattern.compile("^rlk_[A-Za-z0-9_-]{43}$");
    public static final Pattern ID_FORMAT = Pattern.compile("^key_[A-Za-z0-9_-]{16}$");

    private final ApiKeyRepository repository;
    private final ApiKeyHasher hasher;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    private final LoadingCache<String, Optional<ApiKey>> cache;

    public ApiKeyService(ApiKeyRepository repository, ApiKeyHasher hasher, StringRedisTemplate redis,
                         RateLimitProperties props, MeterRegistry meters) {
        this.repository = repository;
        this.hasher = hasher;
        this.redis = redis;

        RateLimitProperties.ApiKeyCache c = props.apiKeyCache();
        long positiveNanos = c.expireAfter().toNanos();
        long negativeNanos = c.negativeTtl().toNanos();
        this.cache = Caffeine.newBuilder()
                .maximumSize(c.maxSize())
                .refreshAfterWrite(c.refreshAfter())
                .expireAfter(new Expiry<String, Optional<ApiKey>>() {
                    @Override
                    public long expireAfterCreate(String k, Optional<ApiKey> v, long now) {
                        return v.isPresent() ? positiveNanos : negativeNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String k, Optional<ApiKey> v, long now, long current) {
                        return v.isPresent() ? positiveNanos : negativeNanos;
                    }

                    @Override
                    public long expireAfterRead(String k, Optional<ApiKey> v, long now, long current) {
                        return current;
                    }
                })
                .recordStats()
                .build(repository::findByHash);
        CaffeineCacheMetrics.monitor(meters, cache, "apiKeys");
    }

    /**
     * @return the active key for this secret, or empty if it is malformed, unknown or revoked
     * @throws RuntimeException if Redis is down and the key isn't cached
     */
    public Optional<ApiKey> authenticate(String secret) {
        if (secret == null || !SECRET_FORMAT.matcher(secret).matches()) {
            return Optional.empty();
        }
        return cache.get(hasher.hash(secret)).filter(ApiKey::isActive);
    }

    public IssuedApiKey issue(String owner, String plan) {
        String secret = SECRET_PREFIX + randomToken(32);
        ApiKey key = new ApiKey(ID_PREFIX + randomToken(12), owner, plan, ApiKey.Status.ACTIVE,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        String hash = hasher.hash(secret);
        repository.save(key, hash);
        cache.invalidate(hash);
        return new IssuedApiKey(key, secret);
    }

    public Optional<ApiKey> find(String id) {
        return repository.findHashById(id).flatMap(repository::findByHash);
    }

    public List<ApiKey> list(int max) {
        return repository.findAll(max);
    }

    public Optional<ApiKey> revoke(String id) {
        return modify(id, k -> k.withStatus(ApiKey.Status.REVOKED));
    }

    public Optional<ApiKey> changePlan(String id, String plan) {
        return modify(id, k -> k.withPlan(plan));
    }

    /** Called when another instance announces a change. */
    public void invalidateLocal(String hash) {
        cache.invalidate(hash);
    }

    private Optional<ApiKey> modify(String id, java.util.function.UnaryOperator<ApiKey> change) {
        Optional<String> hash = repository.findHashById(id);
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        Optional<ApiKey> current = repository.findByHash(hash.get());
        current.map(change).ifPresent(updated -> {
            repository.update(hash.get(), updated);
            cache.invalidate(hash.get());
            redis.convertAndSend(INVALIDATION_CHANNEL, hash.get());
        });
        return current.map(change);
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
