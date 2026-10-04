package com.carddraft.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Reports whether this instance can actually serve traffic: the database answers, and the
 * vector extension is present.
 *
 * <p>The extension check earns its place. {@code CREATE EXTENSION vector} activates something
 * already compiled in, so a wrong database image produces a schema that migrates cleanly and
 * then fails at the first search. Reporting it here turns that into a startup-time fact.
 */
@Component
public class ReadinessProbe {

    private static final String VECTOR_VERSION_SQL =
            "SELECT extversion FROM pg_extension WHERE extname = 'vector'";

    private final Database database;

    public ReadinessProbe(Database database) {
        this.database = database;
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
        try (var connection = database.connection();
             var statement = connection.createStatement()) {
            return statement.execute("SELECT 1") ? up() : down("query returned no result set");
        } catch (SQLException e) {
            return down(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, Object> vectorExtensionCheck() {
        try (var connection = database.connection();
             var statement = connection.prepareStatement(VECTOR_VERSION_SQL);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return down("extension not installed in this database");
            }
            Map<String, Object> check = up();
            check.put("version", result.getString(1));
            return check;
        } catch (SQLException e) {
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
