package com.carddraft.core;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/**
 * The application's connection pool and the only sanctioned way to borrow from it.
 *
 * <p>The pool is an application-scoped resource: Spring creates it at startup from the
 * datasource configuration and closes it when the context shuts down. This class adds no pool
 * of its own — a second one would mean connection count was a function of how many components
 * felt like owning a pool, which is exactly what this class exists to prevent.
 *
 * <p>The rule this exists to enforce: a connection is taken for an operation, never for a
 * whole workflow step. A generation step waits on the model for minutes, and a connection held
 * across that wait occupies a pool slot while doing nothing. Status updates take their own
 * short borrow instead.
 */
@Component
public class Database {

    private final DataSource pool;

    public Database(DataSource pool) {
        this.pool = pool;
    }

    public DataSource pool() {
        return pool;
    }

    /**
     * Borrows a connection. Always use it in try-with-resources so the connection returns to
     * the pool even when the operation throws.
     */
    public Connection connection() throws SQLException {
        return pool.getConnection();
    }
}
