package com.carddraft.metrics;

import java.util.List;

import org.springframework.stereotype.Component;

import com.carddraft.agents.ProductCard;
import com.carddraft.context.ContextChunk;
import com.carddraft.llm.LlmClient;

import tools.jackson.databind.ObjectMapper;

/**
 * Asks a model whether each claim is supported by the fragment it cites.
 *
 * <p>Asks one question per claim rather than "is this card any good", because a single verdict is
 * unusable in a report: it cannot say which claim failed, and a change to a prompt then produces a
 * number that moves without saying what moved with it.
 *
 * <p>Run against the fragments the card actually cited rather than against the whole corpus. The
 * question is whether the cited source supports the claim, and handing the judge everything would
 * answer a different one — it would find support somewhere else and report a card as sourced when
 * its own citation does not say so. That distinction is what citation precision measures
 * separately.
 *
 * <p>A judge is a model, so it is an estimate. It is recorded as data and labelled as an estimate
 * everywhere it appears, and the harness never averages it in as though it were measured like the
 * other two numbers.
 */
@Component
public class SupportJudge {

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public SupportJudge(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public SupportJudgement judge(ProductCard card, List<ContextChunk> citedFragments) {
        String answer = llm.judgeSupport(prompt(card, citedFragments));
        return parseAnswer(answer);
    }

    String prompt(ProductCard card, List<ContextChunk> citedFragments) {
        StringBuilder rendered = new StringBuilder();
        for (ContextChunk chunk : citedFragments) {
            rendered.append(chunk.render()).append("\n\n");
        }

        return """
                You are checking a product card against the source fragments it cites.

                For each characteristic, find the fragment the card names for it in sources, then \
                decide whether that fragment actually states that value. A value that is correct \
                but attributed to a fragment that does not contain it is NOT supported: the card \
                would send a reader to the wrong place. Say so even when the value is right.

                Values may differ in formatting. "800 W", "800 w" and "800 W." are the same value, \
                and so are "1.7 л" and "1,7 л". Judge the value, not the spelling.

                Reply with a JSON object and nothing else:
                  supported   object   characteristic name to true or false
                  reasoning   object   characteristic name to one short sentence saying why

                Judge every characteristic the card declares a source for. No prose, no markdown.

                FRAGMENTS:
                %s

                CARD:
                %s
                """.formatted(rendered.toString().strip(), describe(card));
    }

    private String describe(ProductCard card) {
        try {
            return mapper.writeValueAsString(card);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("could not render the card for the judge", e);
        }
    }

    /**
     * Reads the verdict, defaulting to "unsupported" for anything the judge omitted.
     *
     * <p>Defaulting to supported would be the flattering choice and the wrong one: a judge that
     * silently skipped a claim has not checked it, and counting that as a pass would make a broken
     * judge look like a good card.
     */
    SupportJudgement parseAnswer(String answer) {
        var root = mapper.readTree(answer);
        var supported = root.path("supported");
        var reasoning = root.path("reasoning");

        java.util.Map<String, Boolean> verdicts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> reasons = new java.util.LinkedHashMap<>();
        supported.properties().forEach(entry -> verdicts.put(entry.getKey(), entry.getValue().asBoolean(false)));
        reasoning.properties().forEach(entry -> reasons.put(entry.getKey(), entry.getValue().asString("")));

        return new SupportJudgement(verdicts, reasons);
    }
}