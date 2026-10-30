package com.rostersync.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rostersync.service.EmployeeRecord;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Cache-aside on Redis with a TTL per cache (Spring Cache abstraction).
 *
 * <ul>
 *   <li>{@code employee}: single-employee reads, 60 s. Writers evict after commit, so a read is
 *       stale only if that eviction fails, and then for at most the TTL.</li>
 *   <li>{@code offices}: office codes for the validator, 5 min. A new office can be rejected as
 *       unknown for at most that long.</li>
 *   <li>{@code apikeys}: API key hash to tenant, 5 min. A revoked key keeps working at most that long.</li>
 * </ul>
 *
 * <p>Each cache has a typed serializer (no polymorphic type info in Redis), and the error
 * handler turns every Redis failure into a miss: a cache outage never fails a request.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String EMPLOYEE = "employee";
    public static final String OFFICES = "offices";
    public static final String API_KEYS = "apikeys";

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    private final RosterMetrics metrics;

    public CacheConfig(RosterMetrics metrics) {
        this.metrics = metrics;
    }

    @Bean
    CacheManager cacheManager(RedisConnectionFactory connectionFactory, RosterSyncProperties props) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JavaType officeSet = mapper.getTypeFactory().constructCollectionType(Set.class, String.class);
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig().disableCachingNullValues().prefixCacheNameWith("rostersync:");
        return RedisCacheManager.builder(connectionFactory)
                .withInitialCacheConfigurations(Map.of(
                        EMPLOYEE, base.entryTtl(props.cache().employeeTtl())
                                .serializeValuesWith(SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, EmployeeRecord.class))),
                        OFFICES, base.entryTtl(props.cache().officesTtl())
                                .serializeValuesWith(SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, officeSet))),
                        API_KEYS, base.entryTtl(props.cache().apiKeyTtl())
                                .serializeValuesWith(SerializationPair.fromSerializer(RedisSerializer.string()))))
                .disableCreateOnMissingCache()
                .enableStatistics()
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                metrics.cache(cache.getName(), "error");
                log.debug("Cache read failed; treating as a miss: {}", e.getClass().getSimpleName());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                metrics.cache(cache.getName(), "error");
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                metrics.cache(cache.getName(), "evict_error");
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                metrics.cache(cache.getName(), "error");
            }
        };
    }
}
