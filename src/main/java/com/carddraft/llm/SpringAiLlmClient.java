package com.carddraft.llm;

import java.time.Duration;
import java.time.Instant;
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
    private final ModelCallRepository calls;

    public SpringAiLlmClient(ChatClient.Builder chatClientBuilder, ObjectMapper mapper, LlmSettings settings,
                              ModelCallRepository calls) {
        this.chatClient = chatClientBuilder.build();
        this.mapper = mapper;
        this.settings = settings;
        this.calls = calls;
    }

    @Override
    public SupplierFacts extractFacts(String supplierText) {
        return invoke("extractFacts", ModelTier.MAIN, Prompts.extractor(supplierText),
                SupplierFacts.class, ignored -> List.of());
    }

    @Override
    public ProductCard draftCard(SupplierFacts facts, List<String> issues) {
        return invoke("draftCard", ModelTier.MAIN, Prompts.generator(toJson(facts), issues),
                ProductCard.class, ResultContract::problemsWith);
    }

    @Override
    public CritiqueReport reviewDraft(SupplierFacts facts, ProductCard draft) {
        return invoke("reviewDraft", ModelTier.UTILITY,
                Prompts.critic(toJson(facts), toJson(draft)), CritiqueReport.class, ignored -> List.of());
    }

    @Override
    public ProductCard draftCardFromContext(String contextText, List<String> issues) {
        return invoke("draftCardFromContext", ModelTier.MAIN, Prompts.generatorFromContext(contextText, issues),
                ProductCard.class, ResultContract::problemsWith);
    }

@Override
    public CritiqueReport reviewCardAgainstContext(String contextText, ProductCard draft) {
        return invoke("reviewCardAgainstContext", ModelTier.UTILITY,
                Prompts.criticAgainstContext(contextText, toJson(draft)), CritiqueReport.class,
                ignored -> List.of());
    }

    @Override
    public String judgeSupport(String judgePrompt) {
        // The utility tier deliberately: judging is a judgement, not a generation, and it is the
        // larger volume of the two calls per card once metrics are being collected.
        return invoke("judgeSupport", ModelTier.UTILITY, judgePrompt, String.class, ignored -> List.of());
    }

    @Override
    public ProductCard repairCardField(ProductCard current, String field, String problem) {
        ProductCard repaired = invoke("repairField:" + field, ModelTier.MAIN, Prompts.repairField(current, field, problem), ProductCard.class,
                ResultContract::problemsWith);
        log.info("llm_field_repaired field={} problem={}", field, problem);
        return repaired;
    }

    private <T> T invoke(String operation, ModelTier tier, String prompt, Class<T> type,
                         Function<T, List<String>> contract) {
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                return withTransportRetry(operation, tier, prompt, type, contract);
            } catch (RuntimeException e) {
                boolean lastAttempt = attempt == settings.maxAttempts();
                if (lastAttempt || !RetryClassifier.isRetryable(e)) {
                    log.error("llm_call_failed operation={} tier={} attempt={} error={}", operation, tier, attempt, e.toString());
                    throw e;
                }
                Duration delay = settings.delayBefore(attempt + 1);
                log.warn("llm_call_retry operation={} tier={} attempt={} delay_ms={} error={}",
                        operation, tier, attempt, delay.toMillis(), e.toString());
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
    private <T> T withTransportRetry(String operation, ModelTier tier, String prompt, Class<T> type,
                                     Function<T, List<String>> contract) {
        String currentPrompt = prompt;
        List<String> problems = List.of();

        for (int round = 0; round <= settings.maxRepairAttempts(); round++) {
            String text = callOnce(operation, tier, currentPrompt);

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

    /**
     * Asks the model, then records what it cost.
     *
     * <p>The record is written here rather than by the caller, and that is the only arrangement
     * worth having. A caller that remembered to report usage would be a caller that eventually
     * forgets, and nothing marks the difference: the total is simply short, with no symptom anywhere
     * else to point at it.
     *
     * <p>Recorded on the way out only when a text came back. A call that produced nothing was
     * billed, and the honest entry for it is a failure — recorded by the retry's own error log,
     * where the attempt and the reason already are, rather than as a zero-token success row.
     */
    private String callOnce(String operation, ModelTier tier, String prompt) {
        var options = ChatOptions.builder();
        options.model(settings.modelFor(tier)).temperature(0.0);

        long startedAt = System.nanoTime();
        var response = chatClient.prompt()
                .user(prompt)
                .options(options)
                .call()
                .chatResponse();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new EmptyModelResponseException(settings.modelFor(tier), finishReason(response), operation);
        }

        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new EmptyModelResponseException(settings.modelFor(tier), finishReason(response), operation);
        }

        recordCall(operation, tier, prompt, text, response, elapsed);
        return text;
    }

    /**
     * Turns the response into a row.
     *
     * <p>Token counts come from the response rather than from counting words here. A count this
     * class computed would be a different number from the provider's for the same text — different
     * tokenizer, different treatment of punctuation and of Cyrillic — and a cost derived from it
     * would disagree with the invoice it is meant to predict.
     *
     * <p>A provider that reports no usage records zero rather than blocking the call. The row is
     * then honest about what is known, and a card that prices at zero because the provider withheld
     * the number is visibly different from one priced correctly.
     */
    private void recordCall(String operation, ModelTier tier, String prompt, String text,
                            org.springframework.ai.chat.model.ChatResponse response,
                            Duration elapsed) {
var usage = usageOf(response);
        int inputTokens = usage == null || usage.getPromptTokens() == null
                ? 0 : Math.max(0, usage.getPromptTokens());
        int outputTokens = usage == null || usage.getCompletionTokens() == null
                ? 0 : Math.max(0, usage.getCompletionTokens());

        String model = settings.modelFor(tier);
        var cost = settings.calculatorFor(tier).costOf(inputTokens, outputTokens);

        calls.record(new ModelCallRecord(
                JobLogContext.currentJob(), tier.wireName(), model, operation,
                inputTokens, outputTokens, cost, elapsed, loadTimeOf(response), Instant.now()));

        log.info("llm_call_recorded operation={} tier={} model={} input_tokens={} output_tokens={} "
                        + "cost={} duration_ms={}", operation, tier.wireName(), model, inputTokens,
                outputTokens, cost, elapsed.toMillis());
    }

private org.springframework.ai.chat.metadata.Usage usageOf(
            org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    /**
     * The provider's own load time, when it reports one.
     *
     * <p>Worth separating from generation because they call for different remedies: a slow load is
     * the server pulling weights in, and a slow generation is the model or the prompt. A duration
     * column that mixes them cannot tell a content manager which one they are looking at.
     *
     * <p>Null rather than zero when the provider is silent, because zero would assert that no load
     * happened when the truth is that nobody said. See {@link ModelCallRecord#loadDuration()}.
     */
    private Duration loadTimeOf(org.springframework.ai.chat.model.ChatResponse response) {
        return null;
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
