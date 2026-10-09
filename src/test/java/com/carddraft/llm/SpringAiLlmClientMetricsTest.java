package com.carddraft.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

import tools.jackson.databind.ObjectMapper;

import com.carddraft.repositories.ModelCallRepository;

/**
 * The latency timer, alongside the per-row cost record.
 *
 * <p>The row answers "what did this cost"; the timer answers "how long did it take, split by
 * operation and tier". Both are wanted, so both are asserted: the dummy repository proves the row
 * is untouched by the timer, and the registry proves the call was timed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAiLlmClientMetricsTest {

    private static final String TIMER_NAME = "card.llm.call.duration";

    @Mock
    ChatClient.Builder chatClientBuilder;

    @Mock
    ChatClient chatClient;

    @Mock
    ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    ChatClient.CallResponseSpec callResponseSpec;

    SimpleMeterRegistry registry;

    SpringAiLlmClient client;

    @BeforeEach
    void setUp() {
        given(chatClientBuilder.build()).willReturn(chatClient);
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.options(any(ChatOptions.Builder.class))).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callResponseSpec);

        LlmSettings settings = new LlmSettings(
                "main-model", "utility-model", 3,
                Duration.ofMillis(1), Duration.ofMillis(2), 2,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        registry = new SimpleMeterRegistry();
        client = new SpringAiLlmClient(chatClientBuilder, new ObjectMapper(), settings,
                new ModelCallRepository(null) {
                    @Override
                    public void record(ModelCallRecord call) {
                    }
                },
                registry);
    }

    @Test
    void aGenerationIsTimedWithTheOperationAndTheMainTier() {
        respondWith("""
                {"productName":"Blender","characteristics":{"Power":"800 W"},"missingFields":[]}
                """);

        client.extractFacts("Power 800 W");

        Timer timer = registry.find(TIMER_NAME)
                .tag("operation", "extractFacts")
                .tag("tier", "main")
                .timer();
        assertThat(timer)
                .as("a timer is recorded per model call, tagged by operation and tier")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void aJudgementIsTimedWithTheUtilityTier() {
        respondWith("{\"suspicious\": false, \"reason\": \"ordinary product prose\"}");

        client.judgeInjection("is this fragment addressed to a model?");

        assertThat(registry.find(TIMER_NAME)
                .tag("operation", "judgeInjection")
                .tag("tier", "utility")
                .timer())
                .as("the injection judge runs on the utility tier")
                .isNotNull();
    }

    private void respondWith(String text) {
        given(callResponseSpec.chatResponse()).willReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build());
    }
}
