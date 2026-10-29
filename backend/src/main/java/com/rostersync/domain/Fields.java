package com.rostersync.domain;

import java.util.List;

/**
 * Field ownership is the contract between the HR system and the transport admin.
 * Each field has exactly one owner; a write from a non-owner is dropped and reported.
 */
public final class Fields {

    public static final List<String> HR = List.of("fullName", "email", "phone", "managerId", "officeCode", "shiftCode");
    public static final List<String> TRANSPORT = List.of("pickupPoint", "specialAssistance");

    /** Fixed so a stored bucket never disagrees with a freshly computed one. */
    public static final int BUCKETS = 256;

    private Fields() {}

    public enum Status { ACTIVE, INACTIVE }

    public enum InactiveReason { HR_EXIT, MISSING_FROM_EXPORT }

    public enum Owner { HR, TRANSPORT_ADMIN }
}
