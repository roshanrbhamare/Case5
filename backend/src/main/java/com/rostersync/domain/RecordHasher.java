package com.rostersync.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RecordHasher {

    private static final char SEPARATOR = '\u001f';

    private RecordHasher() {}

    /**
     * Hash of everything HR owns. Equal hashes mean "same HR content", which lets the nightly
     * diff compare 128-bit digests instead of whole records. Status is compared separately,
     * so deactivating someone never requires recomputing their hash.
     */
    public static String hrHash(String employeeId, HrSnapshot hr) {
        StringBuilder canonical = new StringBuilder(employeeId);
        for (String field : Fields.HR) {
            String value = hr == null ? null : hr.get(field);
            canonical.append(SEPARATOR).append(value == null ? "" : value);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** FNV-1a over the id: stable, fast and evenly spread for short ids. */
    public static int bucketOf(String employeeId) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < employeeId.length(); i++) {
            hash ^= employeeId.charAt(i);
            hash *= 0x01000193;
        }
        return (int) (Integer.toUnsignedLong(hash) % Fields.BUCKETS);
    }
}
