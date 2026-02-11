package com.banking.journey.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis configuration for state management.
 * <p>
 * Uses StringRedisTemplate for simplicity — all values
 * are JSON strings serialized via Jackson ObjectMapper.
 * </p>
 */
@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate — uses String serializer for both keys and values.
     * JSON serialization is handled explicitly in the RedisStateStore adapter.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
