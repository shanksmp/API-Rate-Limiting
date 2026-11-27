package com.api_rate_limiting.demo.apikey;

import com.api_rate_limiting.demo.support.TestProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    /** Review finding 2 (outage half): once Redis is known to be down, lookups fail instantly. */
    @Test
    void lookupBreakerStopsHittingRedisDuringAnOutage() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        when(repository.findByHash(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        CircuitBreaker breaker = CircuitBreaker.of("lookup", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(10).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1)).build());
        ApiKeyService service = new ApiKeyService(repository, new ApiKeyHasher(TestProperties.defaults()),
                mock(StringRedisTemplate.class), TestProperties.defaults(), new SimpleMeterRegistry(), breaker);

        for (int i = 0; i < 50; i++) {
            String fresh = "rlk_" + String.format("%043d", i);
            assertThatThrownBy(() -> service.authenticate(fresh)).isInstanceOf(RuntimeException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        verify(repository, atMost(10)).findByHash(anyString());
        assertThatThrownBy(() -> service.authenticate("rlk_" + "z".repeat(43)))
                .isInstanceOf(CallNotPermittedException.class);
    }
}
