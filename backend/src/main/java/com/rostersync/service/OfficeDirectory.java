package com.rostersync.service;

import com.rostersync.config.CacheConfig;
import com.rostersync.persistence.DirectoryRepository;
import com.rostersync.persistence.DirectoryRepository.Office;
import com.rostersync.persistence.Transactions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Office codes per tenant, used by the validator on every record. Cached (see CacheConfig):
 * a newly added office can be rejected as unknown for at most the cache TTL.
 */
@Service
public class OfficeDirectory {

    private final Transactions tx;
    private final DirectoryRepository directory;

    public OfficeDirectory(Transactions tx, DirectoryRepository directory) {
        this.tx = tx;
        this.directory = directory;
    }

    @Cacheable(cacheNames = CacheConfig.OFFICES, key = "#tenantId")
    public Set<String> codes(String tenantId) {
        return new HashSet<>(tx.call(() -> directory.officeCodes(tenantId)));
    }

    public List<Office> list(String tenantId) {
        return tx.call(() -> directory.offices(tenantId));
    }
}
