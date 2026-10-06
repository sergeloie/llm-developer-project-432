package com.carddraft.search;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddraft.embeddings.EmbeddingModel;
import com.carddraft.repositories.ChunkSearchRepository;

/**
 * Turns parsed chunks into searchable ones.
 *
 * <p>Separate from parsing, and deliberately so. Parsing succeeds on a machine with no model
 * running, and text that cannot be embedded is still text a person can read and a word search can
 * find. Making embedding a step inside parsing would mean a stopped model server turns every
 * upload into a failure.
 *
 * <p>Batched because the model is far slower per text than per call, and because the arithmetic
 * works out: a single chunk of one call at a few hundred milliseconds each is minutes over a corpus,
 * and the same corpus in batches of sixteen is seconds. The batch size is the model's context, so
 * it is configuration rather than a constant tuned to one machine.
 */
@Service
public class EmbeddingApplicationService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingApplicationService.class);

    private final EmbeddingModel model;
    private final ChunkSearchRepository chunks;
    private final IndexingSettings settings;

    public EmbeddingApplicationService(EmbeddingModel model, ChunkSearchRepository chunks,
                                       IndexingSettings settings) {
        this.model = model;
        this.chunks = chunks;
        this.settings = settings;
    }

/**
     * Embeds up to {@code batchSize} chunks, writes their vectors, and reports how many remain.
     *
     * <p>Returning the remainder rather than looping is what lets the backfill command report
     * progress and lets a caller decide whether to continue.
     */
    public int embedNextBatch() {
        return embedNextBatch(chunks.chunksWithoutVectors(settings.batchSize()));
    }

    /** The same, restricted to one document, so an upload embeds what it just parsed. */
    public int embedNextBatch(String documentId) {
        return embedNextBatch(chunks.chunksWithoutVectors(documentId, settings.batchSize()));
    }

    private int embedNextBatch(List<ChunkSearchRepository.ChunkToEmbed> pending) {
        if (pending.isEmpty()) {
            return 0;
        }

        List<EmbeddingModel.Document> inputs = new ArrayList<>(pending.size());
        for (ChunkSearchRepository.ChunkToEmbed chunk : pending) {
            inputs.add(new EmbeddingModel.Document(chunk.text(), chunk.section()));
        }

        List<List<Double>> vectors = model.embedDocuments(inputs);

        List<ChunkSearchRepository.ChunkVector> rows = new ArrayList<>(pending.size());
        for (int i = 0; i < pending.size(); i++) {
            rows.add(new ChunkSearchRepository.ChunkVector(pending.get(i).id(), vectors.get(i)));
        }
        chunks.writeVectors(rows);

        log.info("embedded {} chunks", rows.size());
        return chunks.countWithoutVectors();
    }

    /**
     * Embeds one document's chunks and reports how many vectors it wrote.
     *
     * <p>Bounded rather than open-ended, for the reason {@link #embedAll} gives: a document whose
     * parse kept writing chunks would otherwise sit here until the process died. What is left
     * unembedded is the caller's to notice — it is why this returns a count rather than a boolean.
     */
    public int embedDocument(String documentId, int maxBatches) {
        int embedded = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<ChunkSearchRepository.ChunkToEmbed> pending =
                    chunks.chunksWithoutVectors(documentId, settings.batchSize());
            if (pending.isEmpty()) {
                return embedded;
            }
            embedded += pending.size();
            embedNextBatch(pending);
        }
        log.warn("document {} still has chunks without vectors after {} batches",
                documentId, maxBatches);
        return embedded;
    }

    /**
     * Runs until nothing is left, and returns the number of chunks embedded.
     *
     * <p>Counting chunks rather than batches, and counting the batch that finished the job. The
     * count is what a person reads to decide whether the sweep did anything, so a version that
     * reported batches would claim to have embedded sixteen chunks when it had embedded nine, and
     * zero when it had embedded everything — which is the number this once reported, having
     * returned before adding the last batch.
     */
    public int embedAll(int maxBatches) {
        int embedded = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<ChunkSearchRepository.ChunkToEmbed> pending =
                    chunks.chunksWithoutVectors(settings.batchSize());
            if (pending.isEmpty()) {
                return embedded;
            }
            embedded += pending.size();
            embedNextBatch(pending);
        }
        log.warn("stopped after {} batches with chunks still unembedded", maxBatches);
        return embedded;
    }

    public int pendingCount() {
        return chunks.countWithoutVectors();
    }

}