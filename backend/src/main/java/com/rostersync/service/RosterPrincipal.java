package com.rostersync.service;

import com.rostersync.domain.AccessPolicy.Role;

/** The authenticated caller: always carries the tenant, taken from the credential. */
public record RosterPrincipal(String userId, String tenantId, Role role, String employeeId, String displayName, String actor) {}
