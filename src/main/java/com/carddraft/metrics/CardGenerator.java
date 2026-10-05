package com.carddraft.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carddraft.agents.ProductCard;
import com.carddraft.documents.DocumentService;
import com.carddraft.context.AssembledContext;
import com.carddraft.context.ContextAssembler;
import com.carddraft.llm.LlmClient;
import com.carddraft.repositories.ChunkSearchRepository;
import com.carddraft.repositories.DocumentsRepository;
import com.carddraft.search.EmbeddingApplicationService;
import com.carddraft.search.SearchService;

/**
 * Produces one card per document, through the whole path the service actually takes.
 *
 * <p>Register, parse, chunk, embed, retrieve, assemble, generate — in that order and with no
 * shortcuts. A harness that generated from the reference characteristics would measure its prompts
 * against a perfect extraction, which is exactly the number that never moves when the extraction is
 * what broke. Measuring the real path means a change anywhere in it shows up here.
 *
 * <p>It reuses the service's own processing rather than a parallel copy. A metrics harness with its
 * own parsing would drift from the one the service uses, and the drift would appear as a quality
 * difference that no change to any prompt explains.
 *
 * <p>The card from the last call is kept because the harness needs both halves of the outcome: the
 * text to judge and the context to judge it against. Returning them from one call removes the
 * possibility of a second generation between them.
 */
@Component
public class CardGenerator {

    private final DocumentService documents;
    private final EmbeddingApplicationService embedding;
    private final SearchService search;
    private final ContextAssembler assembler;
    private final LlmClient llm;

    private ProductCard lastCard;

    public CardGenerator(DocumentService documents, EmbeddingApplicationService embedding,
                         SearchService search, ContextAssembler assembler, LlmClient llm) {
        this.documents = documents;
        this.embedding = embedding;
        this.search = search;
        this.assembler = assembler;
        this.llm = llm;
    }

    /**
     * The context the card was generated from, or null when the document produced nothing.
     *
     * <p>Null rather than an empty context: "this document yielded no usable fragments" and "this
     * document yielded fragments the model would not use" are different failures, and collapsing
     * them would let a parsing regression hide behind a generation one.
     */
    public AssembledContext generate(String filename) {
        byte[] content = read(filename);

        DocumentsRepository.DocumentRow registered = documents.register(filename, content);
        DocumentsRepository.DocumentRow processed =
                documents.process(registered.id(), filename, content);

        // The service's own backfill, which selects chunks that have text and no vector on a
        // document that reached 'indexed'. Reused rather than replaced so the harness embeds
        // exactly what production would embed.
        embedding.embedAll(64);

        var hits = search.search(filename,
                new ChunkSearchRepository.Filter(List.of(processed.id()), null),
                SearchService.Mode.HYBRID);

        AssembledContext context = assembler.assemble("metrics-" + filename, hits);
        lastCard = llm.draftCardFromContext(context.render(), List.of());
        return context;
    }

    public ProductCard lastCard() {
        return lastCard;
    }

    private byte[] read(String filename) {
        try {
            return Files.readAllBytes(Path.of("data", filename));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read data/" + filename, e);
        }
    }
}