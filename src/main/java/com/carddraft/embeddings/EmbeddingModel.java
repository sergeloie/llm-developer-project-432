package com.carddraft.embeddings;

import java.util.List;

/**
 * Turns text into vectors.
 *
 * <p>Two entry points, not one, because the model is not symmetric. The families this project can
 * use were trained with a task prefix on each side, and the two sides take different prefixes: a
 * query is asked for, a document is offered. Encoding both the same way produces vectors that are
 * individually reasonable and jointly mis-ranked, and the failure is silent — nothing errors, the
 * scores just quietly stop meaning anything.
 *
 * <p>Which is exactly why the templates are configuration rather than constants. They come from
 * the model's card, and a model swap should be a settings change.
 */
public interface EmbeddingModel {

    /** Encodes a user's question, with the query-side template applied. */
    List<Double> embedQuery(String query);

    /**
     * Encodes a chunk for indexing, with the document-side template applied.
     *
     * @param title the chunk's section, which the document-side template asks for. Also what a
     *              citation will point at, so the two uses are the same piece of information.
     */
    List<Double> embedDocument(String text, String title);

    /** Encodes several chunks in one call; the model is far slower per text than per batch. */
    List<List<Double>> embedDocuments(List<Document> documents);

    int dimension();

    record Document(String text, String title) {
    }
}
