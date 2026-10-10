package com.carddraft.context;

import java.util.List;

import com.carddraft.repositories.ChunkSearchRepository.Hit;

/**
 * A retrieved fragment, carrying the name the model will refer to it by.
 *
 * @param reference a short label like {@code C3}, assigned here and nowhere else. The model may only
 *                  use these labels, and they are renumbered per request rather than being chunk
 *                  ids: a chunk id is an opaque number the model can invent, mutate or copy from
 *                  another answer, and a label the application allocated is checkable by membership.
 * @param chunkId   the real chunk, so a reader can open what was cited
 * @param documentId the document to open it in
 */
public record ContextChunk(String reference, long chunkId, String documentId, int page, String section, String text) {

    public static ContextChunk from(Hit hit, int position) {
        return new ContextChunk("C" + position, hit.chunkId(), hit.documentId(), hit.page(), hit.section(), hit.text());
    }

    /** The line the model sees. Carries the reference in every line it might quote from. */
    public String render() {
        return "[" + reference + "] " + (section == null || section.isBlank() ? "" : section + " — ") + text;
    }

    /** One citation target: enough to open the source, and no more than a reader needs. */
    public CitationTarget target() {
        return new CitationTarget(reference, chunkId, documentId, page, section);
    }

    public record CitationTarget(String reference, long chunkId, String documentId, int page, String section) {}

    public static List<ContextChunk> none() {
        return List.of();
    }
}
