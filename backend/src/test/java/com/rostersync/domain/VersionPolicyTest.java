package com.rostersync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.domain.VersionPolicy.Decision;
import org.junit.jupiter.api.Test;

class VersionPolicyTest {

    @Test
    void appliesTheFirstVersionEverSeen() {
        assertThat(VersionPolicy.decide(null, 1)).isEqualTo(Decision.APPLY);
    }

    @Test
    void appliesANewerVersion() {
        assertThat(VersionPolicy.decide(6L, 7)).isEqualTo(Decision.APPLY);
    }

    @Test
    void sameVersionIsADuplicate() {
        assertThat(VersionPolicy.decide(7L, 7)).isEqualTo(Decision.DUPLICATE);
    }

    @Test
    void olderVersionIsStale() {
        assertThat(VersionPolicy.decide(7L, 6)).isEqualTo(Decision.STALE);
    }

    @Test
    void worksWithEpochMillisecondVersions() {
        assertThat(VersionPolicy.decide(1_761_800_000_000L, 1_761_800_000_001L)).isEqualTo(Decision.APPLY);
    }
}
