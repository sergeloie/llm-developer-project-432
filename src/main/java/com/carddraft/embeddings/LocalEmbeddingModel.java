package com.carddraft.embeddings;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;

/**
 * The project's embedding adapter, over Spring AI's OpenAI-compatible client.
 *
 * <p>This class is not a pass-through, and it must not be reduced to one. The local server embeds
 * exactly the string it is handed, so the task template is applied here: a query is asked for and a
 * document is offered, with different prefixes. A prefix this code failed to apply would not error
 * — the vectors stay valid and retrieval quietly degrades — which is why the query and document
 * entry points stay separate and stay ours (ADR-0002).
 *
 * <p>The same argument applies to the checks below. A response of the wrong width or one that
 * omits an input is refused here rather than stored, because a silently unembedded chunk is only
 * discovered by a search that returns the wrong thing.
 *
 * <p>The wire call itself is Spring AI's typed client. Request assembly and response validation
 * are ours; the HTTP body and JSON tree are not.
 */
@Component
public class LocalEmbeddingModel implements EmbeddingModel {

    private final org.springframework.ai.embedding.EmbeddingModel wire;
    private final EmbeddingSettings settings;

    public LocalEmbeddingModel(org.springframework.ai.embedding.EmbeddingModel wire, EmbeddingSettings settings) {
        this.wire = wire;
        this.settings = settings;
    }

    @Override
    public List<Double> embedQuery(String query) {
        return embedBatch(List.of(settings.queryPrefix() + query)).get(0);
    }

    @Override
    public List<Double> embedDocument(String text, String title) {
        return embedBatch(List.of(documentSide(text, title))).get(0);
    }

    @Override
    public List<List<Double>> embedDocuments(List<Document> documents) {
        return embedBatch(documents.stream().map(d -> documentSide(d.text(), d.title())).toList());
    }

    @Override
    public int dimension() {
        return settings.dimension();
    }

    /**
     * The document side of the template: a title slot and a text slot.
     *
     * <p>The title is the chunk's section. The model asks for one, and we happen to have it — the
     * same value a citation points at, so no extra bookkeeping is introduced to satisfy the model.
     */
    private String documentSide(String text, String title) {
        String heading = title == null || title.isBlank() ? "none" : title;
        return settings.documentPrefix() + heading + " | text: " + text;
    }

    /**
     * Sends one batch and returns vectors in the order they were sent.
     *
     * <p>Ordered by the response's own index rather than by position, because a server is free to
     * return them in any order and a swapped pair silently attaches the wrong meaning to two
     * chunks — a failure with no symptom until someone reads a citation.
     */
    private List<List<Double>> embedBatch(List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        EmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(settings.model())
                .timeout(settings.timeout())
                .build();
        EmbeddingResponse response = wire.call(new EmbeddingRequest(inputs, options));

        List<List<Double>> vectors = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            vectors.add(null);
        }
        for (Embedding embedding : response.getResults()) {
            Integer index = embedding.getIndex();
            if (index == null || index < 0 || index >= vectors.size()) {
                throw new IllegalStateException("the embedding response carried index " + index
                        + " for a request of " + inputs.size() + " inputs");
            }
            float[] output = embedding.getOutput();
            vectors.set(index, output == null ? List.of() : toVector(output));
        }

        for (int i = 0; i < vectors.size(); i++) {
            List<Double> vector = vectors.get(i);
            if (vector == null) {
                throw new IllegalStateException("the embedding response omitted index " + i);
            }
            if (vector.isEmpty()) {
                throw new IllegalStateException("the embedding response carried an empty vector at index " + i);
            }
            int actual = vector.size();
            if (actual != settings.dimension()) {
                throw new EmbeddingDimensionMismatchException(settings.model(), settings.dimension(), actual);
            }
        }
        return vectors;
    }

    /**
     * The wire is float32; this interface speaks {@code Double}. Carrying the shortest decimal that
     * round-trips to the same float keeps the vector free of the binary widening noise a plain
     * cast would leave, so a value that arrived as {@code 0.1f} does not become
     * {@code 0.10000000149011612}.
     */
    private static List<Double> toVector(float[] output) {
        List<Double> vector = new ArrayList<>(output.length);
        for (float value : output) {
            vector.add(Double.parseDouble(Float.toString(value)));
        }
        return vector;
    }

    /** Raised when the server returns a width the database column cannot hold. */
    public static class EmbeddingDimensionMismatchException extends RuntimeException {

        public EmbeddingDimensionMismatchException(String model, int expected, int actual) {
            super("model '" + model + "' returned " + actual + "-dimension vectors but the vector "
                    + "column is " + expected + ". Change card.embedding.dimension and migrate, or "
                    + "point at a different model.");
        }
    }
}
