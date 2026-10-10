package com.carddraft.embeddings;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The embedding adapter, against the Spring AI client seam.
 *
 * <p>What matters here is the text that leaves this process. The model server embeds exactly what it
 * is handed, so a prefix this code failed to apply would not be reported by anything — the vectors
 * would still be 768 numbers wide and retrieval would quietly get worse. Asserting on the request
 * the adapter builds is the only place that failure is visible.
 *
 * <p>The seam is Spring AI's own {@link org.springframework.ai.embedding.EmbeddingModel} rather
 * than the HTTP layer, because the wire call is now the framework's: the fake captures the typed
 * request and returns a typed response.
 */
class LocalEmbeddingModelTest {

    private static EmbeddingSettings settings() {
        return new EmbeddingSettings(
                "http://127.0.0.1:1234",
                "test-embedding-model",
                3,
                "task: search result | query: ",
                "title: ",
                java.time.Duration.ofSeconds(5));
    }

    private static LocalEmbeddingModel model(RecordingWire wire) {
        return new LocalEmbeddingModel(wire, settings());
    }

    /** The Spring AI client, recorded and answered by hand. */
    private static final class RecordingWire implements org.springframework.ai.embedding.EmbeddingModel {

        private final List<List<String>> batches = new ArrayList<>();
        private EmbeddingResponse response = new EmbeddingResponse(List.of());
        private RuntimeException failure;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            batches.add(request.getInstructions());
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            throw new UnsupportedOperationException("the adapter always embeds a batch");
        }

        RecordingWire respondingWith(EmbeddingResponse value) {
            this.response = value;
            return this;
        }

        RecordingWire failingWith(RuntimeException error) {
            this.failure = error;
            return this;
        }

        List<List<String>> batches() {
            return batches;
        }
    }

    private static EmbeddingResponse response(int index, double... values) {
        float[] vector = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            vector[i] = (float) values[i];
        }
        return new EmbeddingResponse(List.of(new Embedding(vector, index)));
    }

    private static EmbeddingResponse shuffled() {
        List<Embedding> embeddings = List.of(
                new Embedding(new float[] {0.3f, 0.3f, 0.3f}, 2),
                new Embedding(new float[] {0.1f, 0.1f, 0.1f}, 0),
                new Embedding(new float[] {0.2f, 0.2f, 0.2f}, 1));
        return new EmbeddingResponse(embeddings);
    }

    @Test
    void aQueryIsEncodedWithTheQueryPrefix() {
        RecordingWire wire = new RecordingWire().respondingWith(response(0, 0.1, 0.2, 0.3));

        assertThat(model(wire).embedQuery("what is the boiling point")).containsExactly(0.1, 0.2, 0.3);
        assertThat(wire.batches()).containsExactly(List.of("task: search result | query: what is the boiling point"));
    }

    /**
     * The document side carries the section in the title slot.
     *
     * <p>Two things ride on this: the model asks for a title, and the same value is what a citation
     * points at. One piece of information, two jobs, and no extra bookkeeping to satisfy the model.
     */
    @Test
    void aChunkIsEncodedWithItsSectionInTheTitleSlot() {
        RecordingWire wire = new RecordingWire().respondingWith(response(0, 0.1, 0.2, 0.3));

        model(wire).embedDocument("Water boils at 100 C", "Boiling point");

        assertThat(wire.batches()).containsExactly(List.of("title: Boiling point | text: Water boils at 100 C"));
    }

    /**
     * A chunk with no section gets the literal the model card prescribes.
     *
     * <p>Not an empty string: the template has a fixed shape, and a blank title slot is a different
     * input to the model than the word "none".
     */
    @Test
    void aChunkWithoutASectionIsToldSoExplicitly() {
        RecordingWire wire = new RecordingWire().respondingWith(response(0, 0.1, 0.2, 0.3));

        model(wire).embedDocument("orphan text", null);

        assertThat(wire.batches()).containsExactly(List.of("title: none | text: orphan text"));
    }

    /**
     * The response's own index decides placement, not arrival order.
     *
     * <p>A server is free to return vectors in any order. Reading them positionally would silently
     * attach one chunk's meaning to another, and the symptom would be a wrong citation rather than
     * an error — so the shuffled response below is the case worth a test.
     */
    @Test
    void vectorsArePlacedByTheIndexTheServerGivesThem() {
        RecordingWire wire = new RecordingWire().respondingWith(shuffled());

        List<List<Double>> vectors = model(wire)
                .embedDocuments(List.of(
                        new EmbeddingModel.Document("first", null),
                        new EmbeddingModel.Document("second", null),
                        new EmbeddingModel.Document("third", null)));

        assertThat(vectors).extracting(v -> v.get(0)).containsExactly(0.1, 0.2, 0.3);
    }

    @Test
    void anEmptyBatchMakesNoRequest() {
        RecordingWire wire = new RecordingWire();

        assertThat(model(wire).embedDocuments(List.of())).isEmpty();
        assertThat(wire.batches()).isEmpty();
    }

    /**
     * A width the column cannot hold must stop here.
     *
     * <p>Left to reach the database it becomes a constraint violation on an UPDATE, far from the
     * model that caused it, by which point the cause is guesswork.
     */
    @Test
    void aVectorOfTheWrongWidthIsRejectedWithTheModelNamed() {
        RecordingWire wire = new RecordingWire().respondingWith(response(0, 0.1, 0.2));

        assertThatThrownBy(() -> model(wire).embedQuery("anything"))
                .isInstanceOf(LocalEmbeddingModel.EmbeddingDimensionMismatchException.class)
                .hasMessageContaining("test-embedding-model")
                .hasMessageContaining("2-dimension")
                .hasMessageContaining("vector column is 3");
    }

    /** A truncated response would otherwise leave a chunk silently unembedded. */
    @Test
    void aResponseMissingAnIndexIsRejected() {
        RecordingWire wire = new RecordingWire().respondingWith(response(0, 0.1, 0.2, 0.3));

        assertThatThrownBy(() -> model(wire)
                        .embedDocuments(List.of(
                                new EmbeddingModel.Document("a", null), new EmbeddingModel.Document("b", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("omitted index 1");
    }

    /** An empty vector in place of an input must not pass as an embedded chunk. */
    @Test
    void anEmptyVectorIsRejected() {
        RecordingWire wire =
                new RecordingWire().respondingWith(new EmbeddingResponse(List.of(new Embedding(new float[0], 0))));

        assertThatThrownBy(() -> model(wire).embedQuery("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty vector at index 0");
    }

    /** An index outside the request means the response belongs to another call. */
    @Test
    void aResponseWithAnImpossibleIndexIsRejected() {
        RecordingWire wire = new RecordingWire().respondingWith(response(7, 0.1, 0.2, 0.3));

        assertThatThrownBy(() -> model(wire).embedQuery("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index 7");
    }

    /**
     * The server's own failure is left to propagate.
     *
     * <p>Not retried here. Whether a model server error is worth another attempt is the caller's
     * decision, and an embedding that silently failed leaves a chunk out of the index — which reads
     * as "nothing matched" rather than "the model was down".
     */
    @Test
    void aServerFailureIsSurfacedRatherThanSwallowed() {
        RuntimeException failure = new IllegalStateException("the model server refused the call");
        RecordingWire wire = new RecordingWire().failingWith(failure);

        assertThatThrownBy(() -> model(wire).embedQuery("anything")).isSameAs(failure);
    }

    @Test
    void theConfiguredDimensionIsWhatTheModelAdvertises() {
        assertThat(model(new RecordingWire()).dimension()).isEqualTo(3);
    }
}
