package com.carddraft.agents;

import java.util.List;

/**
 * The smallest piece of a document that knows its own page.
 *
 * <p>Parsing produces structural units; chunking turns them into chunks. Keeping the two apart
 * matters because they answer different questions: a unit knows where it was printed, and a chunk
 * knows how it will be retrieved.
 *
 * @param page    one-based page number, or 1 for a format without pages
 * @param section the heading the unit falls under, or a synthetic one when the document has none
 * @param text    the text itself, already normalised
 * @param table   true when the unit is a table row, which must never be split across chunks
 */
public record StructuralUnit(int page, String section, String text, boolean table) {

    public static final String NO_SECTION = "General";

    public static List<StructuralUnit> prose(int page, String section, String text) {
        return List.of(new StructuralUnit(page, section, text, false));
    }
}
