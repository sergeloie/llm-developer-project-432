package com.carddraft.context;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.carddraft.context.CitationVerifier.Status;
import com.carddraft.context.CitationVerifier.Verdict;
import com.carddraft.repositories.ChunkSearchRepository.Hit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Citation verification, which is the point of the whole ticket.
 *
 * <p>A card that cites something the model never saw does not reach a content manager as finished,
 * so these tests are about the difference between a reference that resolves and a reference that is
 * supported. Most of them are about the second, because that is the one that fails in practice.
 */
class CitationVerifierTest {

    private final ContextAssembler assembler = new ContextAssembler(new ContextSettings(12, 12000));
    private final CitationVerifier verifier = new CitationVerifier();

    private AssembledContext contextOf(int chunks) {
        List<Hit> hits = new java.util.ArrayList<>();
        for (int i = 1; i <= chunks; i++) {
            hits.add(new Hit(i, "doc-" + i, 1, "Section " + i, "fragment " + i, 0.1, "vector"));
        }
        return assembler.assemble("job-1", hits);
    }

    @Test
    void aCitationToAShownFragmentIsSupported() {
        Verdict verdict = verifier.verify(contextOf(3), Map.of("Power", "C2"));

        assertThat(verdict.isClean()).isTrue();
        assertThat(verdict.findings()).singleElement()
                .extracting(CitationVerifier.Finding::status)
                .isEqualTo(Status.SUPPORTED);
    }

    /**
     * The failure that matters.
     *
     * <p>C7 does not exist in the corpus and never existed. The label is contiguous-checked, so no
     * database is consulted: with three fragments shown, anything past C3 was not sent.
     */
    @Test
    void aCitationBeyondTheContextIsFabricated() {
        Verdict verdict = verifier.verify(contextOf(3), Map.of("Power", "C7"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated()).singleElement()
                .extracting(CitationVerifier.Finding::reference, CitationVerifier.Finding::status)
                .containsExactly("C7", Status.UNKNOWN_REFERENCE);
        assertThat(verdict.messages()).singleElement()
                .asString()
                .contains("'Power' cites C7");
    }

    /**
     * A real fragment the model was never shown.
     *
     * <p>This is the case a naive existence check waves through: chunk 99 is in the database, the
     * label resolves to something real, and the card looks well-sourced to a reviewer. It is not —
     * the model had no access to it, so the citation is invented provenance. Reported separately
     * from a plain hallucination because the two point at different problems.
     */
    @Test
    void aCitationToARealFragmentOutsideTheContextIsStillAFabrication() {
        AssembledContext context = contextOf(3);

        Verdict verdict = verifier.verifyAgainst(context, Map.of("Power", "C9"),
                reference -> Set.of("C9").contains(reference));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated()).singleElement()
                .extracting(CitationVerifier.Finding::status)
                .isEqualTo(Status.NOT_IN_CONTEXT);
        assertThat(verdict.messages()).singleElement()
                .asString()
                .contains("exists but was not in the context");
    }

    @Test
    void aClaimWithNoSourceAtAllIsNotSupported() {
        Verdict verdict = verifier.verify(contextOf(3), Map.of("Power", "  "));

        assertThat(verdict.findings()).singleElement()
                .extracting(CitationVerifier.Finding::status)
                .isEqualTo(Status.MISSING);
        assertThat(verdict.fabricated())
                .as("a missing citation is a contract failure, not a fabricated reference")
                .isEmpty();
    }

    /**
     * One bad citation spoils the card.
     *
     * <p>Not the worst offender: a verdict is about the whole card, because a reviewer cannot accept
     * a card "except for the one claim". Rework gets the whole list so it can fix them together.
     */
    @Test
    void aSingleFabricatedCitationFailsTheWholeCardAndNamesEveryOffender() {
        Verdict verdict = verifier.verify(contextOf(4), Map.of(
                "Power", "C1",
                "Volume", "C2",
                "Weight", "C12"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated()).hasSize(1);
        assertThat(verdict.findings()).as("every claim is reported, supported ones too")
                .hasSize(3);
        assertThat(verdict.messages()).hasSize(1);
    }

    @Test
    void anEmptySourceMapIsCleanButEmpty() {
        Verdict verdict = verifier.verify(contextOf(3), Map.of());

        assertThat(verdict.isClean()).isTrue();
        assertThat(verdict.findings()).isEmpty();
    }

    @Test
    void aNullSourceMapIsTreatedAsNoSourcesRatherThanFailing() {
        assertThat(verifier.verify(contextOf(3), null).isClean()).isTrue();
    }

    /**
     * Verification against an empty context cannot be satisfied.
     *
     * <p>Which is correct: a model shown nothing and asked for citations has produced them from
     * somewhere other than here.
     */
    @Test
    void anEmptyContextSupportsNothing() {
        Verdict verdict = verifier.verify(AssembledContext.empty("job-1"), Map.of("Power", "C1"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated()).hasSize(1);
    }

    @Test
    void labelsAreCaseSensitiveSoACardCannotLowerCaseItsWayPastAFragment() {
        Verdict verdict = verifier.verify(contextOf(2), Map.of("Power", "c1"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated()).hasSize(1);
    }

    @Test
    void aLabelWithSurroundingSpaceIsRejectedRatherThanTrimmed() {
        Verdict verdict = verifier.verify(contextOf(2), Map.of("Power", " C1 "));

        assertThat(verdict.isClean()).isFalse();
    }

    /**
     * The retained context is what makes a verdict mean anything later.
     *
     * <p>Round-tripping through the record is the minimum: a citation checkable against an
     * in-memory object but not against the stored one would pass in tests and fail in production,
     * because production verifies after a worker restart.
     */
    @Test
    void aVerdictHoldsAfterTheContextIsReadBackInOrder() {
        AssembledContext stored = new AssembledContext("job-1", contextOf(3).chunks(), 0, 0);

        assertThat(stored.references()).containsExactly("C1", "C2", "C3");
        assertThat(verifier.verify(stored, Map.of("Volume", "C3")).isClean()).isTrue();
        assertThat(verifier.verify(stored, Map.of("Volume", "C4")).isClean()).isFalse();
    }

    @Test
    void messagesNameEveryFabricatedClaimForTheReworkPrompt() {
        Verdict verdict = verifier.verify(contextOf(3), Map.of(
                "Power", "C1",
                "Volume", "C8",
                "Weight", "C9"));

        assertThat(verdict.messages())
                .hasSize(2)
                .anyMatch(message -> message.contains("'Volume' cites C8"))
                .anyMatch(message -> message.contains("'Weight' cites C9"));
    }
}