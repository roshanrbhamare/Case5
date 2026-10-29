package com.rostersync.domain;

/** Everything HR owns about one employee, as of one source version. Immutable. */
public record HrSnapshot(String fullName, String email, String phone, String managerId, String officeCode, String shiftCode) {

    public static final HrSnapshot EMPTY = new HrSnapshot(null, null, null, null, null, null);

    public String get(String field) {
        return switch (field) {
            case "fullName" -> fullName;
            case "email" -> email;
            case "phone" -> phone;
            case "managerId" -> managerId;
            case "officeCode" -> officeCode;
            case "shiftCode" -> shiftCode;
            default -> throw new IllegalArgumentException("Not an HR field: " + field);
        };
    }
}
