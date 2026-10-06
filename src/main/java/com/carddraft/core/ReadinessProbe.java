package com.carddraft.core;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reports whether this instance can actually serve traffic: the database answers, and the
 * vector extension is present.
 *
 * <p>The extension check earns its place. {@code CREATE EXTENSION vector} activates something
 * already compiled in, so a wrong database image produces a schema that migrates cleanly and
 * then fails at the first search. Reporting it here turns that into a startup-time fact.
 *
 * <p>Reads through {@code JdbcClient} like every other query in the service. The probe used
 * to borrow raw connections from a hand-rolled pool wrapper; that wrapper existed for one
 * caller, and one caller is not a reason to keep a second way of reaching the database.
 */
@Component
public class ReadinessProbe {

    private final JdbcClient jdbc;

    public ReadinessProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> check() {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("database", databaseCheck());
        checks.put("vectorExtension", vectorExtensionCheck());

        boolean ready = checks.values().stream()
                .allMatch(check -> "UP".equals(((Map<?, ?>) check).get("status")));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", ready ? "UP" : "DOWN");
        report.put("checks", checks);
        return report;
    }

    private Map<String, Object> databaseCheck() {
        try {
            Integer answer = jdbc.sql("SELECT 1").query(Integer.class).single();
            return Integer.valueOf(1).equals(answer) ? up() : down("query returned no result set");
        } catch (RuntimeException e) {
            return down(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, Object> vectorExtensionCheck() {
        try {
            return jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                    .query(String.class)
                    .optional()
                    .map(version -> {
                        Map<String, Object> check = up();
                        check.put("version", version);
                        return check;
                    })
                    .orElseGet(() -> down("extension not installed in this database"));
        } catch (RuntimeException e) {
            return down(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, Object> up() {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("status", "UP");
        return check;
    }

    private Map<String, Object> down(String reason) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("status", "DOWN");
        check.put("reason", reason);
        return check;
    }
}
