package com.carddraft.llm;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.Prompts;
import com.carddraft.agents.SupplierFacts;

/**
 * The provider boundary.
 *
 * <p>Owns the timeout, the transport retry policy, the contract, recovery of JSON from a dirty
 * response, and the tier each role runs on. Everything above it works with records and never
 * learns that a provider exists.
 *
 * <p>Two loops, deliberately separate. The transport loop retries what can change on its own — a
 * rate limit, a dropped connection. The repair loop handles content that came back unusable, and
 * it sends the specific complaint back rather than the bare prompt. Merging them would mean a
 * malformed answer is retried identically instead of being corrected, which is the expensive kind
 * of repetition.
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
        return invoke("extractFacts", settings.mainModel(), Prompts.extractor(supplierText),
                SupplierFacts.class, ignored -> List.of());
    }

    @Override
    public ProductCard draftCard(SupplierFacts facts, List<String> issues) {
        return invoke("draftCard", settings.mainModel(), Prompts.generator(toJson(facts), issues),
                ProductCard.class, ResultContract::problemsWith);
    }

    @Override
    public CritiqueReport reviewDraft(SupplierFacts facts, ProductCard draft) {
        return invoke("reviewDraft", settings.utilityModel(),
                Prompts.critic(toJson(facts), toJson(draft)), CritiqueReport.class, ignored -> List.of());
    }

    @Override
    public ProductCard repairCardField(ProductCard current, String field, String problem) {
        ProductCard repaired = invoke("repairField:" + field, settings.mainModel(),
                Prompts.repairField(current, field, problem), ProductCard.class,
                ResultContract::problemsWith);
        log.info("llm_field_repaired field={} problem={}", field, problem);
        return repaired;
    }

    private <T> T invoke(String operation, String model, String prompt, Class<T> type,
                         Function<T, List<String>> contract) {
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                return withTransportRetry(operation, model, prompt, type, contract);
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

    /**
     * Asks, checks, and on failure sends the complaint back.
     *
     * <p>A response that cannot be read is not thrown away. It comes back with the reason
     * attached, because a model that wrapped the JSON in a fence or prefixed it with a sentence
     * is one instruction away from being right, and regenerating from scratch costs a generation
     * to fix a formatting slip.
     */
    private <T> T withTransportRetry(String operation, String model, String prompt, Class<T> type,
                                     Function<T, List<String>> contract) {
        String currentPrompt = prompt;
        List<String> problems = List.of();

        for (int round = 0; round <= settings.maxRepairAttempts(); round++) {
            String text = callOnce(operation, model, currentPrompt);

            try {
                T parsed = mapper.readValue(ModelResponses.jsonObject(text, operation), type);
                problems = contract.apply(parsed);
                if (problems.isEmpty()) {
                    if (round > 0) {
                        log.info("llm_contract_repaired operation={} rounds={}", operation, round);
                    }
                    return parsed;
                }
            } catch (ModelResponseFormatException | tools.jackson.core.JacksonException e) {
                problems = List.of(e.getMessage());
            }

            if (round == settings.maxRepairAttempts()) {
                break;
            }
            log.info("llm_contract_rejected operation={} round={} problems={}", operation, round, problems);
            currentPrompt = prompt + "\n\nYour previous answer was rejected:\n"
                    + String.join("\n", problems.stream().map(p -> "- " + p).toList())
                    + "\nReturn a corrected JSON object matching the schema, and nothing else.";
        }

        throw new ModelResponseFormatException(operation + ": still invalid after "
                + settings.maxRepairAttempts() + " repair attempts. Problems: " + String.join("; ", problems));
    }

    private String callOnce(String operation, String model, String prompt) {
        var options = ChatOptions.builder();
        options.model(model).temperature(0.0);

        var response = chatClient.prompt()
                .user(prompt)
                .options(options)
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new EmptyModelResponseException(model, finishReason(response), operation);
        }

        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new EmptyModelResponseException(model, finishReason(response), operation);
        }
        return text;
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
}
