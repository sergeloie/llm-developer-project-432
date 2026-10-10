package com.carddraft.context;

import java.util.List;
import java.util.Optional;

/**
 * Exactly what was put in front of the model, retained.
 *
 * <p>The reason this is a value that gets written down rather than recomputed on demand: a citation
 * can only be verified against the set the model was given, and that set is not recoverable from
 * the database afterwards. Retrieval is not reproducible — the ranking depends on the index as it
 * stood at that moment, and the index changes every time a document is added. Re-running the search
 * during verification would therefore answer a different question than the one being asked, and
 * would quietly pass a card the model actually invented.
 *
 * <p>Which is also why it is stored rather than carried in the workflow's memory: a worker restart
 * replays from history and the workflow cannot re-derive this.
 */
public record AssembledContext(String jobId, List<ContextChunk> chunks, int droppedAsDuplicate, int droppedOverBudget) {

    public AssembledContext {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public static AssembledContext empty(String jobId) {
        return new AssembledContext(jobId, List.of(), 0, 0);
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public List<String> references() {
        return chunks.stream().map(ContextChunk::reference).toList();
    }

    /**
     * Looks up a label the model used.
     *
     * <p>Absence is the answer that matters, so it is a plain {@code Optional} rather than an index
     * or a boolean: "this label was not in the context" is the single most consequential fact
     * citation verification produces.
     */
    public Optional<ContextChunk> find(String reference) {
        return chunks.stream()
                .filter(chunk -> chunk.reference().equals(reference))
                .findFirst();
    }

    /**
     * The text block handed to the model.
     *
     * <p>Rendered from the retained chunks rather than kept alongside them, so the prompt and the
     * record cannot drift apart. A stored copy of the rendered prompt would be a second thing to
     * keep in step for no benefit.
     */
    public String render() {
        StringBuilder rendered = new StringBuilder();
        for (ContextChunk chunk : chunks) {
            rendered.append(chunk.render()).append("\n\n");
        }
        return rendered.toString().strip();
    }
}
