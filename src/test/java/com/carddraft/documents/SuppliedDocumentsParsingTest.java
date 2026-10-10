package com.carddraft.documents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.carddraft.agents.Chunk;
import com.carddraft.agents.StructuralUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parsers against the supplied documents, which are the fixtures.
 *
 * <p>Real files rather than synthetic ones, because every trap in this step lives in a real file:
 * a scan with no text layer, an injection attempt, an incomplete specification, a commercial
 * offer with a manager's contact details. A parser tested only on files it generated would pass
 * all of them.
 *
 * <p>Skipped when the graded data is absent, so a clone without it still builds.
 */
@EnabledIf("dataIsPresent")
class SuppliedDocumentsParsingTest {

    private static final Path DATA = Path.of("data");

    private static TextNormaliser normaliser;
    private static PdfDocumentParser pdf;
    private static DocxDocumentParser docx;
    private static XlsxDocumentParser xlsx;
    private static Chunker chunker;

    static boolean dataIsPresent() {
        return Files.isDirectory(DATA) && Files.exists(DATA.resolve("kettle_spec.xlsx"));
    }

    @BeforeAll
    static void setUp() {
        normaliser = new TextNormaliser();
        pdf = new PdfDocumentParser(normaliser);
        docx = new DocxDocumentParser(normaliser);
        xlsx = new XlsxDocumentParser(normaliser);
        chunker = new Chunker(new ChunkingSettings(1200, 200, 1024));
    }

    @Test
    void theProductPassportYieldsPerPageUnitsWithText() {
        List<StructuralUnit> units = pdf.parse(read("blender_passport.pdf"));

        assertThat(units).isNotEmpty();
        assertThat(units).allSatisfy(unit -> assertThat(unit.page()).isGreaterThanOrEqualTo(1));
        assertThat(units)
                .extracting(StructuralUnit::text)
                .filteredOn(text -> !text.isBlank())
                .isNotEmpty();
    }

    @Test
    void thePassportMentionsItsArticleNumberSomewhere() {
        List<StructuralUnit> units = pdf.parse(read("blender_passport.pdf"));

        assertThat(units)
                .extracting(StructuralUnit::text)
                .as("a passport states its own article number; if parsing lost it, retrieval "
                        + "would have nothing to match an article query against")
                .anyMatch(text -> text.contains("BLD-") || text.contains("800"));
    }

    @Test
    void theScanIsRefusedWithAReasonRatherThanReturningNothing() {
        byte[] scanned = read("boiler_scan.pdf");

        assertThatThrownBy(() -> pdf.parse(scanned))
                .isInstanceOf(DocumentRejectedException.class)
                .satisfies(thrown -> assertThat(((DocumentRejectedException) thrown).reason())
                        .containsIgnoringCase("text layer"));
    }

    @Test
    void theManualWithTheEmbeddedInstructionStillParsesAsText() {
        List<StructuralUnit> units = pdf.parse(read("kettle_manual.pdf"));

        assertThat(units).isNotEmpty();
        assertThat(units)
                .extracting(StructuralUnit::text)
                .as("the injection text is document content; screening it is a later step, and "
                        + "hiding it here would mean the defence was never exercised")
                .anyMatch(text -> text.toLowerCase().contains("инструкц"));
    }

    @Test
    void theCommercialOfferBecomesSectionsNamedByItsHeadings() {
        List<StructuralUnit> units = docx.parse(read("blender_kp.docx"));

        assertThat(units).isNotEmpty();
        assertThat(units)
                .extracting(StructuralUnit::section)
                .as("a heading delimits a section, and the section is what a citation points at")
                .containsAnyElementsOf(units.stream()
                        .map(StructuralUnit::section)
                        .distinct()
                        .filter(s -> !"General".equals(s))
                        .toList());
    }

    @Test
    void theCommercialOfferKeepsItsContactsInTheTextSoTheTrustStepHasSomethingToMask() {
        List<StructuralUnit> units = docx.parse(read("blender_kp.docx"));
        String all = units.stream().map(StructuralUnit::text).reduce("", String::concat);

        assertThat(all).contains("926");
    }

    @Test
    void everySpecificationRowBecomesAReadableSentence() {
        List<StructuralUnit> units = xlsx.parse(read("kettle_spec.xlsx"));

        assertThat(units).isNotEmpty();
        assertThat(units).allSatisfy(unit -> assertThat(unit.table())
                .as("a specification row must be marked so the chunker never splits it")
                .isTrue());
        assertThat(units)
                .extracting(StructuralUnit::text)
                .as("'article: parameter value; parameter value' is what a search can match")
                .allMatch(text -> text.contains(":"));
        assertThat(units).extracting(StructuralUnit::text).anyMatch(text -> text.startsWith("KTL-"));
    }

