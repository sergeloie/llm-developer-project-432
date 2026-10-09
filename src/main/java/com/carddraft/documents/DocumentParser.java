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

    /**
     * A document the service will not process, with a reason a person can act on.
     *
     * <p>Not an error in the incident sense. A scan with no text layer is a normal thing to be
     * sent, and the correct response is to say so.
     */
    class DocumentRejectedException extends RuntimeException {

        private final String reason;

        public DocumentRejectedException(String reason, String detail) {
            super(reason + (detail == null || detail.isBlank() ? "" : ": " + detail));
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
