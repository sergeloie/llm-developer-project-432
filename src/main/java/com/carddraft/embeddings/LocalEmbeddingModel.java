package com.carddraft.embeddings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

/**
 * Embeddings from the local OpenAI-compatible server.
 *
 * <p>The model lives in the server's process, not ours. That is why the task template is applied
 * here rather than by the model: a server-side embedding endpoint embeds exactly the string it is
 * given, so a template this code did not apply would simply be absent — and the retrieval would
 * degrade without a single error.
 *
 * <p>Applying it here also makes the choice visible and configurable, which is what gives the
 * calibration test something to bite on: the same code can be asked to encode without the
 * templates, and the difference in scores observed rather than assumed.
 */
@Component
public class LocalEmbeddingModel implements EmbeddingModel {

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final EmbeddingSettings settings;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalEmbeddingModel(RestClient.Builder builder, ObjectMapper mapper, EmbeddingSettings settings) {
        this(builder.baseUrl(settings.baseUrl()).requestFactory(timeouts(settings)).build(),
                mapper, settings);
    }

    /**
     * Assembled client rather than a builder.
     *
     * <p>Package-visible for the tests: the mock server binds to a builder, and a builder the
     * production constructor already stamped a timeout factory onto no longer carries the mock.
     * Passing the assembled client keeps the interception working without the production path
     * giving up its ceiling.
     */
    LocalEmbeddingModel(RestClient restClient, ObjectMapper mapper, EmbeddingSettings settings) {
        this.mapper = mapper;
        this.settings = settings;
        this.restClient = restClient;
    }

    /**
     * The configured ceiling, applied rather than carried. A batch embed of a parsed document
     * is the call most likely to meet a cold server, and without a bound it holds an indexing
     * slot for as long as the server stays silent.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory timeouts(
            EmbeddingSettings settings) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.timeout());
        factory.setReadTimeout(settings.timeout());
        return factory;
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
        String body = mapper.writeValueAsString(Map.of("model", settings.model(), "input", inputs));

        String response = restClient.post()
                .uri("/v1/embeddings")
                // Stated explicitly because the body is a pre-serialised String: RestClient will
                // not infer a content type from a String, and an OpenAI-compatible endpoint that
                // receives text/plain is within its rights to refuse it.
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        var data = mapper.readTree(response).path("data");
        List<List<Double>> vectors = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            vectors.add(new ArrayList<>());
        }
        for (var element : data) {
            int index = element.path("index").asInt(-1);
            if (index < 0 || index >= vectors.size()) {
                throw new IllegalStateException(
                        "the embedding response carried index " + index + " for a request of "
                                + inputs.size() + " inputs");
            }
            List<Double> vector = new ArrayList<>();
            for (var value : element.path("embedding")) {
                vector.add(value.asDouble());
            }
            vectors.set(index, vector);
        }

        for (int i = 0; i < vectors.size(); i++) {
            if (vectors.get(i).isEmpty()) {
                throw new IllegalStateException("the embedding response omitted index " + i);
            }
            int actual = vectors.get(i).size();
            if (actual != settings.dimension()) {
                throw new EmbeddingDimensionMismatchException(settings.model(), settings.dimension(), actual);
            }
        }
        return vectors;
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
