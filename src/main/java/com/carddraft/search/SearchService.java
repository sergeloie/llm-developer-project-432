package com.carddraft.search;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carddraft.embeddings.EmbeddingModel;
import com.carddraft.repositories.ChunkSearchRepository;
import com.carddraft.repositories.ChunkSearchRepository.Filter;
import com.carddraft.repositories.ChunkSearchRepository.Hit;

/**
 * Retrieval as the rest of the system sees it: a question in, cited fragments out.
 *
 * <p>Hybrid is the default and not an optimisation. Measured on the supplied documents, a question
 * about a passport characteristic finds the right fragment at 0.50 while an article-number query
 * finds the same fragment at 0.16 — vector search sees "KTL-1700" as a string resembling other
 * strings. Word search inverts the problem: it matches the article exactly and misses every
 * paraphrase. Fusing positions rather than scores avoids having to reconcile two incommensurable
 * quantities, and lets a fragment found by both outrank one found by a single mode, which is the
 * property that makes the combination better than either half.
 */
@Service
public class SearchService {

    public enum Mode {
        VECTOR,
        WORD,
        HYBRID
    }

    private final EmbeddingModel embeddingModel;
    private final ChunkSearchRepository chunks;
    private final SearchSettings settings;

    public SearchService(EmbeddingModel embeddingModel, ChunkSearchRepository chunks, SearchSettings settings) {
        this.embeddingModel = embeddingModel;
        this.chunks = chunks;
        this.settings = settings;
    }

    /** Hybrid retrieval, unfiltered — the path a product card question takes. */
    public List<Hit> search(String question) {
        return search(question, Filter.all(), Mode.HYBRID);
    }

    public List<Hit> search(String question, Filter filter, Mode mode) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("a retrieval query needs some text; an empty one matches everything");
        }
        return switch (mode) {
            case VECTOR ->
                chunks.searchByVector(
                        embeddingModel.embedQuery(question), filter, settings.limit(), settings.maxVectorDistance());
            case WORD -> chunks.searchByText(question, filter, settings.limit());
            case HYBRID ->
                chunks.searchHybrid(
                        embeddingModel.embedQuery(question),
                        question,
                        filter,
                        settings.limit(),
                        settings.maxVectorDistance(),
                        settings.perListLimit(),
                        settings.rrfK());
        };
    }

    public SearchSettings settings() {
        return settings;
    }
}
