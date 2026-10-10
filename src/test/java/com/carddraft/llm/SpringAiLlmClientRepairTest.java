package com.carddraft.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.math.BigDecimal;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.ObjectMapper;

import com.carddraft.agents.ProductCard;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.repositories.ModelCallRepository;

/**
 * The repair loop, observed at the transport.
 *
 * <p>The model is the ChatClient, so these tests watch what actually goes out over the wire: not
 * "was the loop entered" but "was the complaint in the prompt". A repair that does not tell the
 * model what it did wrong is just a second chance at the same mistake.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAiLlmClientRepairTest {

    private static final SupplierFacts FACTS =
            new SupplierFacts("Blender", Map.of("Power", "800 W"), List.of());

    @Mock
    ChatClient.Builder chatClientBuilder;

    @Mock
    ChatClient chatClient;

    @Mock
    org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    org.springframework.ai.chat.client.ChatClient.CallResponseSpec callResponseSpec;

    @Captor
    ArgumentCaptor<String> prompts;

    SpringAiLlmClient client;
    RecordingCallRepository calls;

    @BeforeEach
    void setUp() {
        given(chatClientBuilder.build()).willReturn(chatClient);
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.options(any(org.springframework.ai.chat.prompt.ChatOptions.Builder.class)))
                .willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callResponseSpec);

        // Free rates, so these tests assert on repair behaviour rather than on a cost figure; the
        // arithmetic is covered at non-zero rates in CostCalculatorTest, where a zero price would
        // exercise none of it.
        LlmSettings settings = new LlmSettings(
                "main-model", "utility-model", 3,
                java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(2), 2,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        // A recording repository rather than a mock, so "the client wrote a row per call" can be
        // asserted by counting rows instead of by restating the expectation.
        calls = new RecordingCallRepository();
        client = new SpringAiLlmClient(chatClientBuilder, new ObjectMapper(), settings, calls,
                new SimpleMeterRegistry());
    }

    /** Collects what the client recorded, with no expectations of its own. */
    static final class RecordingCallRepository extends ModelCallRepository {

        final List<ModelCallRecord> recorded = new ArrayList<>();

        RecordingCallRepository() {
            super(null);
        }

        @Override
        public void write(ModelCallRecord call) {
            recorded.add(call);
        }
    }

    @Test
    void anOverlongTitleIsSentBackWithTheReasonAndAcceptedOnASecondAttempt() {
        String tooLong = """
                {"title":"%s","description":"A blender.","characteristics":{"Power":"800 W"},
                 "benefits":["Quiet"],"missingFields":[],"confidence":0.9,"sources":{}}
                """.formatted("B".repeat(70));
        String acceptable = """
                {"title":"Blender 800","description":"A blender.","characteristics":{"Power":"800 W"},
                 "benefits":["Quiet"],"missingFields":[],"confidence":0.9,"sources":{}}
                """;
        respondWith(tooLong, acceptable);

        ProductCard card = client.draftCard(FACTS, List.of());

        assertThat(card.title()).isEqualTo("Blender 800");
        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(1))
                .as("the complaint must travel back, or the retry repeats the same mistake")
                .contains("at most 60")
                .contains("characters");
    }

    @Test
    void anUnparseableResponseIsSentBackRatherThanThrownAway() {
        String wrapped = """
                Here is your card:
                ```json
                {"title":"Blender 800","description":"A blender.",
                 "characteristics":{"Power":"800 W"},"benefits":["Quiet"],
                 "missingFields":[],"confidence":0.9,"sources":{}}
                ```
                """;
        given(callResponseSpec.chatResponse()).willReturn(chatResponse(wrapped));

        ProductCard card = client.draftCard(FACTS, List.of());

        assertThat(card.title()).isEqualTo("Blender 800");
    }

    @Test
    void repairIsBoundedSoAModelThatCannotComplyDoesNotSpin() {
        String alwaysTooLong = """
                {"title":"%s","description":"A blender.","characteristics":{},
                 "benefits":["Quiet"],"missingFields":[],"confidence":0.9,"sources":{}}
                """.formatted("B".repeat(70));
        given(callResponseSpec.chatResponse()).willReturn(chatResponse(alwaysTooLong));

        assertThatThrownBy(() -> client.draftCard(FACTS, List.of()))
                .isInstanceOf(ModelResponseFormatException.class)
                .hasMessageContaining("at most 60");

        // One generation plus the repair's own bounded budget: the pointwise title repair is a
        // generation like any other, so it carries the same repair allowance rather than one
        // unguarded call.
        verify(requestSpec, times(4)).user(anyString());
    }

    @Test
    void aFieldIsRepairedWithoutTheRestOfTheCardChanging() {
        String original = """
                {"title":"%s","description":"An original description that must survive untouched.",
                 "characteristics":{"Power":"800 W"},"benefits":["Quiet","Compact"],
                 "missingFields":["Colour"],"confidence":0.42,
                 "sources":{"Power":"C7"}}
                """.formatted("T".repeat(75));
        String repaired = """
                {"title":"Blender 800","description":"An original description that must survive untouched.",
                 "characteristics":{"Power":"800 W"},"benefits":["Quiet","Compact"],
                 "missingFields":["Colour"],"confidence":0.42,
                 "sources":{"Power":"C7"}}
                """;
        given(callResponseSpec.chatResponse()).willReturn(chatResponse(original), chatResponse(repaired));

        ProductCard broken = new ProductCard("T".repeat(75),
                "An original description that must survive untouched.",
                Map.of("Power", "800 W"), List.of("Quiet", "Compact"),
                List.of("Colour"), 0.42, Map.of("Power", "C7"));

        ProductCard fixed = client.repairCardField(broken, "title",
                ResultContract.problemsWith(broken).get(0));

        assertThat(fixed.title()).isEqualTo("Blender 800");
        assertThat(fixed.description()).isEqualTo(broken.description());
        assertThat(fixed.characteristics()).isEqualTo(broken.characteristics());
        assertThat(fixed.benefits()).isEqualTo(broken.benefits());
        assertThat(fixed.missingFields()).isEqualTo(broken.missingFields());
        assertThat(fixed.confidence()).isEqualTo(broken.confidence());
        assertThat(fixed.sources()).isEqualTo(broken.sources());

        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(0))
                .contains("Change the field \"title\" and nothing else")
                .contains("at most 60");
    }

    @Test
    void aModelThatReturnsNothingIsReportedAsSuchAndNotRepaired() {
        given(callResponseSpec.chatResponse()).willReturn(chatResponse("   "));

        assertThatThrownBy(() -> client.draftCard(FACTS, List.of()))
                .isInstanceOf(EmptyModelResponseException.class)
                .hasMessageContaining("returned no content");

        verify(requestSpec, times(1)).user(anyString());
    }

    private void respondWith(String... responses) {
        ChatResponse[] stubs = new ChatResponse[responses.length];
        for (int i = 0; i < responses.length; i++) {
            stubs[i] = chatResponse(responses[i]);
        }
        given(callResponseSpec.chatResponse()).willReturn(stubs[0], java.util.Arrays.copyOfRange(stubs, 1, stubs.length));
    }

    private ChatResponse chatResponse(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }
}
