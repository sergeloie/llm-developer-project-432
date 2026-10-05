package com.carddraft.trust;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.carddraft.context.ContextChunk;

/**
 * Screens fragments before they reach a model, and filters the card before a person sees it.
 *
 * <p>Ordering is the whole design and it is not negotiable. Fragments are screened for injected
 * instructions and masked for personal data <em>before</em> they are assembled, which means before
 * the prompt is built, before it is sent, and before any of it can be logged. Screening afterwards
 * would leave a window in which a masked value had already been written to a log line, and an
 * attacker who can get a value into the logs has it regardless of what the card says.
 *
 * <p>The stored original is left alone. Masking happens on the way into a context and on the way
 * out of a card; the chunk in the database keeps its text so that word search still finds
 * "7712345671" for a person who is allowed to see it. Masking at rest would make the corpus
 * useless for the investigation it exists to support, and would mean a masked value could never be
 * confirmed as one.
 *
 * <p>Fails closed. When the utility model is unreachable, a fragment the rules flagged is treated
 * as suspicious. The alternative — treat an unanswerable question as a clean answer — turns a
 * temporary outage into a window where anything goes.
 */
@Service
public class TrustService {

    private final PiiDetector pii;
    private final InjectionDetector injection;
    private final InjectionModel model;

    public TrustService(PiiDetector pii, InjectionDetector injection, InjectionModel model) {
        this.pii = pii;
        this.injection = injection;
        this.model = model;
    }

    /**
     * What the trust layer did to one job's fragments.
     *
     * @param excluded chunks dropped for carrying injected instructions
     * @param masked   chunks whose personal data was replaced
     * @param escalated whether so many chunks looked suspicious that the document needs a person
     */
    public record Screening(List<String> excluded, List<String> masked, boolean escalated,
                            String reason) {

        public static Screening clean() {
            return new Screening(List.of(), List.of(), false, null);
        }
    }

    /**
     * Screens and masks, returning fragments that are safe to put in a prompt.
     *
     * @param fragments the retained context, already numbered
     * @return the survivors with personal data masked, in the same order and under the same labels
     */
    public Screened screen(List<ContextChunk> fragments, int suspiciousBudget) {
        List<ContextChunk> survivors = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        List<String> masked = new ArrayList<>();

        for (ContextChunk chunk : fragments) {
            InjectionDetector.RuleVerdict verdict = injection.inspect(chunk.text());
            boolean suspicious = verdict.suspicious();

            if (suspicious) {
                // Only what the rules flagged is put to the model, and its silence counts as
                // suspicious: an unanswerable question is not a clean answer.
                suspicious = !model.clears(chunk, verdict);
            }

            if (suspicious) {
                excluded.add(chunk.reference());
                continue;
            }

            Finding.Report personal = pii.scan(chunk.text());
            if (!personal.isClean()) {
                masked.add(chunk.reference());
                survivors.add(new ContextChunk(chunk.reference(), chunk.chunkId(),
                        chunk.documentId(), chunk.page(), chunk.section(),
                        personal.maskedText()));
            } else {
                survivors.add(chunk);
            }
        }

        boolean escalated = excluded.size() > suspiciousBudget;
        String reason = escalated
                ? excluded.size() + " fragments carried injected instructions, above the budget of "
                        + suspiciousBudget
                : null;

        return new Screened(survivors, excluded, masked, escalated, reason);
    }

    /**
     * One job's screened context.
     *
     * @param maskedText the text to send, already masked. Held rather than re-rendered so the prompt
     *                   and the screening decision cannot disagree about what was masked.
     */
    public record Screened(List<ContextChunk> chunks, List<String> excluded, List<String> masked,
                           boolean escalated, String reason) {

        public String render() {
            StringBuilder rendered = new StringBuilder();
            for (ContextChunk chunk : chunks) {
                rendered.append(chunk.render()).append("\n\n");
            }
            return rendered.toString().strip();
        }
    }

    /**
     * Filters the finished card.
     *
     * <p>Runs on the output as well as the input, because the two are different failures and only
     * the second is caught by screening. A model can invent a phone number that appears in no
     * fragment, or quote an instruction that survived screening; neither is visible upstream.
     */
    public Finding.Report filterOutput(String text) {
        Finding.Report personal = pii.scan(text);

        InjectionDetector.RuleVerdict leaked = injection.inspect(text);
        if (!leaked.isClean()) {
            List<Finding> findings = new ArrayList<>(personal.findings());
            leaked.rules().forEach(rule -> findings.add(
                    new Finding(Finding.Kind.INJECTION, "[masked]", rule, 0)));
            return new Finding.Report(findings, personal.maskedText());
        }
        return personal;
    }
}