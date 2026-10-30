package com.rostersync.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fail fast when Redis is down: commands are rejected immediately instead of queueing while
 * Lettuce reconnects. Callers (cache, relay, consumer) already treat Redis errors as
 * "degraded", so an outage costs latency, never correctness.
 */
@Configuration
public class RedisConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastRedis() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                .build());
    }
}
