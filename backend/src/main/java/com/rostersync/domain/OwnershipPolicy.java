package com.rostersync.domain;

import com.rostersync.domain.Fields.Owner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conflict rule: ownership is per field, not per record. A writer can only change the
 * fields it owns. Fields it does not own are dropped (not an error, so an HR system that
 * sends extra columns doesn't get its whole feed rejected) and reported as warnings.
 * An HR exit changes status, which hides transport preferences but keeps them so a
 * rehire gets them back.
 */
public final class OwnershipPolicy {

    public record Partition(Map<String, Object> allowed, List<String> notOwned, List<String> unknown) {}

    private OwnershipPolicy() {}

    public static Owner ownerOf(String field) {
        if (Fields.HR.contains(field)) return Owner.HR;
        if (Fields.TRANSPORT.contains(field)) return Owner.TRANSPORT_ADMIN;
        return null;
    }

    /** Splits a patch into what {@code owner} may write and what it may not, keeping the input order. */
    public static Partition partition(Owner owner, Map<String, Object> patch) {
        Map<String, Object> allowed = new LinkedHashMap<>();
        List<String> notOwned = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            Owner fieldOwner = ownerOf(entry.getKey());
            if (fieldOwner == owner) allowed.put(entry.getKey(), entry.getValue());
            else if (fieldOwner != null) notOwned.add(entry.getKey());
            else unknown.add(entry.getKey());
        }
        return new Partition(allowed, notOwned, unknown);
    }
}
