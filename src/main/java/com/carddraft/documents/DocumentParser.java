package com.carddraft.documents;

import java.util.List;
import java.util.Locale;

import com.carddraft.agents.StructuralUnit;

/**
 * One parser per format.
 *
 * <p>Each format brings its own trouble — a PDF has no headings, a DOCX has paragraphs and no
 * pages, a spreadsheet has neither prose nor pages — so each is handled on its own terms rather
 * than forced through a lowest common denominator that would lose the thing that mattered.
 */
public interface DocumentParser {

    /**
     * The document's prose as its structural units.
     *
     * @throws DocumentRejectedException when the file cannot be read at all. A document with no
     *         text layer is rejected rather than returned empty: an empty result with no reason
     *         is indistinguishable from a broken parser.
     */
    List<StructuralUnit> parse(byte[] content);

    /**
     * The filename extensions this parser accepts, each including its leading dot.
     *
     * <p>This is the single declaration of what the format is: {@link #supports} and the document
     * service's advertised list are both derived from it, so a format added here is accepted and
     * advertised with no second edit to keep in step.
     */
    List<String> extensions();

    /**
     * Whether this parser handles the named file, derived from {@link #extensions()}.
     */
    default boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return extensions().stream().anyMatch(lower::endsWith);
    }
}
