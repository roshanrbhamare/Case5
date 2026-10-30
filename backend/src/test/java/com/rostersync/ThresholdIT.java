package com.rostersync;

import static com.rostersync.support.Fixtures.currentExport;
import static com.rostersync.support.Fixtures.streamOf;
import static com.rostersync.support.Fixtures.toCsv;
import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.persistence.ReconcileRepository.RunRow;
import com.rostersync.persistence.ReconcileRepository.RunStatus;
import com.rostersync.service.ReconcileService;
import com.rostersync.support.TestDb;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The safety-brake threshold comes from configuration (SAFETY_BRAKE_THRESHOLD). */
@SpringBootTest(properties = "rostersync.safety-brake-threshold=0.5")
@ActiveProfiles("test")
@Tag("integration")
class ThresholdIT {

    @Autowired ReconcileService reconcile;

    @Test
    void aLenientThresholdLetsA40PercentGapThrough() {
        TestDb.reset(Map.of("acme", 40));
        RunRow staged = reconcile.stageUpload("acme", streamOf(toCsv(currentExport("acme").subList(0, 24))), "test:hr");

        RunRow run = reconcile.processRun("acme", staged.id(), "inline");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.threshold()).isEqualTo(0.5);
    }
}
