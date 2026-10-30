package com.rostersync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.domain.AccessPolicy.Permission;
import com.rostersync.domain.AccessPolicy.Role;
import org.junit.jupiter.api.Test;

class AccessPolicyTest {

    @Test
    void onlyOperatorsCanApproveOrRejectABlockedRun() {
        assertThat(AccessPolicy.can(Role.OPERATOR, Permission.RECONCILE_DECIDE)).isTrue();
        for (Role role : new Role[] {Role.ADMIN, Role.EMPLOYEE, Role.HR_SYSTEM}) {
            assertThat(AccessPolicy.can(role, Permission.RECONCILE_DECIDE)).as(role.name()).isFalse();
        }
    }

    @Test
    void onlyTheTransportAdminCanEditTransportFields() {
        assertThat(AccessPolicy.can(Role.ADMIN, Permission.TRANSPORT_WRITE)).isTrue();
        assertThat(AccessPolicy.can(Role.HR_SYSTEM, Permission.TRANSPORT_WRITE)).isFalse();
        assertThat(AccessPolicy.can(Role.OPERATOR, Permission.TRANSPORT_WRITE)).isFalse();
    }

    @Test
    void employeesCanOnlyReadThemselves() {
        assertThat(AccessPolicy.permissionsFor(Role.EMPLOYEE)).containsExactly(Permission.SELF_READ);
    }

    @Test
    void missingRoleIsDenied() {
        assertThat(AccessPolicy.can(null, Permission.OPS_READ)).isFalse();
    }
}
