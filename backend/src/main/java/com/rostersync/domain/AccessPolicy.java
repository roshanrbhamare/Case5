package com.rostersync.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Who may do what. One table, checked by {@code @PreAuthorize} on every endpoint. */
public final class AccessPolicy {

    public enum Role {
        EMPLOYEE,
        /** Transport admin. */
        ADMIN,
        /** On-call / sync operator. */
        OPERATOR,
        /** Machine client, authenticated by a tenant API key. */
        HR_SYSTEM
    }

    public enum Permission {
        EVENTS_INGEST(Role.HR_SYSTEM, Role.OPERATOR),
        RECONCILE_UPLOAD(Role.HR_SYSTEM, Role.OPERATOR),
        RECONCILE_READ(Role.OPERATOR, Role.ADMIN),
        RECONCILE_DECIDE(Role.OPERATOR),
        EMPLOYEES_READ(Role.ADMIN, Role.OPERATOR),
        TRANSPORT_WRITE(Role.ADMIN),
        BATCHES_READ(Role.OPERATOR, Role.ADMIN),
        OPS_READ(Role.OPERATOR, Role.ADMIN),
        SELF_READ(Role.EMPLOYEE);

        private final Set<Role> roles;

        Permission(Role... roles) {
            this.roles = EnumSet.copyOf(Arrays.asList(roles));
        }
    }

    private AccessPolicy() {}

    public static boolean can(Role role, Permission permission) {
        return role != null && permission != null && permission.roles.contains(role);
    }

    public static List<Permission> permissionsFor(Role role) {
        return Arrays.stream(Permission.values()).filter(p -> can(role, p)).toList();
    }

    /** Operators run the sync; they work with ids and counts, so contact details are masked for them. */
    public static boolean seesContactDetails(Role role) {
        return role == Role.ADMIN || role == Role.EMPLOYEE;
    }
}
