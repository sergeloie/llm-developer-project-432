package com.carddraft.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.ai.chat.prompt.ChatOptions;

import tools.jackson.databind.ObjectMapper;

import com.carddraft.agents.SupplierFacts;
import com.carddraft.repositories.ModelCallRepository;

/**
 * The temperature reaches the model.
 *
 * <p>Asserted at the request spec rather than on the settings record, because the record binding
 * is a different test and the defect this guards against is the client quietly ignoring it: the
 * value could bind correctly and still be replaced by a literal here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAiLlmClientTemperatureTest {

    private static final SupplierFacts FACTS =
            new SupplierFacts("Blender", Map.of("Power", "800 W"), List.of());

    @Mock
    ChatClient.Builder chatClientBuilder;

    @Mock
    ChatClient chatClient;

    @Mock
    ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    ChatClient.CallResponseSpec callResponseSpec;

    @Captor
    ArgumentCaptor<ChatOptions.Builder> options;

    @BeforeEach
    void setUp() {
        given(chatClientBuilder.build()).willReturn(chatClient);
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.options(any(ChatOptions.Builder.class))).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callResponseSpec);

        given(callResponseSpec.chatResponse()).willReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("""
                        {"title":"Blender 800","description":"A blender.",
                         "characteristics":{"Power":"800 W"},"benefits":["Quiet"],
                         "missingFields":[],"confidence":0.9,"sources":{}}
                        """))))
                .build());
    }

    @Test
    void sendsTheConfiguredTemperatureToTheModel() {
        var client = clientWithTemperature(new BigDecimal("0.7"));

        client.draftCard(FACTS, List.of());

        verify(requestSpec, atLeastOnce()).options(options.capture());
        assertThat(options.getAllValues())
                .as("every request carries the configured temperature")
                .allSatisfy(builder -> assertThat(builder.build().getTemperature()).isEqualTo(0.7));
    }

    private SpringAiLlmClient clientWithTemperature(BigDecimal temperature) {
        LlmSettings settings = new LlmSettings(
                "main-model", "utility-model", 3,
                Duration.ofMillis(1), Duration.ofMillis(2), 2,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, temperature);

        return new SpringAiLlmClient(chatClientBuilder, new ObjectMapper(), settings,
                new ModelCallRepository(null) {
                    @Override
                    public void record(ModelCallRecord call) {
                    }
                });
    }
}
