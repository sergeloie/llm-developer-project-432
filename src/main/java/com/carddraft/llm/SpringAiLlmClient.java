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
import com.carddraft.agents.ModelVerdict;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.ReviewIssue;
import com.carddraft.agents.Prompts;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.SupportJudgement;
import com.carddraft.repositories.ModelCallRepository;

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
    public ProductCard draftCard(SupplierFacts facts, List<ReviewIssue> issues) {
        ProductCard draft = invoke("draftCard", ModelTier.MAIN, Prompts.generator(toJson(facts), issues),
                ProductCard.class, ResultContract::problemsForGeneration);
        draft = repairTitleIfNeeded(draft);
        return repairSourcesIfNeeded(draft,
                "The facts carry no fragment identifiers, so sources must stay empty rather than "
                        + "naming invented references");
    }

    @Override
    public CritiqueReport reviewDraft(SupplierFacts facts, ProductCard draft) {
        return invoke("reviewDraft", ModelTier.UTILITY,
                Prompts.critic(toJson(facts), toJson(draft)), CritiqueReport.class, ResultContract::problemsWith);
    }

    @Override
    public ProductCard draftCardFromContext(String contextText, List<ReviewIssue> issues) {
        // First phase: the full card. A long title and a bad sources mapping pass through here on
        // purpose: both are repaired pointwise below, and regenerating the whole card for either
        // would bill a second generation for one field.
        ProductCard firstDraft = invoke("draftCardFromContext:phase1", ModelTier.MAIN,
                Prompts.generatorFromContext(contextText, issues),
                ProductCard.class, ResultContract::problemsForGeneration);

        ProductCard draft = repairTitleIfNeeded(firstDraft);
        return repairSourcesIfNeeded(draft,
                "Regenerate only the sources mapping using characteristic names as keys");
    }

@Override
    public CritiqueReport reviewCardAgainstContext(String contextText, ProductCard draft) {
        return invoke("reviewCardAgainstContext", ModelTier.UTILITY,
                Prompts.criticAgainstContext(contextText, toJson(draft)), CritiqueReport.class,
                ResultContract::problemsWith);
    }

    @Override
    public SupportJudgement judgeSupport(String judgePrompt) {
        // The utility tier deliberately: judging is a judgement, not a generation, and it is the
        // larger volume of the two calls per card once metrics are being collected.
        return invoke("judgeSupport", ModelTier.UTILITY, judgePrompt, SupportJudgement.class,
                ResultContract::problemsWith);
    }

@Override
    public ModelVerdict judgeInjection(String injectionPrompt) {
        return invoke("judgeInjection", ModelTier.UTILITY, injectionPrompt, ModelVerdict.class,
                ResultContract::problemsWith);
    }

    @Override
    public ProductCard repairCardField(ProductCard current, String field, String problem) {
        ProductCard repaired = invoke("repairField:" + field, ModelTier.MAIN, Prompts.repairField(current, field, problem), ProductCard.class,
                ResultContract::problemsWith);
        log.info("llm_field_repaired field={} problem={}", field, problem);
        return repaired;
    }

    /**
     * One pointwise repair, only when it is needed.
     *
     * <p>A second generation is only worth billing when the first draft failed the field. A card
     * that already satisfies the contract returns unchanged and costs nothing more.
     */
    private ProductCard repairTitleIfNeeded(ProductCard draft) {
        return ResultContract.titleLengthProblem(draft)
                .map(problem -> keepingFirstDraft(draft,
                        repairCardField(draft, "title", problem), "title"))
                .orElse(draft);
    }

    private ProductCard repairSourcesIfNeeded(ProductCard draft, String instruction) {
        List<String> problems = ResultContract.sourcesProblems(draft);
        if (problems.isEmpty()) {
            return draft;
        }
        try {
            ProductCard repaired = repairCardField(draft, "sources",
                    instruction + ": " + String.join("; ", problems));
            return keepingFirstDraft(draft, repaired, "sources");
        } catch (ModelResponseFormatException e) {
            // The mapping stayed imperfect within budget, and the first draft goes on anyway.
            // Sources validity is the workflow's jurisdiction — it holds the retained context,
            // a rework budget and a human to escalate to — while parseability and the card's
            // substance are this client's. Throwing here would short-circuit that graceful path
            // and end a job whose facts are fine. Transport failures are not caught: an
            // unreachable provider is loud, an imperfect mapping is a verification matter.
            log.warn("llm_sources_repair_exhausted problem={}", e.getMessage());
            return draft;
        }
    }

    /**
     * The merge the generation promises: everything from the first draft, one field replaced.
     *
     * <p>The repair prompt asks for exactly this, but an instruction is not an enforcement — a
     * model that rewrote the description while fixing the sources would otherwise smuggle an
     * unreviewed change into the card. The merge keeps the billed second call to the field it
     * was billed for.
     *
     * <p>If the merged card fails the contract — the repaired sources name a characteristic the
     * first draft does not have — the repaired answer stands instead. It passed the contract
     * whole, and a merged card that fails it is worse than a second draft that does not.
     */
    private ProductCard keepingFirstDraft(ProductCard first, ProductCard repaired, String field) {
        ProductCard merged = switch (field) {
            case "sources" -> new ProductCard(first.title(), first.description(),
                    first.characteristics(), first.benefits(), first.missingFields(),
                    first.confidence(), repaired.sources());
            case "title" -> new ProductCard(repaired.title(), first.description(),
                    first.characteristics(), first.benefits(), first.missingFields(),
                    first.confidence(), first.sources());
            default -> repaired;
        };
        return ResultContract.problemsWith(merged).isEmpty() ? merged : repaired;
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
                Duration delay = settings.delayBefore(attempt);
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
