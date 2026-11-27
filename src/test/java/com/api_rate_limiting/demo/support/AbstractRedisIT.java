package com.api_rate_limiting.demo.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base for Spring Boot tests: points the app at the shared Testcontainers Redis. */
@ActiveProfiles("test")
public abstract class AbstractRedisIT {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestSupport.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> RedisTestSupport.REDIS.getMappedPort(6379));
    }
}
