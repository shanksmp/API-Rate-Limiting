package com.api_rate_limiting.demo.support;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** One Redis container shared by every test class in the JVM (started once, stopped by Ryuk). */
public final class RedisTestSupport {

    public static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");

    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private RedisTestSupport() {
    }

    /** A template with its own connection, like a separate app instance would have. */
    public static StringRedisTemplate newTemplate() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        return new StringRedisTemplate(factory);
    }
}
