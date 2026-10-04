package com.carddraft.documents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import com.carddraft.agents.StructuralUnit;

/**
 * DOCX, paragraph by paragraph, with headings delimiting sections.
 *
 * <p>The heading is the reason this format is easier than it looks: a styled heading is a real
 * structural boundary, and a unit's section is carried forward from the last heading above it.
 * That section then becomes the chunk's title, which is also the slot the embedding model wants
 * filled on the document side — so the same piece of information does two jobs.
 *
 * <p>DOCX has no pages, so every unit reports page 1. A citation into a DOCX points at a section
 * rather than a page, and pretending otherwise would be worse than saying so.
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    private final TextNormaliser normaliser;

    public DocxDocumentParser(TextNormaliser normaliser) {
        this.normaliser = normaliser;
    }

    @Override
    public List<StructuralUnit> parse(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {

            String wholeText = extractor.getText();
            if (wholeText == null || wholeText.isBlank()) {
                throw new DocumentRejectedException("the DOCX contains no text", null);
            }

            List<StructuralUnit> units = new ArrayList<>();
            String section = StructuralUnit.NO_SECTION;
            StringBuilder buffer = new StringBuilder();

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = normaliser.normalise(paragraph.getText());
                if (text.isEmpty()) {
                    continue;
                }
                if (isHeading(paragraph)) {
                    if (!buffer.isEmpty()) {
                        units.addAll(StructuralUnit.prose(1, section, buffer.toString()));
                        buffer.setLength(0);
                    }
                    section = text;
                    continue;
                }
                if (!buffer.isEmpty()) {
                    buffer.append('\n');
                }
                buffer.append(text);
            }
            if (!buffer.isEmpty()) {
                units.addAll(StructuralUnit.prose(1, section, buffer.toString()));
            }
            if (units.isEmpty()) {
                throw new DocumentRejectedException(
                        "every paragraph in the DOCX is a heading, so there is no body text", null);
            }
            return units;
        } catch (IOException e) {
            throw new DocumentRejectedException("the DOCX could not be read", e.getMessage());
        }
    }

    private boolean isHeading(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        return style != null && style.toLowerCase().replace(" ", "").startsWith("heading");
    }

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".docx");
    }
}
