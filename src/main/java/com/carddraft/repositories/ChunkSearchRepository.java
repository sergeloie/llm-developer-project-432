package com.carddraft.repositories;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Chunk retrieval: by meaning, by word, and by both.
 *
 * <p>Written as SQL rather than through a vector-store abstraction, because the interesting query
 * here is the merge and an abstraction over it would be one more layer between the reader and the
 * window function that does the work.
 *
 * <p>Hybrid exists because neither mode is sufficient, and the failure is different in each
 * direction. Measured on the supplied documents: a question about a characteristic lands 0.50 from
 * the right fragment while an article-number query lands 0.16 from the same one — semantic search
 * treats "KTL-1700" as a string that resembles other strings. Word search has the mirror problem,
 * matching the article exactly and missing every paraphrase.
 */
@Repository
public class ChunkSearchRepository {

    /** Canonical from the original rank-fusion paper; other values work, this one is not a guess. */
    public static final int DEFAULT_RRF_K = 60;

    private static final String LIMIT_PARAM = "limit";
    private static final String DOCUMENTS_PARAM = "documents";
    private static final String SECTION_PARAM = "section";

    /**
     * Restricts a search to a set of documents, and optionally to one section.
     *
     * <p>Defined once because all three modes narrow the corpus identically, and a filter that
     * drifted between them would make hybrid retrieval disagree with its own halves. The whole
     * clause is bound parameters: the document ids are an array and the section a scalar, so no
     * caller value is ever spliced into the SQL text.
     */
    private static final String DOCUMENT_FILTER = """
             AND (CAST(:documents AS text[]) IS NULL OR c.document_id = ANY(CAST(:documents AS text[])))
             AND (CAST(:section AS text) IS NULL OR c.section = CAST(:section AS text))
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ChunkSearchRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /**
     * A retrieved chunk with the score that put it where it is.
     *
     * @param score in vector mode a distance where lower is better; in word mode the rank score
     *              where higher is better; in hybrid mode the fused contribution. The three are not
     *              comparable and must not be compared — only the ordering within a mode is
     *              meaningful.
     * @param matchedBy which modes found it, so a caller can see why something ranked highly
     */
    public record Hit(long chunkId, String documentId, int page, String section, String text,
                      double score, String matchedBy) {

        /**
         * The modes that found this chunk, as a list.
         *
         * <p>A string in the constructor and a list here rather than a list in both: PostgreSQL's
         * array type has no converter registered in a project with no ORM to bring one, and
         * registering one for a single column would be the abstraction layer the ADR rules out. The
         * split belongs at the boundary anyway, so the database hands over what it can send and
         * this hands over what callers want.
         */
        public List<String> matchedByModes() {
            return matchedBy == null || matchedBy.isBlank() ? List.of() : List.of(matchedBy.split(","));
        }
    }

    /** Restricts retrieval to specific documents, and optionally to a section within them. */
    public record Filter(List<String> documentIds, String section) {

        public static Filter all() {
            return new Filter(null, null);
        }

        boolean isEmpty() {
            return documentIds == null && section == null;
        }
    }

    /**
     * Writes vectors in one round trip.
     *
     * <p>A batch rather than a loop of single updates: vectors are wide rows and a corpus is
     * thousands of them, so one statement per chunk turns indexing into a round trip per row.
     * {@code JdbcTemplate} carries the batch API that {@code JdbcClient} does not expose; the
     * reads stay on {@code JdbcClient} because they never needed it.
     */
    public void writeVectors(List<ChunkVector> vectors) {
        if (vectors.isEmpty()) {
            return;
        }
        template.batchUpdate(
                "UPDATE chunks SET embedding = CAST(? AS vector) WHERE id = ?",
                vectors,
                vectors.size(),
                (statement, vector) -> {
                    statement.setString(1, toVectorLiteral(vector.values()));
                    statement.setLong(2, vector.chunkId());
                });
    }

    /**
     * Chunks that have text and no vector — the whole of both the backfill sweep and the indexing
     * step that follows a parse.
     *
     * <p>No filter on the document's state, and the absence is deliberate. It used to select only
 * documents already marked {@code indexed}, on the reasoning that a chunk whose document never
 * finished should not be embedded. That reasoning inverted once {@code indexed} came to mean "every
 * chunk has a vector": gating the embedding on it asks for the thing being produced, so the sweep
 * could only ever find documents that were already finished. The gate a chunk actually needs is the
 * one that is not a choice — a row in this table exists because a parse wrote it, and a document the
 * parser refused has no rows.
     */
    public List<ChunkToEmbed> chunksWithoutVectors(int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.document_id, c.section, c.text
                          FROM chunks c
                         WHERE c.embedding IS NULL
                          ORDER BY c.document_id, c.ordinal
                          LIMIT :limit
                        """)
                .param(LIMIT_PARAM, limit)
                .query(ChunkToEmbed.class)
                .list();
    }

    /** The same, for one document, so an upload embeds what it just parsed and nothing else. */
    public List<ChunkToEmbed> chunksWithoutVectors(String documentId, int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.document_id, c.section, c.text
                          FROM chunks c
                         WHERE c.document_id = :documentId
                           AND c.embedding IS NULL
                          ORDER BY c.ordinal
                          LIMIT :limit
                        """)
                .param("documentId", documentId)
                .param(LIMIT_PARAM, limit)
                .query(ChunkToEmbed.class)
                .list();
    }

    public int countWithoutVectors() {
        return jdbc.sql("SELECT count(*) AS c FROM chunks WHERE embedding IS NULL")
                .query(Integer.class)
                .single();
    }

    public int countWithoutVectors(String documentId) {
        return jdbc.sql("SELECT count(*) AS c FROM chunks WHERE document_id = :id AND embedding IS NULL")
                .param("id", documentId)
                .query(Integer.class)
                .single();
    }

    /** Nearest neighbours by meaning. Distance, so lower is better. */
    public List<Hit> searchByVector(List<Double> queryVector, Filter filter, int limit, double maxDistance) {
        return jdbc.sql("""
                        SELECT c.id AS chunk_id, c.document_id, c.page, c.section, c.text,
                               c.embedding <=> CAST(:vector AS vector) AS score, 'vector' AS matched_by
                          FROM chunks c
                         WHERE c.embedding IS NOT NULL
                           AND c.embedding <=> CAST(:vector AS vector) <= :maxDistance
                        """ + DOCUMENT_FILTER + """
                         ORDER BY score
                         LIMIT :limit
                        """)
                .param("vector", toVectorLiteral(queryVector))
                .param("maxDistance", maxDistance)
                .param(DOCUMENTS_PARAM, filter.documentIds() == null ? null : filter.documentIds().toArray(new String[0]))
                .param(SECTION_PARAM, filter.section())
                .param(LIMIT_PARAM, limit)
                .query(Hit.class)
                .list();
    }

    /**
     * Word search. The websearch-to-tsquery form is used so an article number reaches the index
     * as one token rather than being split, and so that a stray operator character in a query
     * cannot become a syntax error.
     */
    public List<Hit> searchByText(String query, Filter filter, int limit) {
        return jdbc.sql("""
                        SELECT c.id AS chunk_id, c.document_id, c.page, c.section, c.text,
                               ts_rank(c.search_text, websearch_to_tsquery('simple', :query)) AS score, 'text' AS matched_by
                          FROM chunks c
                         WHERE c.search_text @@ websearch_to_tsquery('simple', :query)
                        """ + DOCUMENT_FILTER + """
                         ORDER BY score DESC
                         LIMIT :limit
                        """)
                .param("query", query)
                .param(DOCUMENTS_PARAM, filter.documentIds() == null ? null : filter.documentIds().toArray(new String[0]))
                .param(SECTION_PARAM, filter.section())
                .param(LIMIT_PARAM, limit)
                .query(Hit.class)
                .list();
    }

    /**
     * Reciprocal rank fusion of the two lists.
     *
     * <p>Each list contributes 1/(k + rank) for every chunk it contains, so a chunk found by both
     * outranks a chunk found by one — which is the whole property, and the reason scores from the
     * two modes never have to be reconciled onto one scale. Only positions matter, so the
     * incomparable distances and rank scores are never compared to each other.
     *
     * <p>Written as one statement with window functions because that is where the ranks come from.
     * Pulling both lists into Java and merging them there would move the ranking out of the
     * database, where the rest of retrieval already lives.
     */
    public List<Hit> searchHybrid(List<Double> queryVector, String textQuery, Filter filter,
                                  int limit, double maxDistance, int perListLimit, int rrfK) {
        return jdbc.sql("""
                        WITH vector_hits AS (
                            SELECT c.id, c.document_id, c.page, c.section, c.text,
                                   row_number() OVER (ORDER BY c.embedding <=> CAST(:vector AS vector)) AS rank
                              FROM chunks c
                             WHERE c.embedding IS NOT NULL
                               AND c.embedding <=> CAST(:vector AS vector) <= :maxDistance
                        """ + DOCUMENT_FILTER + """
                             ORDER BY rank
                             LIMIT :perList
                         ),
                         text_hits AS (
                            SELECT c.id, c.document_id, c.page, c.section, c.text,
                                   row_number() OVER (ORDER BY ts_rank(c.search_text,
                                       websearch_to_tsquery('simple', :query)) DESC) AS rank
                              FROM chunks c
                             WHERE c.search_text @@ websearch_to_tsquery('simple', :query)
                        """ + DOCUMENT_FILTER + """
                             ORDER BY rank
                             LIMIT :perList
                         ),
                         fused AS (
                            SELECT id, document_id, page, section, text,
                                   sum(contribution) AS score,
                                   string_agg(source, ',' ORDER BY source) AS matched_by
                              FROM (
                                  SELECT id, document_id, page, section, text,
                                         1.0 / (:k + rank) AS contribution, 'vector' AS source
                                    FROM vector_hits
                                   UNION ALL
                                  SELECT id, document_id, page, section, text,
                                         1.0 / (:k + rank) AS contribution, 'text' AS source
                                    FROM text_hits
                              ) sides
                             GROUP BY id, document_id, page, section, text
                        )
                        SELECT id AS chunk_id, document_id, page, section, text, score, matched_by
                          FROM fused
                         ORDER BY score DESC
                         LIMIT :limit
                        """)
                .param("vector", toVectorLiteral(queryVector))
                .param("query", textQuery == null ? "" : textQuery)
                .param("maxDistance", maxDistance)
                .param(DOCUMENTS_PARAM, filter.documentIds() == null ? null : filter.documentIds().toArray(new String[0]))
                .param(SECTION_PARAM, filter.section())
                .param("perList", perListLimit)
                .param("k", rrfK)
                .param(LIMIT_PARAM, limit)
                .query(Hit.class)
                .list();
    }

    public record ChunkToEmbed(long id, String documentId, String section, String text) {
    }

    public record ChunkVector(long chunkId, List<Double> values) {
    }

    /**
     * pgvector's text form for a vector.
     *
     * <p>Written by hand rather than through the driver's vector type because the project has no
     * ORM and adding one type for a single column would be the layer the ADR rules out. Values are
     * formatted at full precision: truncating them to fit a string would quietly change every
     * distance computed afterwards.
     */
    static String toVectorLiteral(List<Double> values) {
        StringBuilder literal = new StringBuilder(values.size() * 20).append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(values.get(i));
        }
        return literal.append(']').toString();
    }

    public List<Hit> byIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        jdbc.sql("""
                        SELECT id AS chunk_id, document_id, page, section, text, 0.0 AS score,
                               'by-id' AS matched_by
                          FROM chunks WHERE id = ANY(:ids)
                        """)
                .param("ids", ids.toArray(new Long[0]))
                .query(Hit.class)
                .list()
                .forEach(hits::add);
        return hits;
    }
}
