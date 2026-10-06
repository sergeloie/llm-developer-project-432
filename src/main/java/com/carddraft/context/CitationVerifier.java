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
 *
 * <p>References are compared exactly, and that is a security property rather than pedantry: trimming,
 * lower-casing or otherwise forgiving would let a card write a near-miss of a real reference and pass
 * a check it should fail. The one concession is the display form this service itself renders the
 * fragments in — {@code [C1]} — because a model shown that notation will copy it, and a citation that
 * is right apart from the brackets it was given is not a fabrication.
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
            String reference = canonicalReference(sources.get(characteristic));
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
     * The reference a citation names, given the text the model wrote for it.
     *
     * <p>Unwraps a bracketed display form and does nothing else. {@link ContextChunk#render()} shows
     * the model {@code [C1]}, so {@code [C1]} in a card is this service's own notation rather than a
     * different reference, and rejecting it would fail every correct citation — which is what
     * happened: a real card's thirteen correct citations were each reported as fabricated.
     *
     * <p>Nothing else is forgiven. {@code c1}, {@code " C1 "} and {@code []} are each still what the
     * model wrote, and a verifier that forgives a near-miss of a real reference cannot be the thing
     * that catches a fabricated one. Case and surrounding space are the model's to get right.
     */
    private static String canonicalReference(String written) {
        if (written == null || written.length() < 3) {
            return written;
        }
        if (written.startsWith("[") && written.endsWith("]")) {
            String inner = written.substring(1, written.length() - 1);
            return inner.isBlank() ? written : inner;
        }
        return written;
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