package com.carddraft.context;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.carddraft.context.CitationVerifier.Status;
import com.carddraft.context.CitationVerifier.Verdict;
import com.carddraft.llm.ResultContract;
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
        Verdict verdict = verifier.verify(contextOf(3), Set.of("Power"), Map.of("Power", "C2"));

        assertThat(verdict.isClean()).isTrue();
        assertThat(verdict.findings())
                .singleElement()
                .extracting(CitationVerifier.Finding::status)
                .isEqualTo(Status.SUPPORTED);
    }

    /**
     * A citation written the way the fragment was displayed still resolves.
     *
     * <p>Found by running the service, not by reading it: the context is rendered to the model as
     * {@code [C1]}, the model copies what it was shown, and the lookup was being asked for the
     * bracketed form against a retained reference that has no brackets. Every citation on a real card
     * was therefore reported as fabricated — which is the gate designed to catch fabrications unable to
     * tell one from a truth.
     */
    @Test
    void aCitationInTheDisplayedFormResolvesToTheReferenceItWasShownAs() {
        Verdict verdict =
                verifier.verify(contextOf(3), Set.of("Power", "Weight"), Map.of("Power", "[C2]", "Weight", "[C1]"));

        assertThat(verdict.isClean()).isTrue();
        assertThat(verdict.findings())
                .extracting(CitationVerifier.Finding::reference)
                .as("and the finding reports the reference, so a reader is not left decoding brackets")
                .containsExactlyInAnyOrder("C1", "C2");
    }

    @Test
    void forgivingOneNotationDoesNotMakeTheCheckForgiving() {
        Verdict verdict = verifier.verify(
                contextOf(2),
                Set.of("Power", "Weight", "Model"),
                Map.of(
                        "Power", "[c1]",
                        "Weight", "[]",
                        "Model", "[ C1 ]"));

        assertThat(verdict.fabricated())
                .extracting(CitationVerifier.Finding::characteristic)
                .as("unwrapping the display form is not the same as forgiving a near miss: the case, "
                        + "an empty reference and a padded one are each still what the model wrote")
                .containsExactlyInAnyOrder("Power", "Weight", "Model");
    }

    /**
     * The failure that matters.
     *
     * <p>C7 does not exist in the corpus and never existed. The label is contiguous-checked, so no
     * database is consulted: with three fragments shown, anything past C3 was not sent.
     */
    @Test
    void aCitationBeyondTheContextIsFabricated() {
        Verdict verdict = verifier.verify(contextOf(3), Set.of("Power"), Map.of("Power", "C7"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated())
                .singleElement()
                .extracting(CitationVerifier.Finding::reference, CitationVerifier.Finding::status)
                .containsExactly("C7", Status.UNKNOWN_REFERENCE);
        assertThat(verdict.messages()).singleElement().asString().contains("'Power' cites C7");
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

        Verdict verdict =
                verifier.verifyAgainst(context, Set.of("Power"), Map.of("Power", "C9"), reference -> Set.of("C9")
                        .contains(reference));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated())
                .singleElement()
                .extracting(CitationVerifier.Finding::status)
                .isEqualTo(Status.NOT_IN_CONTEXT);
        assertThat(verdict.messages()).singleElement().asString().contains("exists but was not in the context");
    }

    @Test
    void aClaimWithNoSourceAtAllIsNotSupported() {
        Verdict verdict = verifier.verify(contextOf(3), Set.of("Power"), Map.of("Power", "  "));

        assertThat(verdict.findings())
                .singleElement()
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
        Verdict verdict = verifier.verify(
                contextOf(4),
                Set.of("Power", "Volume", "Weight"),
                Map.of(
                        "Power", "C1",
                        "Volume", "C2",
                        "Weight", "C12"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated()).hasSize(1);
        assertThat(verdict.findings())
                .as("every claim is reported, supported ones too")
                .hasSize(3);
        assertThat(verdict.messages()).hasSize(1);
    }

    /**
     * Nothing declared, nothing to verify.
     *
     * <p>An empty card is not a pass in the sense that matters; it is a card with no claims to check.
     * The coverage case — a declared characteristic with no source — is the failure, and it is tested
     * below.
     */
    @Test
    void noDeclaredCharacteristicsMeansNothingToVerify() {
        Verdict verdict = verifier.verify(contextOf(3), Set.of(), Map.of());

        assertThat(verdict.isClean()).isTrue();
        assertThat(verdict.findings()).isEmpty();
    }

    /**
     * The contract and the verifier must never disagree about a reference.
     *
     * <p>A card that passes the contract goes on to be verified, so a form the contract accepts but
     * the verifier will not resolve is a card that passes one gate and fails the next. The verifier
     * unwraps only a full pair of brackets — anything else is exactly what the model wrote — so the
     * contract must accept exactly the paired-or-absent forms and reject everything else.
     */
    @Test
    void theContractAcceptsExactlyTheFormsTheVerifierResolves() {
        AssembledContext context = contextOf(3);

        for (String accepted : List.of("C3", "[C3]")) {
            var card = cardWithSource(accepted);
            assertThat(ResultContract.sourcesProblems(card))
                    .as("the contract accepts " + accepted)
                    .isEmpty();
            assertThat(verifier.verify(context, Set.of("Power"), card.sources()).isClean())
                    .as("a card the contract accepted must verify: " + accepted)
                    .isTrue();
        }

        for (String rejected : List.of("[C3", "C3]", "c3", "[c3]", "[ C3 ]", "[]", " C3 ", " C3", "C3 ")) {
            var card = cardWithSource(rejected);
            assertThat(ResultContract.sourcesProblems(card))
                    .as("the contract rejects " + rejected)
                    .isNotEmpty();
            assertThat(verifier.verify(context, Set.of("Power"), card.sources()).isClean())
                    .as("and the verifier rejects it too: " + rejected)
                    .isFalse();
        }
    }

    private static com.carddraft.agents.ProductCard cardWithSource(String reference) {
        return new com.carddraft.agents.ProductCard(
                "Blender",
                "A blender.",
                Map.of("Power", "800 W"),
                List.of("Fast"),
                List.of(),
                0.9,
                Map.of("Power", reference));
    }

    @Test
    void aNullSourceMapIsTreatedAsNoSourcesRatherThanFailing() {
        assertThat(verifier.verify(contextOf(3), Set.of(), null).isClean()).isTrue();
    }

    /**
     * Verification against an empty context cannot be satisfied.
     *
     * <p>Which is correct: a model shown nothing and asked for citations has produced them from
     * somewhere other than here.
     */
    @Test
    void anEmptyContextSupportsNothing() {
        Verdict verdict = verifier.verify(AssembledContext.empty("job-1"), Set.of("Power"), Map.of("Power", "C1"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated()).hasSize(1);
    }

    @Test
    void labelsAreCaseSensitiveSoACardCannotLowerCaseItsWayPastAFragment() {
        Verdict verdict = verifier.verify(contextOf(2), Set.of("Power"), Map.of("Power", "c1"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.fabricated()).hasSize(1);
    }

    @Test
    void aLabelWithSurroundingSpaceIsRejectedRatherThanTrimmed() {
        Verdict verdict = verifier.verify(contextOf(2), Set.of("Power"), Map.of("Power", " C1 "));

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
        assertThat(verifier.verify(stored, Set.of("Volume"), Map.of("Volume", "C3"))
                        .isClean())
                .isTrue();
        assertThat(verifier.verify(stored, Set.of("Volume"), Map.of("Volume", "C4"))
                        .isClean())
                .isFalse();
    }

    @Test
    void messagesNameEveryFabricatedClaimForTheReworkPrompt() {
        Verdict verdict = verifier.verify(
                contextOf(3),
                Set.of("Power", "Volume", "Weight"),
                Map.of(
                        "Power", "C1",
                        "Volume", "C8",
                        "Weight", "C9"));

        assertThat(verdict.messages())
                .hasSize(2)
                .anyMatch(message -> message.contains("'Volume' cites C8"))
                .anyMatch(message -> message.contains("'Weight' cites C9"));
    }

    // --- coverage: every declared characteristic must be cited -----------------------------

    /**
     * A card with no sources at all fails every characteristic it declares.
     *
     * <p>The defect this guards: iterating the sources map reported nothing for a card that cited
     * nothing, so a draft the generator prompt required to cite passed its own verification. The
     * declared characteristics are what the card promised to support, so they are what is checked.
     */
    @Test
    void aCardWithNoSourcesAtAllFailsEveryDeclaredCharacteristic() {
        Verdict verdict = verifier.verify(contextOf(3), Set.of("Power", "Volume"), Map.of());

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.findings())
                .extracting(CitationVerifier.Finding::status)
                .containsExactlyInAnyOrder(Status.MISSING, Status.MISSING);
    }

    /**
     * A characteristic with no source entry fails with a MISSING finding.
     *
     * <p>Not a fabrication: nothing was cited, rather than something false. It is still a card a
     * reviewer cannot accept, so it fails verification and its sentence reaches the rework prompt.
     */
    @Test
    void aCharacteristicWithNoSourceEntryFailsWithAMissingFinding() {
        Verdict verdict = verifier.verify(contextOf(3), Set.of("Power", "Volume"), Map.of("Power", "C1"));

        assertThat(verdict.isClean()).isFalse();
        assertThat(verdict.findings())
                .filteredOn(finding -> finding.characteristic().equals("Volume"))
                .singleElement()
                .extracting(CitationVerifier.Finding::status, CitationVerifier.Finding::isFabricated)
                .containsExactly(Status.MISSING, false);
        assertThat(verdict.messages())
                .as("the omission reaches the rework prompt rather than failing silently")
                .anySatisfy(message -> assertThat(message).contains("Volume"));
    }

    /**
     * The clean path is unchanged: every characteristic covered by a shown fragment passes.
     */
    @Test
    void aFullyCoveredCardIsClean() {
        Verdict verdict =
                verifier.verify(contextOf(3), Set.of("Power", "Volume"), Map.of("Power", "C1", "Volume", "C2"));

        assertThat(verdict.isClean()).isTrue();
        assertThat(verdict.findings())
                .extracting(CitationVerifier.Finding::status)
                .containsOnly(Status.SUPPORTED);
    }
}
