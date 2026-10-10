package com.carddraft.documents;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.carddraft.agents.StructuralUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supported set lives in the parsers and nowhere else.
 *
 * <p>A parser declares its extensions once; the upload check and the advertised list are both
 * derived from that declaration. A format added to a parser, therefore, cannot be accepted by one
 * and rejected or hidden by the other — which is the drift this guards against.
 */
class SupportedFormatsTest {

    @Test
    void aParserAddedInOnePlaceIsAcceptedAndAdvertised() {
        DocumentService service =
                serviceWith(declaring(".pdf"), declaring(".docx"), declaring(".xlsx", ".xls"), declaring(".odt"));

        assertThat(service.supports("manual.odt")).isTrue();
        assertThat(service.supportedFormats()).contains("odt");
    }

    @Test
    void aFormatNoParserDeclaresIsRejectedAndAbsentFromTheAdvertisedList() {
        DocumentService service = serviceWith(declaring(".pdf"), declaring(".docx"), declaring(".xlsx", ".xls"));

        assertThat(service.supports("supplier.exe")).isFalse();
        assertThat(service.supportedFormats()).doesNotContain("exe");
    }

    @Test
    void theRealParsersAdvertiseExactlyTheFormatsTheyAccept() {
        TextNormaliser normaliser = new TextNormaliser();
        DocumentService service = serviceWith(
                new PdfDocumentParser(normaliser),
                new DocxDocumentParser(normaliser),
                new XlsxDocumentParser(normaliser));

        assertThat(service.supportedFormats()).containsExactlyInAnyOrder("pdf", "docx", "xlsx", "xls");
    }

    private static DocumentService serviceWith(DocumentParser... parsers) {
        return new DocumentService(List.of(parsers), new TextNormaliser(), null, null);
    }

    private static DocumentParser declaring(String... extensions) {
        return new DocumentParser() {
            @Override
            public List<StructuralUnit> parse(byte[] content) {
                return List.of();
            }

            @Override
            public List<String> extensions() {
                return List.of(extensions);
            }
        };
    }
}
