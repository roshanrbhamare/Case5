package com.rostersync.service;

import com.rostersync.config.CacheConfig;
import com.rostersync.persistence.DirectoryRepository;
import com.rostersync.persistence.DirectoryRepository.Tenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * API key hash to tenant, cached. A separate bean from AuthService on purpose: @Cacheable
 * works through a proxy, so a call from inside the same class would skip the cache.
 */
@Component
public class ApiKeyResolver {

    private final DirectoryRepository directory;

    public ApiKeyResolver(DirectoryRepository directory) {
        this.directory = directory;
    }

    public static String hash(String apiKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Cacheable(cacheNames = CacheConfig.API_KEYS, key = "#keyHash", unless = "#result == null")
    public String tenantFor(String keyHash) {
        return directory.tenantByApiKeyHash(keyHash).map(Tenant::id).orElse(null);
    }
}
