package com.carddraft.search;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.carddraft.embeddings.EmbeddingModel;
import com.carddraft.repositories.ChunkSearchRepository;
import com.carddraft.repositories.ChunkSearchRepository.Filter;
import com.carddraft.repositories.ChunkSearchRepository.Hit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Hybrid retrieval against a real pgvector, with a scripted model.
 *
 * <p>The model is scripted because what is under test is the retrieval, not the encoding. A real
 * model would make the ranking a property of its weights and of the day's mood; here the vectors
 * are chosen so the two modes disagree, which is the situation fusion exists for.
 *
 * <p>Against a real database because the query under test is a window function and a distance
 * operator. Neither is verified by asserting on a string, and the two defects that actually bit
 * during development — an untyped null parameter and a reserved word used as an alias — were both
 * invisible to anything but execution.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(ChunkSearchRepository.class)
class HybridRetrievalTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card")
            .withUsername("card")
            .withPassword("card");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    /**
     * A model whose answers are fixed, so the test states the ranking it expects rather than
     * discovering one.
     *
     * <p>Vectors are real 768-wide, because the column is 768 wide and the production code checks
     * responses against that width instead of trusting it — a four-element vector would be refused
     * by the column, which is the property being leaned on here.
     *
     * <p>They are built from orthogonal axes, so the numbers are readable: distance from a query
     * on axis 0 to a vector on axis 0 is 0, to a weighted blend is the blend's complement, and to
     * another axis is 1. Deriving the geometry beats writing out literals nobody can check by eye.
     */
    @TestConfiguration
    static class ScriptedModel {

        static final int WIDTH = 768;

        /** 1.0 on one axis, zeros elsewhere. */
        static List<Double> axis(int index) {
            return weighted(index, 1.0);
        }

        /**
         * {@code weight} on {@code index}, the remainder on the next axis.
         *
         * <p>Cosine distance from {@code axis(index)} is then {@code 1 - weight}.
         */
        static List<Double> weighted(int index, double weight) {
            List<Double> values = new ArrayList<>(WIDTH);
            for (int i = 0; i < WIDTH; i++) {
                values.add(0.0);
            }
            values.set(index, weight);
            values.set((index + 1) % WIDTH, 1.0 - weight);
            return values;
        }

        /** The question is about a passport, so it sits exactly on the passport axis. */
        static final List<Double> PASSPORT_QUERY = axis(0);

        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return new EmbeddingModel() {
                @Override
                public List<Double> embedQuery(String query) {
                    return PASSPORT_QUERY;
                }

                @Override
                public List<Double> embedDocument(String text, String title) {
                    return switch (text) {
                        case "passport fragment" -> axis(0);
                        case "kettle fragment" -> weighted(0, 0.6);
                        default -> axis(2);
                    };
                }

                @Override
                public List<List<Double>> embedDocuments(List<Document> documents) {
                    return documents.stream().map(d -> embedDocument(d.text(), d.title())).toList();
                }

                @Override
                public int dimension() {
                    return WIDTH;
                }
            };
        }

    }

    static final String PASSPORT_SECTION = "Technical specifications";
    static final String KETTLE_SECTION = "Specifications";
    static final String UNRELATED_SECTION = "Miscellaneous";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ChunkSearchRepository repository;

    /**
     * The property fusion exists for.
     *
     * <p>Vector ranks: passport 1, kettle 2. Word ranks: passport 1 only. So the passport fragment
     * collects 1/61 + 1/61 and the kettle fragment 1/62, and the one found twice wins outright.
     */
    @Test
    void aFragmentFoundByBothModesOutranksOneFoundByEither() {
        seed();

        List<Hit> fused = repository.searchHybrid(
                ScriptedModel.PASSPORT_QUERY, "passport", Filter.all(), 10, 0.9, 5, 60);

        assertThat(fused).extracting(Hit::text)
                .containsExactly("passport fragment", "kettle fragment");

        assertThat(fused.get(0).matchedByModes())
                .as("the winner is the one both modes agreed on")
                .containsExactlyInAnyOrder("vector", "text");
        assertThat(fused.get(1).matchedByModes())
                .as("and the runner-up was found by meaning alone")
                .containsExactly("vector");
    }

    /**
     * Word search finds things vector search refuses.
     *
     * <p>A paragraph mentioning "passport" in an unrelated document sits at distance 1.0 and is
     * excluded by the calibrated threshold, yet it is the only thing that answers a question asked
     * in words. This is the concrete case for keeping word search at all.
     */
    @Test
    void aFragmentOnlyWordSearchCanFindStillReachesTheCaller() {
        seed();
        long photoId = insertChunk("doc-unrelated", 1, 2, "Photo requirements", "passport photograph");

        repository.writeVectors(List.of(new ChunkSearchRepository.ChunkVector(
                photoId, ScriptedModel.axis(2))));

        assertThat(repository.searchByVector(ScriptedModel.PASSPORT_QUERY, Filter.all(), 10, 0.5))
                .as("vector search rejects it on distance")
                .extracting(Hit::text)
                .doesNotContain("passport photograph");

        List<Hit> fused = repository.searchHybrid(
                ScriptedModel.PASSPORT_QUERY, "passport", Filter.all(), 10, 0.5, 5, 60);

        assertThat(fused).extracting(Hit::text).contains("passport photograph");
        assertThat(fused).filteredOn(hit -> hit.text().equals("passport photograph"))
                .singleElement()
                .extracting(Hit::matchedByModes)
                .as("and it is labelled, so a caller can see it matched on words only")
                .isEqualTo(List.of("text"));
    }

    /**
     * The calibrated threshold earns its place here.
     *
     * <p>An unrelated fragment is a perfect word match and semantically at distance 1.0. Vector
     * search cannot rank it away; the threshold removes it.
     */
    @Test
    void theCalibratedDistanceRemovesWhatVectorSearchCannotRankAway() {
        seed();

        assertThat(repository.searchByText("unrelated", Filter.all(), 10))
                .as("word search has no notion of relevance and happily returns it")
                .extracting(Hit::text)
                .contains("unrelated fragment");

        assertThat(repository.searchByVector(ScriptedModel.PASSPORT_QUERY, Filter.all(), 10, 0.5))
                .extracting(Hit::text)
                .containsExactlyInAnyOrder("passport fragment", "kettle fragment");
    }

    @Test
    void retrievalCanBeNarrowedToTheSelectedDocuments() {
        seed();

        List<Hit> kettleOnly = repository.searchByText("kettle", new Filter(List.of("doc-kettle"), null), 10);

        assertThat(kettleOnly).extracting(Hit::text).containsExactly("kettle fragment");
    }

