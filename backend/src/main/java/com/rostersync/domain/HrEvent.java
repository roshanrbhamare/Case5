package com.rostersync.domain;

/** A validated HR change. {@code data} is null for DEACTIVATE. */
public record HrEvent(String eventId, Type type, String employeeId, long version, HrSnapshot data) {

    public enum Type { CREATE, UPDATE, DEACTIVATE }
}
