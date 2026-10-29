package com.rostersync.domain;

import java.util.List;

/** Something observable happened to an employee; becomes one outbox event. */
public record Change(Type type, List<String> changedFields) {

    public enum Type {
        CREATED("EmployeeCreated"),
        UPDATED("EmployeeUpdated"),
        REACTIVATED("EmployeeReactivated"),
        DEACTIVATED("EmployeeDeactivated"),
        TRANSPORT_UPDATED("EmployeeTransportUpdated");

        private final String eventName;

        Type(String eventName) {
            this.eventName = eventName;
        }

        public String eventName() {
            return eventName;
        }
    }

    public static final List<String> ALL_HR_AND_STATUS = List.of("fullName", "email", "phone", "managerId", "officeCode", "shiftCode", "status");
}
