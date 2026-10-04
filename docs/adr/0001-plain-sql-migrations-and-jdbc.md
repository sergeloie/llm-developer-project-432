# Plain SQL migrations with Flyway, and JDBC without an ORM

The project needs a vector column, an inverted index on chunk text, generated columns, and
queries with window functions for hybrid rank fusion. An ORM would put a translation layer
between us and exactly that SQL, so persistence is hand-written SQL through Spring's
`JdbcClient`, and schema changes are versioned `V*.sql` files applied by Flyway.

Flyway is deliberately chosen over writing our own migration runner, even though the
assignment asks for "plain SQL files and a simple runner that remembers applied versions in
a table". Flyway *is* that: ordered SQL files plus `flyway_schema_history`. Writing the
runner ourselves would reimplement its checksums, locking, and partial-failure handling for
no gain.

**Considered Options**

- *Alembic-equivalent / hand-rolled runner* — rejected. Same model, more code, and we'd own
  the failure modes.
- *Spring Data JPA / Hibernate* — rejected. Window-function rank fusion and pgvector index
  DDL are written far more clearly as SQL, and the assignment states SQL lives only in the
  repository layer.

**Consequences**

Repositories return domain records, not entity graphs. There is no change tracking, so
every state transition is an explicit `UPDATE`. Money is `NUMERIC(18,8)` mapped to
`BigDecimal`; token counts are `INTEGER`.