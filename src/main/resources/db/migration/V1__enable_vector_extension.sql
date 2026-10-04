-- Activates the vector extension. This statement does not install anything: it can only
-- activate an extension that is already compiled into the server, which is why the database
-- image is pgvector's rather than stock postgres. See ADR-0001.
--
-- The table recording applied versions is created and maintained by Flyway itself
-- (flyway_schema_history), which is the "runner remembers what it applied" mechanism the
-- assignment asks for.
CREATE EXTENSION IF NOT EXISTS vector;
