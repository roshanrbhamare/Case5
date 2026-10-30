package com.rostersync.web.security;

import com.rostersync.domain.AccessPolicy;
import com.rostersync.domain.AccessPolicy.Permission;
import com.rostersync.service.RosterPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Bridge from {@code @PreAuthorize("@access.allows('EVENTS_INGEST')")} to the single
 * permission table in {@link AccessPolicy}, so roles are never hard-coded on controllers.
 */
@Component("access")
public class AccessGuard {

    public boolean allows(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof RosterPrincipal principal)) return false;
        return AccessPolicy.can(principal.role(), Permission.valueOf(permission));
    }
}
