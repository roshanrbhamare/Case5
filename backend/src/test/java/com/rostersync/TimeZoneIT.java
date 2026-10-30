package com.rostersync;

import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** "Future bookings" are decided in SQL with current_date, which has to be India's date. */
@IntegrationTest
class TimeZoneIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void rideDatesFollowIndiaTimeEvenWhenTheJvmRunsInUtc() {
        assertThat(jdbc.queryForObject("SELECT current_setting('TimeZone')", String.class)).isEqualTo("Asia/Kolkata");
        assertThat(jdbc.queryForObject("SELECT current_date", LocalDate.class)).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    }
}
