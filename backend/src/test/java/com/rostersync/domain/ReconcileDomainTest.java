package com.rostersync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rostersync.domain.Fields.InactiveReason;
import com.rostersync.domain.Fields.Status;
import com.rostersync.domain.ReconcilePolicy.Action;
import com.rostersync.domain.ReconcilePolicy.FileRow;
import com.rostersync.domain.ReconcilePolicy.Stored;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReconcileDomainTest {

    @Nested
    class SafetyBrakeRules {

        final SafetyBrake brake = new SafetyBrake(0.3);

        @Test
        void tripsWhen30PercentOrMoreAreMissing() {
            assertThat(brake.evaluate(200_000, 80_000).tripped()).isTrue();
            assertThat(brake.evaluate(200_000, 80_000).missingRatio()).isEqualTo(0.4);
            assertThat(brake.evaluate(1000, 300).tripped()).isTrue();
        }

        @Test
        void doesNotTripJustBelowTheThreshold() {
            assertThat(brake.evaluate(1000, 299).tripped()).isFalse();
        }

        @Test
        void doesNotTripOnTheVeryFirstLoad() {
            assertThat(brake.evaluate(0, 0).tripped()).isFalse();
        }

        @Test
        void thresholdIsConfigurable() {
            assertThat(new SafetyBrake(0.05).evaluate(100, 6).tripped()).isTrue();
            assertThat(new SafetyBrake(0.5).evaluate(100, 40).tripped()).isFalse();
        }

        @Test
        void refusesANonsensicalThreshold() {
            assertThatThrownBy(() -> new SafetyBrake(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SafetyBrake(1.5)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Stored stored(Status status, InactiveReason reason) {
        return new Stored(5, "h5", status, reason);
    }

    static Stream<Arguments> decisions() {
        return Stream.of(
                Arguments.of("unknown employee", new FileRow(1, "x"), null, Action.CREATE),
                Arguments.of("newer version", new FileRow(6, "h6"), stored(Status.ACTIVE, null), Action.UPDATE),
                Arguments.of("newer version for someone who left", new FileRow(6, "h6"), stored(Status.INACTIVE, InactiveReason.HR_EXIT), Action.REACTIVATE),
                Arguments.of("live feed is ahead of the file", new FileRow(4, "h4"), stored(Status.ACTIVE, null), Action.STALE),
                Arguments.of("identical", new FileRow(5, "h5"), stored(Status.ACTIVE, null), Action.UNCHANGED),
                Arguments.of("same version, different content", new FileRow(5, "zz"), stored(Status.ACTIVE, null), Action.CONFLICT),
                Arguments.of("back after an export dropped them", new FileRow(5, "h5"), stored(Status.INACTIVE, InactiveReason.MISSING_FROM_EXPORT), Action.REACTIVATE),
                Arguments.of("listed although HR said they left at this version", new FileRow(5, "h5"), stored(Status.INACTIVE, InactiveReason.HR_EXIT), Action.CONFLICT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decisions")
    void reconcilePolicy(String name, FileRow file, Stored stored, Action expected) {
        assertThat(ReconcilePolicy.decide(file, stored)).isEqualTo(expected);
    }

    @Nested
    class Hashing {

        final HrSnapshot hr = new HrSnapshot("A", "a@x.io", "+919800000001", null, "BLR-WF", "S0900");

        @Test
        void isStableAndSensitiveToEveryHrField() {
            String base = RecordHasher.hrHash("E1", hr);
            assertThat(RecordHasher.hrHash("E1", new HrSnapshot("A", "a@x.io", "+919800000001", null, "BLR-WF", "S0900"))).isEqualTo(base);
            for (String field : Fields.HR) {
                HrSnapshot changed = new HrSnapshot(
                        field.equals("fullName") ? "Ax" : hr.fullName(), field.equals("email") ? "x@x.io" : hr.email(),
                        field.equals("phone") ? "+919800000002" : hr.phone(), field.equals("managerId") ? "M1" : hr.managerId(),
                        field.equals("officeCode") ? "PNQ" : hr.officeCode(), field.equals("shiftCode") ? "NIGHT" : hr.shiftCode());
                assertThat(RecordHasher.hrHash("E1", changed)).as(field).isNotEqualTo(base);
            }
            assertThat(RecordHasher.hrHash("E2", hr)).isNotEqualTo(base);
            assertThat(base).hasSize(32);
        }

        @Test
        void spreadsIdsEvenlyAcrossBuckets() {
            int[] counts = new int[Fields.BUCKETS];
            for (int i = 0; i < 100_000; i++) counts[RecordHasher.bucketOf("E" + i)]++;
            double expected = 100_000.0 / Fields.BUCKETS;
            for (int c : counts) assertThat(c).isBetween((int) (expected * 0.8), (int) (expected * 1.2));
        }
    }
}