/**
 * A filter has to reach the fusion as well as the single-mode queries.
 *
 * <p>Missing that is the easy mistake: the filters are written out again inside each CTE, so a
 * fusion that forgot one would quietly reintroduce documents the caller excluded.
 *
     * <p>Section labels are ASCII here on purpose. What is under test is that the predicate reaches
 * every subquery, and non-ASCII literals in a test about SQL plumbing add an encoding failure mode
 * that has nothing to do with the plumbing. Cyrillic sections are exercised where they belong, in
 * the parsing tests against the real documents.
     */
    @Test
    void aSectionFilterNarrowsBothTheSearchAndItsFusion() {
        seed();

        Filter passportSection = new Filter(null, PASSPORT_SECTION);

        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE search_text @@ websearch_to_tsquery('simple', :q)")
                        .param("q", "passport").query(Integer.class).single())
                .as("only the passport fragment contains the word at all").isEqualTo(1);

        assertThat(repository.searchByText("passport", Filter.all(), 10))
                .as("unfiltered, one fragment mentions it").hasSize(1);
        assertThat(repository.searchByText("passport", passportSection, 10))
                .as("and the section filter keeps that one").hasSize(1);
        assertThat(repository.searchByText("passport", new Filter(List.of("doc-kettle"), null), 10))
                .as("a filter that excludes the document finds nothing")
                .isEmpty();

        assertThat(repository.searchByVector(ScriptedModel.PASSPORT_QUERY, passportSection, 10, 0.9))
                .extracting(Hit::text)
                .containsExactly("passport fragment");

        assertThat(repository.searchHybrid(ScriptedModel.PASSPORT_QUERY, "passport", passportSection, 10, 0.9, 5, 60))
                .extracting(Hit::text)
                .containsExactly("passport fragment");
    }

    /**
     * An empty document list is not the same as no document list.
     *
     * <p>{@code null} means "every document"; an empty list means "none of them". Treating them the
     * same would let a caller who filtered everything down to nothing retrieve everything instead.
     */
    @Test
    void anEmptyDocumentFilterSelectsNothingRatherThanEverything() {
        seed();

        assertThat(repository.searchByText("passport", new Filter(List.of(), null), 10))
                .isEmpty();
        assertThat(repository.searchByText("passport", Filter.all(), 10))
                .as("which is the opposite of no filter at all")
                .hasSize(1);
    }

    /**
     * Vector formatting is asserted by consequence rather than by string.
     *
     * <p>A vector written through {@code writeVectors} and found again by a distance query proves
     * the literal was valid SQL text and kept its precision, which is the only property that
     * matters. Asserting on the literal would lock in a formatting choice while testing nothing
     * about retrieval.
     */
    @Test
    void vectorsSurviveARoundTripThroughTheColumn() {
        seed();

        repository.writeVectors(List.of(new ChunkSearchRepository.ChunkVector(
                chunkId("passport fragment"), ScriptedModel.axis(0))));

        assertThat(repository.searchByVector(ScriptedModel.axis(0), Filter.all(), 10, 0.01))
                .extracting(Hit::text)
                .containsExactly("passport fragment");
    }

