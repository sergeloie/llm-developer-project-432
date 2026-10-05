package com.carddraft.temporal;

import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.Verdict;
import com.carddraft.context.AssembledContext;
import com.carddraft.context.CitationVerifier;
import com.carddraft.context.ContextAssembler;
import com.carddraft.context.JobContextRepository;
import com.carddraft.llm.LlmClient;
import com.carddraft.repositories.ChunkSearchRepository;
import com.carddraft.search.SearchService;
import com.carddraft.repositories.JobsRepository;

/**
 * The steps, as ordinary Java.
 *
 * <p>Everything here runs outside the workflow's constraints: it may read configuration, block on
 * the model, touch the database and use a clock. The workflow gets an interface and a return
 * value; it never learns any of this happened.
 *
 * <p>This is the only place that bridges the two worlds, and it is thin on purpose. The logic is
 * in the services below it — the activities decide what to call, not how it behaves.
 */
@Component
public class CardActivitiesImpl implements CardActivities {

    private final LlmClient llmClient;
    private final JobsRepository jobs;
    private final ContextAssembler assembler;
    private final SearchService search;
    private final JobContextRepository contexts;
    private final CitationVerifier verifier;
    private final ObjectMapper mapper;

    public CardActivitiesImpl(LlmClient llmClient, JobsRepository jobs, ContextAssembler assembler,
                              SearchService search, JobContextRepository contexts,
                              CitationVerifier verifier, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.jobs = jobs;
        this.assembler = assembler;
        this.search = search;
        this.contexts = contexts;
        this.verifier = verifier;
        this.mapper = mapper;
    }

    @Override
    public String extractFacts(String jobId, String supplierText) {
        return toJson(llmClient.extractFacts(supplierText));
    }

    @Override
    public String generateDraft(String jobId, String factsJson, List<String> issues) {
        return toJson(llmClient.draftCard(fromJson(factsJson, SupplierFacts.class), issues));
    }

    @Override
    public ReviewOutcome reviewDraft(String jobId, String factsJson, String draftJson) {
        CritiqueReport report = llmClient.reviewDraft(
                fromJson(factsJson, SupplierFacts.class),
                fromJson(draftJson, ProductCard.class));
        return new ReviewOutcome(report.verdict() == Verdict.APPROVE, report.issues());
    }

    /**
     * Searches with the hint, falls back to what the documents are called.
     *
     * <p>A content manager who picks three documents and types nothing should still get a card.
     * Retrieval needs a query, so when the hint is blank the document filenames are concatenated:
     * approximate, and better than refusing, because the alternative is an empty context and a card
     * that says nothing.
     */
    @Override
    public String retrieveAndAssemble(String jobId, String productHint, List<String> documentIds) {
        String query = productHint == null || productHint.isBlank() ? filenamesOf(documentIds) : productHint;
        var hits = search.search(query, new ChunkSearchRepository.Filter(documentIds, null),
                SearchService.Mode.HYBRID);

        AssembledContext context = assembler.assemble(jobId, hits);
        contexts.save(context);
        return context.render();
    }

    private String filenamesOf(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return "";
        }
        StringBuilder names = new StringBuilder();
        for (String documentId : documentIds) {
            names.append(documentId).append(' ');
        }
        return names.toString().strip();
    }

    @Override
    public String generateFromContext(String jobId, String contextText, List<String> issues) {
        return toJson(llmClient.draftCardFromContext(contextText, issues));
    }

    /**
     * Verifies against the retained context, and reports which kind of failure it was.
     *
     * <p>The corpus-wide existence check distinguishes a citation to a real fragment the model was
     * never shown from a label that means nothing at all. They are separated because they lead to
     * different conversations: one says the context was wrong, the other says the model invented
     * something, and sending both down the same rework path teaches nothing.
     */
    @Override
    public ReviewOutcome reviewCardAgainstContext(String jobId, String contextText, String draftJson) {
        CritiqueReport report = llmClient.reviewCardAgainstContext(
                contextText, fromJson(draftJson, ProductCard.class));
        return new ReviewOutcome(report.verdict() == Verdict.APPROVE, report.issues());
    }

    @Override
    public CitationCheck checkCitations(String jobId, String draftJson) {
        ProductCard card = fromJson(draftJson, ProductCard.class);
        AssembledContext context = contexts.load(jobId);

        CitationVerifier.Verdict verdict = verifier.verifyAgainst(context, card.sources(),
                reference -> {
                    var chunkId = contexts.chunkIdForReference(jobId, reference);
                    return chunkId.isPresent();
                });

        return new CitationCheck(verdict.isClean(), verdict.messages(),
                verdict.fabricated().size(), 0);
    }

    @Override
    public void writeStatus(String jobId, String state, String detail) {
        jobs.setStatus(jobId, state, detail);
    }

    @Override
    public void countAttempt(String jobId) {
        jobs.recordAttempt(jobId);
    }

    @Override
    public void recordFailure(String jobId, String error) {
        jobs.fail(jobId, error);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("could not read " + type.getSimpleName(), e);
        }
    }
}
