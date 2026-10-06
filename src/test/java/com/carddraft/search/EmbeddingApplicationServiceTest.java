package com.carddraft.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.carddraft.embeddings.EmbeddingModel;
import com.carddraft.repositories.ChunkSearchRepository;

/**
 * What the two counts say, which is the only thing anybody reads them for.
 *
 * <p>Written because the number was wrong once and looked right. The sweep added a configured batch
 * size per round instead of the chunks it had actually embedded, and returned before adding the last
 * round at all — so a run that embedded nine chunks reported zero, and a log line saying so is
 * exactly what a person decides whether to trust. Nothing in the code was broken; the report was.
 */
class EmbeddingApplicationServiceTest {

    private static final int DIMENSION = 4;

    @Test
    void theSweepCountsTheChunksItWroteNotTheBatchesItTook() {
        // Nine chunks through a batch size of sixteen: one batch, and the count has to be nine.
        FakeChunks chunks = new FakeChunks(Map.of("doc-1", 9));
        EmbeddingApplicationService service = serviceOver(chunks, 16);

        assertThat(service.embedAll(100))
                .as("nine chunks, one batch, and a sweep that reported zero would leave a person "
                        + "concluding it had done nothing")
                .isEqualTo(9);
    }

    @Test
    void aSweepSpanningSeveralBatchesCountsEveryChunk() {
        FakeChunks chunks = new FakeChunks(Map.of("doc-1", 7, "doc-2", 7));
        EmbeddingApplicationService service = serviceOver(chunks, 5);

        assertThat(service.embedAll(100))
                .as("fourteen chunks at five to a batch is three batches; counting batches would say three")
                .isEqualTo(14);
    }

    @Test
    void indexingOneDocumentTouchesOnlyThatDocument() {
        FakeChunks chunks = new FakeChunks(Map.of("doc-1", 4, "doc-2", 6));
        EmbeddingApplicationService service = serviceOver(chunks, 16);

        assertThat(service.embedDocument("doc-1", 100))
                .as("an upload must embed what it just parsed, not the whole corpus around it")
                .isEqualTo(4);
        assertThat(chunks.remaining()).isEqualTo(6);
    }

    @Test
    void aSweepWithNothingToDoReportsZeroRatherThanFailing() {
        FakeChunks chunks = new FakeChunks(Map.of());
        EmbeddingApplicationService service = serviceOver(chunks, 16);

        assertThat(service.embedAll(100)).isZero();
        assertThat(service.embedDocument("doc-1", 100)).isZero();
    }

    private EmbeddingApplicationService serviceOver(FakeChunks chunks, int batchSize) {
        return new EmbeddingApplicationService(new FixedWidthModel(), chunks,
                new IndexingSettings(batchSize, 1000));
    }

    /** A model that answers whatever width it is asked for, without a server. */
    private static final class FixedWidthModel implements EmbeddingModel {

        @Override
        public List<Double> embedQuery(String query) {
            return zeros();
        }

        @Override
        public List<Double> embedDocument(String text, String title) {
            return zeros();
        }

        @Override
        public List<List<Double>> embedDocuments(List<Document> documents) {
            List<List<Double>> vectors = new ArrayList<>();
            documents.forEach(ignored -> vectors.add(zeros()));
            return vectors;
        }

        @Override
        public int dimension() {
            return DIMENSION;
        }

        private static List<Double> zeros() {
            return java.util.Collections.nCopies(DIMENSION, 0.0);
        }
    }

    /**
     * A repository that answers from a count instead of a database.
     *
     * <p>Subclasses the real one rather than replacing it, so the queries the service issues are the
     * queries under test; only the answers are replaced. Everything it overrides is a pure function of
     * how many chunks each document still owes a vector for.
     */
    private static final class FakeChunks extends ChunkSearchRepository {

        private final Map<String, Integer> outstanding;
        private final Map<String, Integer> embedded = new LinkedHashMap<>();

        private FakeChunks(Map<String, Integer> outstanding) {
            super(null, null);
            this.outstanding = new LinkedHashMap<>(outstanding);
        }

        @Override
        public List<ChunkToEmbed> chunksWithoutVectors(int limit) {
            return take(null, limit);
        }

        @Override
        public List<ChunkToEmbed> chunksWithoutVectors(String documentId, int limit) {
            return take(documentId, limit);
        }

        @Override
        public int countWithoutVectors() {
            return remaining();
        }

        @Override
        public int countWithoutVectors(String documentId) {
            return outstanding.getOrDefault(documentId, 0);
        }

        @Override
        public void writeVectors(List<ChunkVector> vectors) {
            vectors.forEach(vector -> embedded.merge(
                    documentOf(vector.chunkId()), 1, Integer::sum));
        }

        private List<ChunkToEmbed> take(String documentId, int limit) {
            List<ChunkToEmbed> batch = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : outstanding.entrySet()) {
                if (documentId != null && !documentId.equals(entry.getKey())) {
                    continue;
                }
                while (batch.size() < limit && entry.getValue() > 0) {
                    long id = batch.size() + entry.getValue();
                    batch.add(new ChunkToEmbed(id, entry.getKey(), "S", "fragment " + id));
                    entry.setValue(entry.getValue() - 1);
                }
            }
            return batch;
        }

        private String documentOf(long chunkId) {
            return outstanding.keySet().stream().findFirst().orElseThrow();
        }

        private int remaining() {
            return outstanding.values().stream().mapToInt(Integer::intValue).sum();
        }

    }
}