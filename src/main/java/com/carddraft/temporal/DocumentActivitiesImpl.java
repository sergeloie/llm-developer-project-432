package com.carddraft.temporal;

import org.springframework.stereotype.Component;

import com.carddraft.documents.DocumentService;
import com.carddraft.search.EmbeddingApplicationService;

/**
 * Runs document parsing and indexing as durable steps.
 *
 * <p>Blocking is allowed here and nowhere else, so this is where the work belongs. The workflow
 * holds only the identifier and the outcome.
 *
 * <p>Each step returns the document's own outcome rather than deciding what to do with it. A refusal
 * is a result, not a failure: retrying a scan with no text layer three times costs three times nothing
 * and ends where it started. A failure to reach the model server is the opposite — it is thrown, so
 * the engine retries it and the document keeps whatever state it had.
 */
@Component
public class DocumentActivitiesImpl implements DocumentActivities {

    private final DocumentService documentService;
    private final EmbeddingApplicationService embedding;
    private final com.carddraft.search.IndexingSettings indexing;

    public DocumentActivitiesImpl(
            DocumentService documentService,
            EmbeddingApplicationService embedding,
            com.carddraft.search.IndexingSettings indexing) {
        this.documentService = documentService;
        this.embedding = embedding;
        this.indexing = indexing;
    }

    @Override
    public DocumentResult parse(String documentId) {
        return describe(documentService.process(documentId));
    }

    @Override
    public DocumentResult index(String documentId) {
        embedding.embedDocument(documentId, indexing.maxBatches());
        return describe(documentService.settle(documentId));
    }

    private DocumentResult describe(com.carddraft.repositories.DocumentsRepository.DocumentRow document) {
        return new DocumentResult(document.id(), document.state(), document.chunkCount(), document.rejectionReason());
    }
}
