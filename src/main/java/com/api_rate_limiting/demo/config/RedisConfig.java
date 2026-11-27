package com.api_rate_limiting.demo.config;

import com.api_rate_limiting.demo.apikey.ApiKeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /** Drops revoked / re-planned keys from this instance's cache as soon as any instance changes them. */
    @Bean
    RedisMessageListenerContainer apiKeyInvalidationListener(RedisConnectionFactory factory, ApiKeyService apiKeys) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(
                (message, pattern) -> apiKeys.invalidateLocal(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(ApiKeyService.INVALIDATION_CHANNEL));
        return container;
    }
}
