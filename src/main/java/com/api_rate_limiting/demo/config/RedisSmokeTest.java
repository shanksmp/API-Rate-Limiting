package com.api_rate_limiting.demo.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisSmokeTest {

    @Bean
    CommandLineRunner redisPing(StringRedisTemplate redis){
        return args -> {
            redis.opsForValue().set("rl:smoke","ok");
            String v = redis.opsForValue().get("rl:smoke");
            System.out.println("Redis smoke value = " + v);
        };
    }

}
