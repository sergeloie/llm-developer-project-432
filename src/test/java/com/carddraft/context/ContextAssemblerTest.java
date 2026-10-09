package com.carddraft.context;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.carddraft.repositories.ChunkSearchRepository.Hit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context assembly, as the model will experience it.
 *
 * <p>No database and no model: both would add ways for this to fail without testing the decisions.
 * What is under test is which fragments reach the prompt, what they are called, and what gets
 * dropped — and every one of those is a choice rather than a side effect.
 */
class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler(new ContextSettings(12, 12000));

    private static Hit hit(long id, String documentId, String section, String text) {
        return new Hit(id, documentId, 1, section, text, 0.1, "vector");
    }

    @Test
    void labelsAreContiguousFromOneAndFollowRetrievalOrder() {
        List<Hit> hits = List.of(
                hit(10, "doc-a", "Power", "Power 800 W"),
                hit(11, "doc-b", "Volume", "Bowl volume 1.5 l"),
                hit(12, "doc-a", "Weight", "Weight 5.9 kg"));

        AssembledContext context = assembler.assemble("job-1", hits);

        assertThat(context.references()).containsExactly("C1", "C2", "C3");
        assertThat(context.chunks()).extracting(ContextChunk::chunkId).containsExactly(10L, 11L, 12L);
        assertThat(context.chunks()).extracting(ContextChunk::section)
                .containsExactly("Power", "Volume", "Weight");
    }

    /**
     * The same fragment uploaded under two names must cost the budget once.
     *
     * <p>Without this, a specification uploaded twice as PDF and DOCX is retrieved twice, the model
     * reads both copies, and whichever it cites resolves to a document the reviewer then has to
     * choose between.
     */
    @Test
    void identicalFragmentsFromDifferentDocumentsAppearOnce() {
        List<Hit> hits = List.of(
                hit(10, "doc-pdf", "Power", "Power 800 W, bowl volume 1.5 l"),
                hit(11, "doc-docx", "Power", "Power 800 W, bowl volume 1.5 l"),
                hit(12, "doc-pdf", "Weight", "Weight 5.9 kg"));

        AssembledContext context = assembler.assemble("job-1", hits);

        assertThat(context.chunks()).extracting(ContextChunk::chunkId).containsExactly(10L, 12L);
        assertThat(context.droppedAsDuplicate()).isEqualTo(1);
    }

    /**
     * The same text with different line breaks is the same fragment.
     *
     * <p>A PDF and a DOCX of one manual never agree on whitespace, so deduplicating on the raw
     * string would miss precisely the duplicates it exists to catch.
     */
    @Test
    void whitespaceDifferencesDoNotDefeatDeduplication() {
        List<Hit> hits = List.of(
                hit(10, "doc-a", "Power", "Power 800 W"),
                hit(11, "doc-b", "Power", "  power   800  W \n"));

        AssembledContext context = assembler.assemble("job-1", hits);

        assertThat(context.chunks()).hasSize(1);
        assertThat(context.droppedAsDuplicate()).isEqualTo(1);
    }

    @Test
    void paraphrasesAreNotTreatedAsDuplicates() {
        List<Hit> hits = List.of(
                hit(10, "doc-a", "Power", "Power 800 W"),
                hit(11, "doc-b", "Power", "Motor output 800 watts"));

        assertThat(assembler.assemble("job-1", hits).chunks()).hasSize(2);
    }

    /**
     * Blank fragments collapse to one and everything else survives.
     *
     * <p>Not a special case in the code — a blank text fingerprints to the empty string — but worth
     * pinning, because a chunk with no text is a parsing failure and should not be able to silently
     * consume the whole budget.
     */
    @Test
    void emptyFragmentsCollapseRatherThanFloodingTheBudget() {
        List<Hit> hits = List.of(
                hit(10, "doc-a", null, ""),
                hit(11, "doc-b", null, "   "),
                hit(12, "doc-c", null, "Weight 5.9 kg"));

        AssembledContext context = assembler.assemble("job-1", hits);

        assertThat(context.chunks()).extracting(ContextChunk::chunkId).containsExactly(10L, 12L);
        assertThat(context.droppedAsDuplicate()).isEqualTo(1);
    }

    @Test
    void theChunkLimitIsEnforcedAndTheBestRankedSurvive() {
        ContextAssembler limited = new ContextAssembler(new ContextSettings(3, 12000));
        List<Hit> hits = List.of(
                hit(1, "d", "s", "first"),
                hit(2, "d", "s", "second"),
                hit(3, "d", "s", "third"),
                hit(4, "d", "s", "fourth"),
                hit(5, "d", "s", "fifth"));

        AssembledContext context = limited.assemble("job-1", hits);

        assertThat(context.chunks()).extracting(ContextChunk::chunkId).containsExactly(1L, 2L, 3L);
        assertThat(context.droppedOverBudget()).isEqualTo(2);
    }

    /**
     * One huge fragment cannot take the whole budget, and skipping it beats keeping it.
     *
     * <p>A spreadsheet flattened into a single chunk is a real case here, and a chunk-count limit
     * alone would let it through, leaving the model with one fragment and no room for anything else.
     *
     * <p>Note what is asserted: the oversized fragment is dropped and the two that fit still go in.
     * Truncating the giant instead would fill the context with one unusable fragment and answer
     * nothing — a fragment that does not fit is worth less to the model than no fragment at all,
     * while two fragments that do fit carry two facts.
     */
    @Test
    void theCharacterLimitSkipsAnOversizedFragmentAndKeepsWhatFits() {
        ContextAssembler limited = new ContextAssembler(new ContextSettings(12, 20));
        List<Hit> hits = List.of(
                hit(1, "d", "s", "x".repeat(50)),
                hit(2, "d", "s", "short one"),
                hit(3, "d", "s", "short two"));

        AssembledContext context = limited.assemble("job-1", hits);

        assertThat(context.chunks()).extracting(ContextChunk::chunkId).containsExactly(2L, 3L);
        assertThat(context.droppedOverBudget()).isEqualTo(1);
    }

    @Test
    void everyRenderedLineCarriesTheLabelSoAQuoteCanBeTraced() {
        AssembledContext context = assembler.assemble("job-1", List.of(
                hit(10, "doc-a", "Power", "Power 800 W"),
                hit(11, "doc-b", null, "Weight 5.9 kg")));

        String rendered = context.render();

        assertThat(rendered)
                .contains("[C1] Power — Power 800 W")
                .contains("[C2] Weight 5.9 kg");
    }

    @Test
    void aLabelTheModelInventsIsSimplyAbsent() {
        AssembledContext context = assembler.assemble("job-1", List.of(hit(10, "doc-a", "s", "text")));

        assertThat(context.find("C1")).isPresent();
        assertThat(context.find("C2")).as("the contiguity is what makes membership a complete check")
                .isEmpty();
        assertThat(context.find("C0")).isEmpty();
        assertThat(context.find("")).isEmpty();
    }

    @Test
    void anEmptyRetrievalYieldsAnEmptyContextRatherThanFailing() {
        AssembledContext context = assembler.assemble("job-1", List.of());

        assertThat(context.isEmpty()).isTrue();
        assertThat(context.render()).isEmpty();
        assertThat(context.references()).isEmpty();
    }

    /**
     * Fingerprinting is about identity, not equality.
     *
     * <p>Pinned because the choice is invisible in the assembler and everything about it is a
     * judgement: case and whitespace yes, because one manual arrives in two formats; character
     * similarity no, because treating paraphrases as one fragment would drop real content.
     */
    @Test
    void fingerprintingFoldsCaseAndWhitespaceOnly() {
        assertThat(ContextAssembler.fingerprint("Power   800\nW")).isEqualTo("power 800 w");
        assertThat(ContextAssembler.fingerprint("POWER 800 W")).isEqualTo(ContextAssembler.fingerprint("power 800 w"));
        assertThat(ContextAssembler.fingerprint("power 800")).isNotEqualTo(ContextAssembler.fingerprint("power 900"));
        assertThat(ContextAssembler.fingerprint(null)).isEmpty();
    }

    @Test
    void aContextKnowsWhichJobItBelongsTo() {
        AssembledContext context = assembler.assemble("job-77", List.of(hit(1, "d", "s", "t")));

        assertThat(context.jobId()).isEqualTo("job-77");
        assertThat(context.chunks().get(0).target())
                .extracting(ContextChunk.CitationTarget::documentId,
                        ContextChunk.CitationTarget::page,
                        ContextChunk.CitationTarget::section)
                .containsExactly("d", 1, "s");
    }

    @Test
    void verifierAgreesWithTheContextItWasGiven() {
        AssembledContext context = assembler.assemble("job-1", List.of(hit(10, "d", "s", "text")));

        CitationVerifier.Verdict verdict = new CitationVerifier()
                .verify(context, Set.of("Power"), Map.of("Power", "C1"));

        assertThat(verdict.isClean()).isTrue();
        assertThat(verdict.findings()).hasSize(1);
    }
}