package com.carddraft.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that every claim's citation names something the model was actually shown.
 *
 * <p>Two conditions, and the second is the one that matters.
 *
 * <p>The chunk exists: a label pointing at no chunk at all is the obvious failure and the easy one.
 * The chunk was in the submitted context: the failure that actually happens. Models produce plausible
 * labels — C1, C2, C7 — and a chunk that genuinely exists in the corpus but was never sent to the
 * model looks identical to a real citation unless the context is checked. A model that was shown
 * five fragments and cites a sixth real one has still invented its provenance, and the card reads to
 * a reviewer as well-sourced because the label resolves.
 *
 * <p>That is why {@link AssembledContext} is retained rather than reconstructed, and why this takes
 * it as an argument. Verification against a freshly-run search would compare the model against a
 * different set of fragments and wave through exactly the cards it exists to catch.
 *
 * <p>Pure, with no database behind it, because the second condition subsumes the first: a label in
 * the context necessarily names a chunk that exists. Existence is still checked as a defence against
 * a corrupted retention record, and reported separately so the two are distinguishable in a log.
 */
@org.springframework.stereotype.Component
public class CitationVerifier {

    /** The outcome for one claim's citation. */
    public enum Status {
        /** Named a fragment from the submitted context. */
        SUPPORTED,
        /** Named a fragment that exists but was never shown to the model. */
        NOT_IN_CONTEXT,
        /** Named nothing that exists. */
        UNKNOWN_REFERENCE,
        /** Claimed a source at all, or cited a characteristic the card does not declare. */
        MISSING
    }

    public record Finding(String characteristic, String reference, Status status) {

        public boolean isFabricated() {
            return status == Status.NOT_IN_CONTEXT || status == Status.UNKNOWN_REFERENCE;
        }
    }

    /**
     * @param findings every claim's citation, in card order, so a reviewer sees them all
     * @param fabricated subset that names a fragment the model was not shown
     */
    public record Verdict(List<Finding> findings, List<Finding> fabricated) {

        public boolean isClean() {
            return fabricated.isEmpty();
        }

        public List<String> messages() {
            return fabricated.stream()
                    .map(finding -> switch (finding.status()) {
                        case NOT_IN_CONTEXT ->
                                "'" + finding.characteristic() + "' cites " + finding.reference()
                                        + ", which exists but was not in the context this model was given";
                        case UNKNOWN_REFERENCE ->
                                "'" + finding.characteristic() + "' cites " + finding.reference()
                                        + ", which is not a fragment of this submission";
                        default -> "'" + finding.characteristic() + "' cites nothing";
                    })
                    .toList();
        }
    }

    /**
     * @param sources characteristic name to the reference the model gave it, straight off the card
     */
    public Verdict verify(AssembledContext context, Map<String, String> sources) {
        List<Finding> findings = new ArrayList<>();
        Set<String> declared = sources == null ? Set.of() : new LinkedHashSet<>(sources.keySet());

        for (String characteristic : declared) {
            String reference = sources.get(characteristic);
            if (reference == null || reference.isBlank()) {
                findings.add(new Finding(characteristic, reference, Status.MISSING));
                continue;
            }
            findings.add(new Finding(characteristic, reference,
                    context.find(reference).isPresent()
                            ? Status.SUPPORTED
                            : Status.UNKNOWN_REFERENCE));
        }

        List<Finding> fabricated = findings.stream().filter(Finding::isFabricated).toList();
        return new Verdict(findings, fabricated);
    }

    /**
     * The same check for a claim the card declares a source for but whose label is real elsewhere.
     *
     * <p>Separated from {@link #verify} because it answers a different question. That a label exists
     * in the corpus is worth knowing: a citation to a real fragment the model never saw is a
     * provenance failure, and a citation to no fragment at all is a hallucination, and the two lead
     * to different conversations with whoever maintains the documents.
     */
    public Verdict verifyAgainst(AssembledContext context, Map<String, String> sources,
                                 java.util.function.Predicate<String> existsInCorpus) {
        Verdict basic = verify(context, sources);
        List<Finding> findings = new ArrayList<>();
        for (Finding finding : basic.findings()) {
            if (finding.status() == Status.UNKNOWN_REFERENCE
                    && existsInCorpus.test(finding.reference())) {
                findings.add(new Finding(finding.characteristic(), finding.reference(),
                        Status.NOT_IN_CONTEXT));
            } else {
                findings.add(finding);
            }
        }
        List<Finding> fabricated = findings.stream().filter(Finding::isFabricated).toList();
        return new Verdict(findings, fabricated);
    }
}