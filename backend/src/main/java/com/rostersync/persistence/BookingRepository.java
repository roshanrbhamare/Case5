package com.rostersync.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {

    public record BookingRow(UUID id, LocalDate rideDate, String status, String cancelReason, String direction, String shiftCode,
                             String officeCode, int capacity, int seatsBooked) {}

    public record CancelResult(int cancelled, int tripsFreed) {}

    private final JdbcTemplate jdbc;

    public BookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cancels every future booking and gives the seat back to its trip, in one statement.
     * Only BOOKED rows are touched, so running it twice is a no-op.
     */
    public CancelResult cancelFutureForEmployee(String tenantId, String employeeId, String reason) {
        return jdbc.queryForObject("""
                WITH cancelled AS (
                  UPDATE bookings SET status = 'CANCELLED', cancel_reason = ?, updated_at = now()
                   WHERE tenant_id = ? AND employee_id = ? AND status = 'BOOKED' AND ride_date >= current_date
                  RETURNING trip_id
                ), freed AS (
                  UPDATE trips t SET seats_booked = t.seats_booked - c.n
                    FROM (SELECT trip_id, count(*)::int AS n FROM cancelled GROUP BY trip_id) c
                   WHERE t.id = c.trip_id AND t.tenant_id = ?
                  RETURNING t.id
                )
                SELECT (SELECT count(*) FROM cancelled)::int AS cancelled, (SELECT count(*) FROM freed)::int AS trips_freed""",
                (rs, i) -> new CancelResult(rs.getInt("cancelled"), rs.getInt("trips_freed")), reason, tenantId, employeeId, tenantId);
    }

    public List<BookingRow> upcomingForEmployee(String tenantId, String employeeId, int limit) {
        return jdbc.query("""
                SELECT b.id, b.ride_date, b.status, b.cancel_reason, t.direction, t.shift_code, t.office_code, t.capacity, t.seats_booked
                  FROM bookings b JOIN trips t ON t.id = b.trip_id
                 WHERE b.tenant_id = ? AND b.employee_id = ? AND b.ride_date >= current_date
                 ORDER BY b.ride_date, t.direction LIMIT ?""",
                (rs, i) -> new BookingRow(rs.getObject("id", UUID.class), Sql.date(rs, "ride_date"), rs.getString("status"),
                        rs.getString("cancel_reason"), rs.getString("direction"), rs.getString("shift_code"), rs.getString("office_code"),
                        rs.getInt("capacity"), rs.getInt("seats_booked")),
                tenantId, employeeId, limit);
    }
}
