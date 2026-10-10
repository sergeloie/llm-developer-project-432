package com.carddraft.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Embeds whatever is left over, on startup.
 *
 * <p>Opt-in through {@code card.embedding.backfill-on-start}, because doing it by default would
 * mean every restart silently talks to a model server that may not be running — and an application
 * that cannot start without a GPU is a different product from one that can.
 *
 * <p>Driven from startup rather than a shell script because the model and the database have to be
 * configured the same way whether a person or a script runs it, and a second configuration path is
 * a second set of ways to be wrong.
 */
@Component
@ConditionalOnProperty(name = "card.embedding.backfill-on-start", havingValue = "true")
public class EmbeddingBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillRunner.class);

    private final EmbeddingApplicationService embedding;
    private final com.carddraft.repositories.DocumentsRepository documents;
    private final IndexingSettings indexing;

    public EmbeddingBackfillRunner(
            EmbeddingApplicationService embedding,
            com.carddraft.repositories.DocumentsRepository documents,
            IndexingSettings indexing) {
        this.embedding = embedding;
        this.documents = documents;
        this.indexing = indexing;
    }

    @Override
    public void run(ApplicationArguments args) {
        int pending = embedding.pendingCount();
        if (pending == 0) {
            log.info("backfill: nothing to embed");
            return;
        }
        log.info("backfill: {} chunks have no vector yet", pending);
        int embedded = embedding.embedAll(indexing.maxBatches());

        // Settling afterwards is what makes this a recovery rather than only a repair. A document
        // whose embedding failed keeps its chunks and stays at 'parsing', and without this the
        // sweep would fill in its vectors and leave it still claiming to be mid-parse — which reads
        // as a hang rather than as the success it now is.
        int settled = documents.settleIndexedDocuments();
        log.info(
                "backfill: embedded {} chunks, {} still pending, {} documents now searchable",
                embedded,
                embedding.pendingCount(),
                settled);
    }
}
