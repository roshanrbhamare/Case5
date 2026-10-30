package com.rostersync;

import static com.rostersync.support.Fixtures.event;
import static com.rostersync.support.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.service.EmployeeQueryService;
import com.rostersync.service.IngestService;
import com.rostersync.support.TestDb;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Redis pointed at a port where nothing listens: writes and reads must still work. */
@SpringBootTest(properties = "spring.data.redis.url=redis://localhost:6390/0")
@ActiveProfiles("test")
@Tag("integration")
class RedisOutageIT {

    @Autowired IngestService ingest;
    @Autowired EmployeeQueryService employees;
    @Autowired MeterRegistry registry;

    @Test
    void withRedisDown_writesAndReadsStillWork_cacheFailuresOnlyCountAsMisses() {
        TestDb.reset(Map.of("acme", 3));

        var result = ingest.ingest("acme", List.of(event("UPDATE", "E1002", 2, snapshot("shiftCode", "NIGHT"))), "test:hr");

        assertThat(result.results().get(0).outcome().name()).isEqualTo("APPLIED");
        assertThat(employees.get("acme", "E1002").hr().shiftCode()).isEqualTo("NIGHT");
        assertThat(registry.find("rostersync.cache.requests").tag("result", "error").counters()).isNotEmpty();
    }
}
