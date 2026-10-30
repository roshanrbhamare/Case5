package com.rostersync.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every tunable in one typed, validated object (bound from the {@code rostersync.*} keys in
 * application.yml). A bad value stops the app at startup instead of misbehaving at 2 AM.
 */
@Validated
@ConfigurationProperties(prefix = "rostersync")
public record RosterSyncProperties(
        @Size(min = 32, message = "must be at least 32 characters (HS256 key)") String jwtSecret,
        Duration jwtTtl,
        String corsOrigins,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") double safetyBrakeThreshold,
        Reconcile reconcile,
        Outbox outbox,
        Stream stream,
        Cache cache,
        Chaos chaos) {

    public record Reconcile(@Min(1) int bucketGroup, @Min(1) int maxUploadMb, @Min(10) int leaseSeconds, @Min(10) long pollMs) {}

    public record Outbox(@Min(1) int batchSize, @Min(10) long pollMs, @Min(1) int maxAttempts) {}

    public record Stream(@NotBlank String name, @Min(1000) long maxLength, @NotBlank String consumerGroup, @Min(10) long reclaimMs) {}

    public record Cache(Duration employeeTtl, Duration officesTtl, Duration apiKeyTtl) {}

    public record Chaos(String crashAt, boolean allowed) {}
}