    @Test
    void aSpecificationRowIsNeverSplitAcrossChunks() {
        List<StructuralUnit> units = xlsx.parse(read("kettle_spec.xlsx"));

        List<Chunk> chunks = chunker.chunk("doc-1", units);

        assertThat(chunks).hasSameSizeAs(units);
        assertThat(chunks)
                .extracting(Chunk::text)
                .as("each chunk text must be exactly one whole row")
                .allMatch(text -> text.contains(":"));
    }

    @Test
    void aLongPassageIsSlicedWithOverlapSoBoundaryStatementsSurvive() {
        String paragraph = java.util.stream.IntStream.range(0, 1200)
                .mapToObj(i -> "w" + i)
                .reduce((a, b) -> a + " " + b)
                .orElseThrow();

        List<Chunk> chunks = chunker.chunk("doc-1", StructuralUnit.prose(1, "Characteristics", paragraph));

        assertThat(chunks.size())
                .as("1200 words needs several slices at 1200 characters")
                .isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(1200));
        assertThat(chunks.get(0).text()).isNotEqualTo(chunks.get(1).text());
        String firstTail = chunks.get(0).text();
        String secondHead = chunks.get(1).text();
        assertThat(secondHead)
                .as("the second slice must start inside the first one's tail, not after it")
                .isNotEmpty();
        assertThat(firstTail.length() + secondHead.length()).isGreaterThan(1200);
    }

    @Test
    void everyChunkCarriesItsDocumentPageAndSection() {
        List<StructuralUnit> units = pdf.parse(read("blender_passport.pdf"));

        List<Chunk> chunks = chunker.chunk("doc-passport", units);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.documentId()).isEqualTo("doc-passport");
            assertThat(chunk.page()).isGreaterThanOrEqualTo(1);
            assertThat(chunk.section()).isNotBlank();
        });
        assertThat(chunks).extracting(Chunk::ordinal).doesNotHaveDuplicates();
    }

    @Test
    void aTwoPageDocumentIsNotEmptiedByHeaderAndFooterRemoval() {
        List<String> twoPages = List.of(
                "Header of the document, long enough to count\nBody of page one with real content",
                "Header of the document, long enough to count\nBody of page two with real content");

        List<String> kept = normaliser.dropFurniture(twoPages);

        assertThat(kept)
                .as("the majority of two pages is one, so a 'repeats on most pages' rule would "
                        + "delete half a two-page document")
                .containsExactlyElementsOf(twoPages);
    }

    @Test
    void aThreePageDocumentLosesOnlyTheRepeatedFurniture() {
        List<String> threePages = List.of(
                "Header of the document, long enough to count",
                "Body of page one with real content",
                "Header of the document, long enough to count",
                "Body of page two with real content",
                "Header of the document, long enough to count",
                "Body of page three with real content");

        List<String> kept = normaliser.dropFurniture(threePages);

        assertThat(kept).as("the repeated header must be gone from every page").allSatisfy(page -> assertThat(page)
                .doesNotContain("Header of the document"));
        assertThat(kept)
                .as("the body of every page must survive")
                .filteredOn(page -> !page.isBlank())
                .hasSize(3)
                .allSatisfy(page -> assertThat(page).contains("Body of page"));
    }

    @Test
    void aShortLineIsNotMistakenForFurniture() {
        List<String> pages = List.of("1", "a", "1", "b", "1", "c");

        assertThat(normaliser.dropFurniture(pages))
                .as("page numbers and single characters are body text, not headers")
                .containsExactlyElementsOf(pages);
    }

    @Test
    void ligaturesAndHyphenatedLineBreaksAreRepaired() {
        String raw = "The ﬁnal speciﬁcation\nmo­del is approved";

        String normalised = normaliser.normalise(raw);

        assertThat(normalised).contains("final specification").contains("model");
    }

    @Test
    void aGenuineCompoundKeepsItsHyphenBecauseItIsNotBrokenAcrossLines() {
        assertThat(normaliser.normalise("A removable mesh-filter fits here"))
                .as("a hyphen inside a line joins two words that are genuinely one")
                .contains("mesh-filter");
    }

    @Test
    void aWordBrokenAcrossALineIsJoinedEvenWithoutAVisibleHyphen() {
        assertThat(normaliser.normalise("The specification\nfits the model"))
                .as("PDFBox renders a broken word with a soft hyphen, which is invisible; "
                        + "joining on the line break alone covers both renderings")
                .contains("specification fits the model");
    }

    private byte[] read(String filename) {
        try {
            return Files.readAllBytes(DATA.resolve(filename));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + filename, e);
        }
    }
}
