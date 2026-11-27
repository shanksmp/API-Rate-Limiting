package com.api_rate_limiting.demo.apikey;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Issues, validates and revokes API keys.
 * <p>
 * Lookups go through a local Caffeine cache, so the hot path normally costs one Redis call (the
 * rate limit script), not two. Unknown keys are cached too, briefly, and Redis lookups sit behind
 * their own circuit breaker so an outage fails fast instead of waiting on timeouts.
 * <p>
 * Revocations and plan changes are broadcast over Redis pub/sub so other instances drop the key
 * at once. Pub/sub can lose messages, so each instance also re-reads cached keys
 * {@code refresh-after} after loading them, on their next use. During a Redis outage a cached
 * key, including one revoked just before the outage, stays usable until {@code expire-after}.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    public static final String SECRET_PREFIX = "rlk_";
    public static final String ID_PREFIX = "key_";
    public static final String INVALIDATION_CHANNEL = "rl:apikey:invalidate";

    private static final Pattern SECRET_FORMAT = Pattern.compile("^rlk_[A-Za-z0-9_-]{43}$");
    public static final Pattern ID_FORMAT = Pattern.compile("^key_[A-Za-z0-9_-]{16}$");

    private final ApiKeyRepository repository;
    private final ApiKeyHasher hasher;
    private final StringRedisTemplate redis;
    private final Duration revokedRetention;
    private final SecureRandom random = new SecureRandom();
    private final LoadingCache<String, Optional<ApiKey>> cache;

    public ApiKeyService(ApiKeyRepository repository, ApiKeyHasher hasher, StringRedisTemplate redis,
                         RateLimitProperties props, MeterRegistry meters,
                         @Qualifier("apiKeyLookupCircuitBreaker") CircuitBreaker lookupBreaker) {
        this.repository = repository;
        this.hasher = hasher;
        this.redis = redis;
        this.revokedRetention = props.revokedKeyRetention();

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
                .build(hash -> lookupBreaker.executeSupplier(() -> repository.findByHash(hash)));
        CaffeineCacheMetrics.monitor(meters, cache, "apiKeys");
    }

    /**
     * @return the active key for this secret, or empty if it is malformed, unknown or revoked
     * @throws RuntimeException if Redis is unavailable and the key isn't cached
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

    public ApiKeyRepository.Page list(String afterId, int limit) {
        return repository.findPage(afterId, limit);
    }

    /** Marks the key revoked (an atomic single-field write) and starts its retention countdown. */
    public Optional<ApiKey> revoke(String id) {
        Optional<String> hash = repository.findHashById(id);
        if (hash.isEmpty() || !repository.setField(hash.get(), "status", ApiKey.Status.REVOKED.name())) {
            return Optional.empty();
        }
        repository.expire(hash.get(), id, revokedRetention);
        announceChange(hash.get());
        return repository.findByHash(hash.get());
    }

    /** Changes only the plan field, so it can never undo a concurrent revocation. */
    public Optional<ApiKey> changePlan(String id, String plan) {
        Optional<String> hash = repository.findHashById(id);
        if (hash.isEmpty() || !repository.setField(hash.get(), "plan", plan)) {
            return Optional.empty();
        }
        announceChange(hash.get());
        return repository.findByHash(hash.get());
    }

    /** Called when another instance announces a change. */
    public void invalidateLocal(String hash) {
        cache.invalidate(hash);
    }

    @EventListener(ApplicationReadyEvent.class)
    void backfillIndex() {
        try {
            int added = repository.backfillIndex();
            if (added > 0) {
                log.info("Added {} existing API keys to the pagination index", added);
            }
        } catch (RuntimeException e) {
            log.warn("Could not backfill the API key index: {}", e.toString());
        }
    }

    /**
     * The change is already saved, so a failed broadcast must not fail the admin request. Other
     * instances then pick the change up on their next refresh of that key.
     */
    private void announceChange(String hash) {
        cache.invalidate(hash);
        try {
            redis.convertAndSend(INVALIDATION_CHANNEL, hash);
        } catch (RuntimeException e) {
            log.warn("API key change saved, but the invalidation broadcast failed; other instances will "
                    + "see it on their next cache refresh: {}", e.toString());
        }
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
