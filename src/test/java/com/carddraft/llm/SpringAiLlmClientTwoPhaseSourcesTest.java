package com.carddraft.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import com.carddraft.agents.ProductCard;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.repositories.ModelCallRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Two-phase sources generation, observed at the transport.
 *
 * <p>The second call happens only when the first draft's sources mapping is invalid (sentences
 * instead of references, names with no characteristic). A card that already cites correctly
 * costs one generation rather than two, and the difference is visible in the call records.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAiLlmClientTwoPhaseSourcesTest {

    private static final SupplierFacts FACTS = new SupplierFacts("Blender", Map.of("Power", "800 W"), List.of());

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

        // Free rates, zero cost for test
        LlmSettings settings = new LlmSettings(
                "main-model",
                "utility-model",
                3,
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(2),
                2,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);

        calls = new RecordingCallRepository();
        client = new SpringAiLlmClient(
                chatClientBuilder, new ObjectMapper(), settings, calls, new SimpleMeterRegistry());
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
    void whenFirstCallHasInvalidSourcesSecondCallUsesSourcesOnlyPrompt() {
        // Given: first response has sentences in sources instead of characteristic names
        String firstResponse = """
                {"title":"Blender 800","description":"A powerful blender.",
                 "characteristics":{"Power":"800 W","Capacity":"1.5 L"},
                 "benefits":["Quiet operation"],"missingFields":[],
                 "confidence":0.9,
                 "sources":{
                   "Power":"This blender has a power of 800 W",
                   "Capacity":"The capacity is 1.5 liters"
                 }}
                """;

        // Given: second response has valid sources with characteristic names as keys (full card)
        String secondResponse = """
                {"title":"Blender 800","description":"A powerful blender.",
                 "characteristics":{"Power":"800 W","Capacity":"1.5 L"},
                 "benefits":["Quiet operation"],"missingFields":[],
                 "confidence":0.9,
                 "sources":{
                   "Power":"C1",
                   "Capacity":"C2"
                 }}
                """;

        respondWith(firstResponse, secondResponse);

        // When: calling draftCardFromContext
        String contextText = "[C1] Power: 800 W\n[C2] Capacity: 1.5 L";
        ProductCard card = client.draftCardFromContext(contextText, List.of());

        // Then: the final card has facts from first call and sources from second call
        assertThat(card.title()).isEqualTo("Blender 800");
        assertThat(card.characteristics()).containsEntry("Power", "800 W");
        assertThat(card.characteristics()).containsEntry("Capacity", "1.5 L");
        assertThat(card.sources()).containsEntry("Power", "C1");
        assertThat(card.sources()).containsEntry("Capacity", "C2");

        // Then: two model calls were made
        verify(requestSpec, times(2)).user(prompts.capture());

        // Then: first call was the normal generator prompt
        String firstPrompt = prompts.getAllValues().get(0);
        assertThat(firstPrompt).contains("generator");
        assertThat(firstPrompt).contains("fragments below");

        // Then: second call was the repair prompt for sources field
        String secondPrompt = prompts.getAllValues().get(1);
        assertThat(secondPrompt).contains("correcting one field");
        assertThat(secondPrompt).contains("sources");
        assertThat(secondPrompt).contains("Regenerate only the sources mapping");
        // The prompt contains the current card with old sources - this is expected for repair
    }

    @Test
    void whenSourcesAreSentencesRatherThanReferencesTheyAreRepairedPointwise() {
        // Given: first response is valid but with sentence-based sources
        String firstResponse = """
                {"title":"Blender 800","description":"A powerful blender.",
                 "characteristics":{"Power":"800 W"},
                 "benefits":["Quiet operation"],"missingFields":[],
                 "confidence":0.9,
                 "sources":{"Power":"This blender has a power of 800 W"}}
                """;

        // Given: second response fixes the sources
        String secondResponse = """
                {"title":"Blender 800","description":"A powerful blender.",
                 "characteristics":{"Power":"800 W"},
                 "benefits":["Quiet operation"],"missingFields":[],
                 "confidence":0.9,
                 "sources":{"Power":"C1"}}
                """;

        respondWith(firstResponse, secondResponse);

        // When: calling draftCardFromContext
        String contextText = "[C1] Power: 800 W";
        ProductCard card = client.draftCardFromContext(contextText, List.of());

        // Then: two model calls were made (generation plus the pointwise sources repair)
        verify(requestSpec, times(2)).user(prompts.capture());

        // Then: the result has correct sources from second call
        assertThat(card.title()).isEqualTo("Blender 800");
        assertThat(card.sources()).containsEntry("Power", "C1");
    }

    @Test
    void whenSourcesAlreadyCiteCorrectlyNoSecondCallIsBilled() {
        // Given: a first response whose sources are already references
        String valid = """
                {"title":"Blender 800","description":"A powerful blender.",
                 "characteristics":{"Power":"800 W"},
                 "benefits":["Quiet operation"],"missingFields":[],
                 "confidence":0.9,
                 "sources":{"Power":"C1"}}
                """;
        respondWith(valid);

        // When: calling draftCardFromContext
        ProductCard card = client.draftCardFromContext("[C1] Power: 800 W", List.of());

        // Then: no pointwise repair was needed, so no second generation was billed
        verify(requestSpec, times(1)).user(anyString());
        assertThat(card.title()).isEqualTo("Blender 800");
        assertThat(card.sources()).containsEntry("Power", "C1");
    }

    /**
     * A sources repair that never converges still leaves a generatable card.
     *
     * <p>Sources validity is the workflow's jurisdiction — it holds the retained context, a
     * rework budget and a human to escalate to — while parseability and the card's substance
     * are the client's. Exploding here would short-circuit that graceful path: a card whose
     * facts are fine but whose mapping stayed imperfect must go on to citation verification,
     * not end the job in the client.
     */
    @Test
    void whenSourcesRepairNeverConvergesTheFirstDraftStillGoesOn() {
        // Given: valid facts with sentence sources, and a model that never fixes the mapping
        // while keeping everything else intact
        String stubborn = """
                {"title":"Blender 800","description":"A powerful blender.",
                 "characteristics":{"Power":"800 W"},
                 "benefits":["Quiet operation"],"missingFields":[],
                 "confidence":0.9,
                 "sources":{"Power":"This blender has a power of 800 W"}}
                """;
        respondWith(stubborn);

        // When: calling draftCardFromContext
        ProductCard card = client.draftCardFromContext("[C1] Power: 800 W", List.of());

        // Then: the first draft goes on with its facts, imperfect sources included
        assertThat(card.characteristics()).containsEntry("Power", "800 W");
        assertThat(card.sources()).containsEntry("Power", "This blender has a power of 800 W");

        // Then: one generation plus the repair's bounded budget, and no throw
        verify(requestSpec, times(4)).user(anyString());
    }

    private void respondWith(String... responses) {
        if (responses.length == 1) {
            given(callResponseSpec.chatResponse()).willReturn(chatResponse(responses[0]));
        } else {
            // For multiple responses, track call count and return appropriate response
            final int[] callCount = {0};
            given(callResponseSpec.chatResponse()).willAnswer(invocation -> {
                int count = callCount[0]++;
                return chatResponse(responses[Math.min(count, responses.length - 1)]);
            });
        }
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(content), null // No metadata needed for this test
                )));
    }
}
