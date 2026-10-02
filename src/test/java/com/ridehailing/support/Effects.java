package com.ridehailing.support;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A test-only table where scripted handlers record each effect, so a test can count how often one committed. */
public final class Effects {

    private Effects() {
    }

    public static void reset(JdbcClient jdbc) {
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS test_support").update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS test_support.effects (
                    seq    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    source text NOT NULL,
                    ref    text NOT NULL)
                """).update();
        jdbc.sql("TRUNCATE test_support.effects").update();
    }

    public static void record(JdbcClient jdbc, String source, String ref) {
        jdbc.sql("INSERT INTO test_support.effects (source, ref) VALUES (:source, :ref)")
                .param("source", source)
                .param("ref", ref)
                .update();
    }

    public static long count(JdbcClient jdbc, String source, String ref) {
        return jdbc.sql("SELECT count(*) FROM test_support.effects WHERE source = :source AND ref = :ref")
                .param("source", source)
                .param("ref", ref)
                .query(Long.class)
                .single();
    }

    /** The refs a source recorded, in commit order of their inserts' sequence numbers. */
    public static List<String> refs(JdbcClient jdbc, String source) {
        return jdbc.sql("SELECT ref FROM test_support.effects WHERE source = :source ORDER BY seq")
                .param("source", source)
                .query(String.class)
                .list();
    }
}
