package com.ridehailing.audit.db;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The monthly partitions of {@code audit.audit_log}, named {@code audit_log_yyyy_mm}. Names and bounds are formatted
 * from a {@link YearMonth}'s digits only, so building the DDL from them is safe.
 */
@Repository
public class AuditPartitions {

    private static final Pattern NAME = Pattern.compile("audit_log_(\\d{4})_(\\d{2})");

    private final JdbcClient jdbc;

    AuditPartitions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<YearMonth> existing() {
        return jdbc.sql("""
                        SELECT c.relname FROM pg_catalog.pg_inherits i
                        JOIN pg_catalog.pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = 'audit.audit_log'::regclass
                        """)
                .query(String.class)
                .list()
                .stream()
                .map(NAME::matcher)
                .filter(Matcher::matches)
                .map(name -> YearMonth.of(Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2))))
                .sorted()
                .toList();
    }

    /** Must run in a transaction: the lock timeout keeps the DDL from queueing inserts behind a long transaction. */
    public void create(YearMonth month) {
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
        jdbc.sql("CREATE TABLE IF NOT EXISTS audit." + name(month) + " PARTITION OF audit.audit_log"
                        + " FOR VALUES FROM ('" + start(month) + "') TO ('" + start(month.plusMonths(1)) + "')")
                .update();
    }

    /** Must run in a transaction, like {@link #create}. */
    public void drop(YearMonth month) {
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
        jdbc.sql("DROP TABLE IF EXISTS audit." + name(month)).update();
    }

    private static String name(YearMonth month) {
        Objects.requireNonNull(month, "month");
        return "audit_log_%04d_%02d".formatted(month.getYear(), month.getMonthValue());
    }

    private static String start(YearMonth month) {
        return "%04d-%02d-01 00:00:00+00".formatted(month.getYear(), month.getMonthValue());
    }
}
