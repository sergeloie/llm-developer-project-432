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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import tools.jackson.databind.ObjectMapper;

import com.carddraft.agents.SupportJudgement;
import com.carddraft.repositories.ModelCallRepository;

/**
 * What the support judge can actually read back.
 *
 * <p>The failure this guards against was found by running the harness, not by reading it, and it is
 * the same one {@link SpringAiLlmClientInjectionVerdictTest} records for the injection judge.
 * {@code SupportJudge.prompt} asks for a JSON object carrying {@code supported} and
 * {@code reasoning}, the utility model answers exactly that, and the client was asking for a String.
 * Every judgement was therefore unreadable — and unlike the injection judge, nothing above this call
 * caught it, so one unreadable answer ended the metrics run and the report was never written.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAiLlmClientSupportVerdictTest {

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
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        client = new SpringAiLlmClient(chatClientBuilder, new ObjectMapper(), settings,
                new ModelCallRepository(null) {
                    @Override
                    public void record(ModelCallRecord call) {
                    }
                },
                new SimpleMeterRegistry());
    }

    @Test
    void theVerdictTheUtilityModelIsAskedForCanBeReadBack() {
        respondWith("{\"supported\":{\"Power\":true},"
                + "\"reasoning\":{\"Power\":\"the fragment states 800 W\"}}");

        SupportJudgement judgement = client.judgeSupport("is the claim supported by the fragment it cites?");

        assertThat(judgement.supported()).containsEntry("Power", true);
        assertThat(judgement.reasoning()).containsEntry("Power", "the fragment states 800 W");
        assertThat(judgement.measured())
                .as("a judge that answered is measured, whatever it said")
                .isTrue();
    }

    @Test
    void anUnsupportedClaimWithNoSentenceIsSentBackRatherThanBelieved() {
        respondWith("{\"supported\":{\"Power\":false},\"reasoning\":{}}",
                "{\"supported\":{\"Power\":false},"
                        + "\"reasoning\":{\"Power\":\"C1 does not mention power at all\"}}");

        SupportJudgement judgement = client.judgeSupport("is the claim supported by the fragment it cites?");

        assertThat(judgement.unsupported()).containsExactly("Power");
        assertThat(judgement.reasoning().get("Power"))
                .as("a rejected claim is what the report lists by name, so it has to be readable")
                .isEqualTo("C1 does not mention power at all");
        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(1))
                .as("the complaint must travel back, or the retry repeats the same silence")
                .containsIgnoringCase("reasoning")
                .contains("Power");
    }

    /**
     * A claim the judge accepted needs no justification, and asking for one would double the judge's
     * output on the majority of claims that pass.
     */
    @Test
    void anAcceptedClaimNeedsNoSentence() {
        respondWith("{\"supported\":{\"Power\":true},\"reasoning\":{}}");

        SupportJudgement judgement = client.judgeSupport("is the claim supported by the fragment it cites?");

        assertThat(judgement.supported()).containsEntry("Power", true);
        verify(requestSpec, times(1)).user(anyString());
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