@Test
    void aVectorOfTheWrongWidthIsRefusedRatherThanTruncated() {
        seed();
        long id = chunkId("passport fragment");
        ChunkSearchRepository.ChunkVector tooNarrow =
                new ChunkSearchRepository.ChunkVector(id, List.of(1.0, 0.0, 0.0));

        assertThat(catchThrowable(() -> repository.writeVectors(List.of(tooNarrow))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("768");
    }

    private long chunkId(String text) {
        return jdbc.sql("SELECT id FROM chunks WHERE text = :t")
                .param("t", text)
                .query(Long.class)
                .single();
    }

    private void seed() {
        jdbc.sql("DELETE FROM chunks").update();
        jdbc.sql("DELETE FROM documents").update();

        for (String id : List.of("doc-passport", "doc-kettle", "doc-unrelated")) {
            jdbc.sql("""
                            INSERT INTO documents (id, filename, content_sha256, size_bytes, state)
                            VALUES (:id, :id, :hash, 100, 'indexed')
                            """)
                    .param("id", id)
                    .param("hash", "sha-" + id)
                    .update();
        }

        embed("doc-passport", 0, 1, PASSPORT_SECTION, "passport fragment", ScriptedModel.axis(0));
        embed("doc-kettle", 0, 1, KETTLE_SECTION, "kettle fragment", ScriptedModel.weighted(0, 0.6));
        embed("doc-unrelated", 0, 1, UNRELATED_SECTION, "unrelated fragment", ScriptedModel.axis(2));
    }

    private void embed(String documentId, int ordinal, int page, String section, String text, List<Double> vector) {
        long id = insertChunk(documentId, ordinal, page, section, text);
        repository.writeVectors(List.of(new ChunkSearchRepository.ChunkVector(id, vector)));
    }

    private long insertChunk(String documentId, int ordinal, int page, String section, String text) {
        return jdbc.sql("""
                        INSERT INTO chunks (document_id, ordinal, page, section, text, is_table)
                        VALUES (:documentId, :ordinal, :page, :section, :text, false)
                        RETURNING id
                        """)
                .param("documentId", documentId)
                .param("ordinal", ordinal)
                .param("page", page)
                .param("section", section)
                .param("text", text)
                .query(Long.class)
                .single();
    }
}