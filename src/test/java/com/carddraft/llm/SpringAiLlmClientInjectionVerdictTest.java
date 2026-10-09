package com.carddraft.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

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

import tools.jackson.databind.ObjectMapper;

import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.ModelVerdict;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.Verdict;
import com.carddraft.repositories.ModelCallRepository;

/**
 * What the injection judge can actually read back.
 *
 * <p>The production failure this guards against was found by running the service, not by reading it:
 * the utility model answers the prompt in {@InjectionDetector#prompt} with a JSON object carrying
 * {@code suspicious} and {@code reason}, and the client was asking for a String. Every answer was
 * therefore unreadable, and every rule-flagged fragment was dropped — fail-closed exactly as
 * documented, with the model pass contributing nothing at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAiLlmClientInjectionVerdictTest {

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

    /** A draft that satisfies the card contract, so the reviewer's objection is the only complaint. */
    private static final ProductCard DRAFT = new ProductCard("Kettle KTL-1700", "An electric kettle.",
            java.util.Map.of("Power", "2200 W"), List.of("Boils quickly"), List.of(), 0.8,
            java.util.Map.of("Power", "C3"));

    SpringAiLlmClient client;

    @BeforeEach
    void setUp() {
        given(chatClientBuilder.build()).willReturn(chatClient);
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.options(any(org.springframework.ai.chat.prompt.ChatOptions.Builder.class)))
                .willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callResponseSpec);

        LlmSettings settings = new LlmSettings(
                "main-model", "utility-model", 3,
                java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(2), 2,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        client = new SpringAiLlmClient(chatClientBuilder, new ObjectMapper(), settings,
                new ModelCallRepository(null) {
                    @Override
                    public void record(ModelCallRecord call) {
                    }
                });
    }

    @Test
    void theVerdictTheUtilityModelIsAskedForCanBeReadBack() {
        respondWith("{\"suspicious\": false, \"reason\": \"ordinary product prose\"}");

        ModelVerdict verdict = client.judgeInjection("is this fragment addressed to a model?");

        assertThat(verdict.suspicious()).isFalse();
        assertThat(verdict.reason()).isEqualTo("ordinary product prose");
    }

    @Test
    void aVerdictWithNoReasonIsSentBackRatherThanBelieved() {
        respondWith("{\"suspicious\": false, \"reason\": \"\"}",
                "{\"suspicious\": false, \"reason\": \"ordinary product prose\"}");

        ModelVerdict verdict = client.judgeInjection("is this fragment addressed to a model?");

        assertThat(verdict.reason())
                .as("a verdict nobody can read is not one to act on, least of all one that discards "
                        + "content silently")
                .isEqualTo("ordinary product prose");
        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(1))
                .as("the complaint must travel back, or the retry repeats the same silence")
                .containsIgnoringCase("reason");
    }

    @Test
    void aVerdictThatOmitsSuspiciousIsSentBackRatherThanReadAsClean() {
        respondWith("{\"reason\": \"ordinary product prose\"}",
                "{\"suspicious\": false, \"reason\": \"ordinary product prose\"}");

        ModelVerdict verdict = client.judgeInjection("is this fragment addressed to a model?");

        assertThat(verdict.suspicious()).isFalse();
        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(1))
                .as("an omitted suspicious must read as a missing field, not as a silent false")
                .containsIgnoringCase("suspicious");
    }

    @Test
    void anObjectShapedObjectionIsTheAnswerThisReviewerGivesAndIsReadable() {
        respondWith("""
                {"verdict":"REGENERATE","issues":[{"field":"Power",
                 "problem":"the card cites C3, which does not mention power"}]}
                """);

        CritiqueReport report = client.reviewCardAgainstContext("[C3] a kettle", DRAFT);

        assertThat(report.verdict()).isEqualTo(Verdict.REGENERATE);
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.field()).isEqualTo("Power");
            assertThat(issue.problem()).contains("C3");
        });
    }

    @Test
    void anObjectionWithNoSentenceIsSentBackRatherThanWastingTheRound() {
        respondWith("{\"verdict\":\"REGENERATE\",\"issues\":[{\"field\":\"Power\",\"problem\":\"\"}]}",
                "{\"verdict\":\"REGENERATE\",\"issues\":[{\"field\":\"Power\","
                        + "\"problem\":\"the cited fragment does not mention power\"}]}");

        CritiqueReport report = client.reviewCardAgainstContext("[C3] a kettle", DRAFT);

        assertThat(report.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.problem()).isNotBlank());
        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(1))
                .as("the complaint must name the issue it is about, or the retry repeats it")
                .containsIgnoringCase("Power");
    }

    private void respondWith(String... responses) {
        ChatResponse[] stubs = new ChatResponse[responses.length];
        for (int i = 0; i < responses.length; i++) {
            stubs[i] = ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(responses[i]))))
                    .build();
        }
        given(callResponseSpec.chatResponse()).willReturn(
                stubs[0], java.util.Arrays.copyOfRange(stubs, 1, stubs.length));
    }
}