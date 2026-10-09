package com.carddraft.embeddings;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The embedding client, against a recorded request.
 *
 * <p>What matters here is the text that leaves this process. The model server embeds exactly what it
 * is handed, so a prefix this code failed to apply would not be reported by anything — the vectors
 * would still be 768 numbers wide and retrieval would quietly get worse. Asserting on the request
 * body is the only place that failure is visible.
 */
class LocalEmbeddingModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EmbeddingSettings settings() {
        return new EmbeddingSettings(
                "http://127.0.0.1:1234",
                "test-embedding-model",
                3,
                "task: search result | query: ",
                "title: ",
                java.time.Duration.ofSeconds(5));
    }

    private record Harness(LocalEmbeddingModel model, MockRestServiceServer server) {
    }

    private static Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1:1234");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // The assembled client, not the builder: the production constructor stamps its timeout
        // factory onto the builder, which would replace the mock's interception.
        return new Harness(new LocalEmbeddingModel(builder.build(), MAPPER, settings()), server);
    }

    private static String response(String... indicesAndVectors) {
        StringBuilder json = new StringBuilder("{\"data\":[");
        for (int i = 0; i < indicesAndVectors.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"index\":").append(indicesAndVectors[i]).append(",\"embedding\":[0.1,0.2,0.3]}");
        }
        return json.append("]}").toString();
    }

    @Test
    void aQueryIsEncodedWithTheQueryPrefix() {
        Harness h = harness();
        h.server().expect(requestTo("http://127.0.0.1:1234/v1/embeddings"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("test-embedding-model"))
                .andExpect(jsonPath("$.input[0]").value("task: search result | query: what is the boiling point"))
                .andRespond(withSuccess(response("0"), MediaType.APPLICATION_JSON));

        assertThat(h.model().embedQuery("what is the boiling point"))
                .containsExactly(0.1, 0.2, 0.3);
        h.server().verify();
    }

    /**
     * The document side carries the section in the title slot.
     *
     * <p>Two things ride on this: the model asks for a title, and the same value is what a citation
     * points at. One piece of information, two jobs, and no extra bookkeeping to satisfy the model.
     */
    @Test
    void aChunkIsEncodedWithItsSectionInTheTitleSlot() {
        Harness h = harness();
        h.server().expect(requestTo("http://127.0.0.1:1234/v1/embeddings"))
                .andExpect(jsonPath("$.input[0]")
                        .value("title: Boiling point | text: Water boils at 100 C"))
                .andRespond(withSuccess(response("0"), MediaType.APPLICATION_JSON));

        h.model().embedDocument("Water boils at 100 C", "Boiling point");
        h.server().verify();
    }

    /**
     * A chunk with no section gets the literal the model card prescribes.
     *
     * <p>Not an empty string: the template has a fixed shape, and a blank title slot is a different
     * input to the model than the word "none".
     */
    @Test
    void aChunkWithoutASectionIsToldSoExplicitly() {
        Harness h = harness();
        h.server().expect(requestTo("http://127.0.0.1:1234/v1/embeddings"))
                .andExpect(jsonPath("$.input[0]").value("title: none | text: orphan text"))
                .andRespond(withSuccess(response("0"), MediaType.APPLICATION_JSON));

        h.model().embedDocument("orphan text", null);
        h.server().verify();
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
        Harness h = harness();
        h.server().expect(requestTo("http://127.0.0.1:1234/v1/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(3))
                .andRespond(withSuccess("""
                        {"data":[
                          {"index":2,"embedding":[0.3,0.3,0.3]},
                          {"index":0,"embedding":[0.1,0.1,0.1]},
                          {"index":1,"embedding":[0.2,0.2,0.2]}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<List<Double>> vectors = h.model().embedDocuments(List.of(
                new EmbeddingModel.Document("first", null),
                new EmbeddingModel.Document("second", null),
                new EmbeddingModel.Document("third", null)));

        assertThat(vectors).extracting(v -> v.get(0))
                .containsExactly(0.1, 0.2, 0.3);
        h.server().verify();
    }

    @Test
    void anEmptyBatchMakesNoRequest() {
        Harness h = harness();

        assertThat(h.model().embedDocuments(List.of())).isEmpty();
        h.server().verify();
    }

    /**
     * A width the column cannot hold must stop here.
     *
     * <p>Left to reach the database it becomes a constraint violation on an UPDATE, far from the
     * model that caused it, by which point the cause is guesswork.
     */
    @Test
    void aVectorOfTheWrongWidthIsRejectedWithTheModelNamed() {
        Harness h = harness();
        h.server().expect(requestTo("http://127.0.0.1:1234/v1/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.model().embedQuery("anything"))
                .isInstanceOf(LocalEmbeddingModel.EmbeddingDimensionMismatchException.class)
                .hasMessageContaining("test-embedding-model")
                .hasMessageContaining("2-dimension")
                .hasMessageContaining("vector column is 3");
        h.server().verify();
    }

    /** A truncated response would otherwise leave a chunk silently unembedded. */
    @Test
    void aResponseMissingAnIndexIsRejected() {
        Harness h = harness();
        h.server().expect(requestTo("http://127.0.0.1:1234/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.model().embedDocuments(List.of(
                new EmbeddingModel.Document("a", null),
                new EmbeddingModel.Document("b", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("omitted index 1");
        h.server().verify();
    }

    /** An index outside the request means the response belongs to another call. */
    @Test
    void aResponseWithAnImpossibleIndexIsRejected() {
        Harness h = harness();
        h.server().expect(requestTo("http://127.0.0.1:1234/v1/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":7,\"embedding\":[0.1,0.2,0.3]}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.model().embedQuery("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index 7");
        h.server().verify();
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
        Harness h = harness();
        h.server().expect(requestTo("http://127.0.0.1:1234/v1/embeddings"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> h.model().embedQuery("anything"))
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        h.server().verify();
    }

    @Test
    void theConfiguredDimensionIsWhatTheModelAdvertises() {
        assertThat(harness().model().dimension()).isEqualTo(3);
    }


}