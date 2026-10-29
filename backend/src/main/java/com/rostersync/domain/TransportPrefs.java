package com.rostersync.domain;

/** What the transport admin owns about one employee. */
public record TransportPrefs(String pickupPoint, String specialAssistance) {

    public static final TransportPrefs NONE = new TransportPrefs(null, null);

    /**
     * A partial edit: a field is only touched when its "has" flag is set, so "not sent" and
     * "cleared (null)" stay different things.
     */
    public record Patch(boolean hasPickupPoint, String pickupPoint, boolean hasSpecialAssistance, String specialAssistance) {}
}
