package com.carddraft.llm;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import com.carddraft.agents.CardDraft;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.Prompts;
import com.carddraft.agents.SupplierFacts;

import tools.jackson.databind.ObjectMapper;

/**
 * The provider boundary.
 *
 * <p>Owns the timeout, the retry policy, recovery of JSON from a dirty response, and the tier
 * each role runs on. Everything above it works with records and never learns that a provider
 * exists.
 *
 * <p>Uses Jackson 3, which is what Spring Boot 4 configures and what {@code spring.jackson.*}
 * applies to. Spring AI carries Jackson 2 for its own internals; the two coexist, and this class
 * deliberately does not straddle them.
 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final LlmSettings settings;

    public SpringAiLlmClient(ChatClient.Builder chatClientBuilder, ObjectMapper mapper, LlmSettings settings) {
        this.chatClient = chatClientBuilder.build();
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public SupplierFacts extractFacts(String supplierText) {
        return invoke("extractFacts", settings.mainModel(), Prompts.extractor(supplierText), SupplierFacts.class);
    }

    @Override
    public CardDraft draftCard(SupplierFacts facts, List<String> issues) {
        return invoke("draftCard", settings.mainModel(), Prompts.generator(toJson(facts), issues), CardDraft.class);
    }

    @Override
    public CritiqueReport reviewDraft(SupplierFacts facts, CardDraft draft) {
        return invoke("reviewDraft", settings.utilityModel(),
                Prompts.critic(toJson(facts), toJson(draft)), CritiqueReport.class);
    }

    private <T> T invoke(String operation, String model, String prompt, Class<T> resultType) {
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                return callOnce(operation, model, prompt, resultType);
            } catch (RuntimeException e) {
                boolean lastAttempt = attempt == settings.maxAttempts();
                if (lastAttempt || !RetryClassifier.isRetryable(e)) {
                    log.error("llm_call_failed operation={} model={} attempt={} error={}",
                            operation, model, attempt, e.toString());
                    throw e;
                }
                Duration delay = settings.delayBefore(attempt + 1);
                log.warn("llm_call_retry operation={} model={} attempt={} delay_ms={} error={}",
                        operation, model, attempt, delay.toMillis(), e.toString());
                sleep(delay);
            }
        }
        throw new IllegalStateException("unreachable: retry loop always returns or throws");
    }

    private <T> T callOnce(String operation, String model, String prompt, Class<T> resultType) {
        var options = ChatOptions.builder();
        options.model(model).temperature(0.0);

        var response = chatClient.prompt()
                .user(prompt)
                .options(options)
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new EmptyModelResponseException(model, finishReason(response), operation);
        }

        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new EmptyModelResponseException(model, finishReason(response), operation);
        }

        String json = ModelResponses.jsonObject(text, operation);
        try {
            return mapper.readValue(json, resultType);
        } catch (tools.jackson.core.JacksonException e) {
            throw new ModelResponseFormatException(
                    operation + ": response did not match " + resultType.getSimpleName() + ": " + e.getMessage());
        }
    }

    private String finishReason(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return null;
        }
        var metadata = response.getResult().getMetadata();
        return metadata instanceof org.springframework.ai.chat.metadata.ChatGenerationMetadata chat
                ? chat.getFinishReason()
                : null;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry a model call", e);
        }
    }

    static double jitter() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
