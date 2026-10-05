package com.carddraft.trust;

import org.springframework.stereotype.Component;

import com.carddraft.llm.LlmClient;
import com.carddraft.context.ContextChunk;

/**
 * Asks the utility model the narrow question about a fragment the rules already flagged.
 *
 * <p>Named separately from {@link LlmClient} so the dependency is visible and so the failure
 * behaviour can be stated in one place: every call goes through the ordinary client, which means it
 * inherits the same timeout, the same retry classification and the same cost record. A detector with
 * its own HTTP path would have its own timeout — which is the kind of thing that hangs during an
 * incident, exactly when it matters — and its own accounting, which is the kind of thing that makes
 * the cost of security invisible.
 *
 * <p>Cannot answer and answers "no". The caller treats a failure as suspicious rather than as
 * clean, so this class never has to decide what a failed judgement means; it only has to be honest
 * about whether it got one.
 */
@Component
public class InjectionModel {

    private final InjectionDetector detector;
    private final LlmClient llm;

    public InjectionModel(InjectionDetector detector, LlmClient llm) {
        this.detector = detector;
        this.llm = llm;
    }

    /**
     * Whether the model clears this fragment.
     *
     * @return false when the fragment is suspicious <em>or</em> when the model could not be asked.
     *         Both readings lead to the fragment being dropped, which is the fail-closed direction.
     */
    public boolean clears(ContextChunk fragment, InjectionDetector.RuleVerdict rules) {
        try {
            String answer = llm.judgeInjection(detector.prompt(fragment.text()));
            var parsed = new tools.jackson.databind.ObjectMapper().readTree(answer);
            boolean suspicious = parsed.path("suspicious").asBoolean(true);
            return !suspicious;
        } catch (RuntimeException e) {
            // Logged at warn, not error, and not retried here: the call already used the client's
            // own retry policy, and a third attempt would mean a security decision costing three
            // generations.
            org.slf4j.LoggerFactory.getLogger(InjectionModel.class)
                    .warn("injection_judge_unavailable reference={} rules={} error={}",
                            fragment.reference(), rules.rules(), e.toString());
            return false;
        }
    }
}