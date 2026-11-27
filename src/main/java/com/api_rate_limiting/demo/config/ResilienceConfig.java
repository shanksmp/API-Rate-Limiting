package com.api_rate_limiting.demo.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    public static final String REDIS_BREAKER = "redis-rate-limiter";
    public static final String API_KEY_LOOKUP_BREAKER = "redis-api-key-lookup";

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(RateLimitProperties props, MeterRegistry meters) {
        RateLimitProperties.CircuitBreaker cb = props.circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cb.failureRateThreshold())
                .slowCallRateThreshold(cb.failureRateThreshold())
                .slowCallDurationThreshold(cb.slowCallThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.slidingWindowSize())
                .minimumNumberOfCalls(cb.minimumCalls())
                .waitDurationInOpenState(cb.openDuration())
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    CircuitBreaker rateLimiterCircuitBreaker(CircuitBreakerRegistry registry) {
        return logged(registry.circuitBreaker(REDIS_BREAKER));
    }

    /** Separate breaker for API key lookups, so an outage fails them fast instead of on timeouts. */
    @Bean
    CircuitBreaker apiKeyLookupCircuitBreaker(CircuitBreakerRegistry registry) {
        return logged(registry.circuitBreaker(API_KEY_LOOKUP_BREAKER));
    }

    private static CircuitBreaker logged(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(e ->
                log.warn("Circuit breaker {}: {} -> {}", breaker.getName(),
                        e.getStateTransition().getFromState(), e.getStateTransition().getToState()));
        return breaker;
    }
}
