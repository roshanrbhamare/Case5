package com.rostersync.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.stream.Collectors;

/** Small JDBC helpers shared by the repositories. */
final class Sql {

    private Sql() {}

    /** Postgres array literal for {@code ?::smallint[]} / {@code ?::bigint[]} parameters. */
    static String numberArray(Collection<? extends Number> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    /** Postgres text[] literal; every element quoted and escaped. */
    static String textArray(Collection<String> values) {
        return values.stream().map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").collect(Collectors.joining(",", "{", "}"));
    }

    static OffsetDateTime timestamp(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
