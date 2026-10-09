package com.carddraft.temporal;

import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import com.carddraft.agents.ProductCard;
import com.carddraft.agents.ReviewIssue;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.Verdict;
import com.carddraft.context.AssembledContext;
import com.carddraft.context.CitationVerifier;
import com.carddraft.context.ContextAssembler;
import com.carddraft.repositories.JobContextRepository;
import com.carddraft.llm.JobLogContext;
import com.carddraft.llm.LlmClient;
import com.carddraft.repositories.ChunkSearchRepository;
import com.carddraft.search.SearchService;
import com.carddraft.trust.Finding;
import com.carddraft.trust.TrustSettings;
import com.carddraft.trust.TrustService;
import com.carddraft.repositories.JobsRepository;

/**
 * The steps, as ordinary Java.
 *
 * <p>Everything here runs outside the workflow's constraints: it may read configuration, block on
 * the model, touch the database and use a clock. The workflow gets an interface and a return
 * value; it never learns any of this happened.
 *
 * <p>This is the only place that bridges the two worlds, and it is thin on purpose. The logic is
 * in the services below it �?" the activities decide what to call, not how it behaves.
 *
 * <p>Every model-calling step runs inside {@link JobLogContext}, which is what puts the job on the
 * cost record and into the log lines. Done here rather than inside the client because the client is
 * given no job: it is the step that knows which job it is working on, and a client that took one
 * would have to be told at every call site — the arrangement that eventually gets forgotten.
 */
@Component
public class CardActivitiesImpl implements CardActivities {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CardActivitiesImpl.class);

    private final LlmClient llmClient;
    private final JobsRepository jobs;
    private final ContextAssembler assembler;
    private final SearchService search;
    private final JobContextRepository contexts;
    private final CitationVerifier verifier;
    private final TrustService trust;
    private final TrustSettings trustSettings;
    private final ObjectMapper mapper;

    public CardActivitiesImpl(LlmClient llmClient, JobsRepository jobs, ContextAssembler assembler,
                              SearchService search, JobContextRepository contexts,
                              CitationVerifier verifier, TrustService trust,
                              TrustSettings trustSettings, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.jobs = jobs;
        this.assembler = assembler;
        this.search = search;
        this.contexts = contexts;
        this.verifier = verifier;
        this.trust = trust;
        this.trustSettings = trustSettings;
        this.mapper = mapper;
    }

    @Override
    public String extractFacts(String jobId, String supplierText) {
        return JobLogContext.withJob(jobId, () -> toJson(llmClient.extractFacts(supplierText)));
    }

    @Override
    public String generateDraft(String jobId, String factsJson, List<ReviewIssue> issues) {
        return JobLogContext.withJob(jobId, () -> toJson(
                llmClient.draftCard(fromJson(factsJson, SupplierFacts.class), issues)));
    }

    @Override
    public ReviewOutcome reviewDraft(String jobId, String factsJson, String draftJson) {
        return JobLogContext.withJob(jobId, () -> {
            CritiqueReport report = llmClient.reviewDraft(
                    fromJson(factsJson, SupplierFacts.class),
                    fromJson(draftJson, ProductCard.class));
            return new ReviewOutcome(report != null && report.verdict() == Verdict.APPROVE, report == null ? List.of() : report.issues());
        });
    }

    /**
     * Searches, screens, assembles, and retains.
 *
     * <p>Screening sits between retrieval and assembly, which is the only place it can sit. A value
     * has to be masked before the prompt is built and before the context is written down, and a
     * suspicious fragment has to be gone before the model can read it — neither is possible once the
     * context exists.
     *
     * <p>The query is sent unmasked on purpose. It is the caller's own words or a document's name,
     * and masking it would change the retrieval without protecting anything: the values that matter
     * are in the fragments being returned, not in the question.
     */
    @Override
    public RetrievedContext retrieveAndAssemble(String jobId, String productHint, List<String> documentIds) {
        return JobLogContext.withJob(jobId, () -> {
            String query = productHint == null || productHint.isBlank() ? filenamesOf(documentIds) : productHint;
        var hits = search.search(query, new ChunkSearchRepository.Filter(documentIds, null),
                SearchService.Mode.HYBRID);

        AssembledContext assembled = assembler.assemble(jobId, hits);
        TrustService.Screened screened = trust.screen(assembled.chunks(), trustSettings.maxSuspiciousChunks());
        screened.excluded().forEach(reference -> log.info("llm_fragment_excluded job={} reference={}",
                jobId, reference));

        AssembledContext retained = new AssembledContext(jobId, screened.chunks(),
                assembled.droppedAsDuplicate(), assembled.droppedOverBudget());
        contexts.save(retained);

        // Nothing safe to generate from is its own outcome, distinct from "few were dropped".
        // An empty context would send the model off with no sources, and the card that comes
        // back cites nothing it was shown — so the job waits for a person with the reason
        // rather than failing a generation that never had anything to work from.
        if (screened.chunks().isEmpty()) {
            String reason = "all " + assembled.chunks().size()
                    + " retrieved fragments were excluded as suspicious, so there is nothing "
                    + "safe to generate from";
            log.warn("llm_context_empty job={} excluded={}", jobId, screened.excluded());
            return new RetrievedContext("", screened.excluded(), screened.masked(), true, reason);
        }

        return new RetrievedContext(screened.render(), screened.excluded(), screened.masked(),
                screened.escalated(), screened.reason());
        });
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

    /**
     * Generates from the screened context, then filters what came back.
     *
     * <p>The model can invent a phone number that appears in no fragment, so screening the input
     * is not enough. Personal data is masked in the returned draft and every finding is logged
     * by kind and count, never by value. The mask replaces inside string values, so the masked
     * draft is still the JSON the workflow stores.
     */
    @Override
    public String generateFromContext(String jobId, String contextText, List<ReviewIssue> issues) {
        return JobLogContext.withJob(jobId, () -> {
            String draftJson = toJson(llmClient.draftCardFromContext(contextText, issues));
            Finding.Report report = trust.filterOutput(draftJson);
            if (report.isClean()) {
                return draftJson;
            }
            log.warn("llm_output_filtered job={} findings={}", jobId, report.summary());
            boolean personal = report.findings().stream().anyMatch(Finding::isPersonal);
            return personal ? report.maskedText() : draftJson;
        });
    }

    /**
     * Verifies against the retained context, and reports which kind of failure it was.
     *
     * <p>The corpus-wide existence check distinguishes a citation to a real fragment the model was
     * never shown from a label that means nothing at all. "Corpus" here is every retained context:
     * labels are positional, so a label another job was shown names a real allocation. They are
     * separated because they lead to different conversations: one says the context was wrong, the
     * other says the model invented something, and sending both down the same rework path teaches
     * nothing.
     */
    @Override
    public ReviewOutcome reviewCardAgainstContext(String jobId, String contextText, String draftJson) {
        return JobLogContext.withJob(jobId, () -> {
            CritiqueReport report = llmClient.reviewCardAgainstContext(
                    contextText, fromJson(draftJson, ProductCard.class));
            return new ReviewOutcome(report != null && report.verdict() == Verdict.APPROVE,
                    report == null ? List.of() : report.issues());
        });
    }

    @Override
    public CitationCheck checkCitations(String jobId, String draftJson) {
        ProductCard card = fromJson(draftJson, ProductCard.class);
        AssembledContext context = contexts.load(jobId);

        CitationVerifier.Verdict verdict = verifier.verifyAgainst(context,
                card.characteristics().keySet(), card.sources(),
                contexts::existsReferenceInAnyContext);

        return new CitationCheck(verdict.isClean(), verdict.messages(),
                verdict.fabricated().size(), 0);
    }

    @Override
    public void writeStatus(String jobId, String state, String detail) {
        jobs.setStatus(jobId, state, detail);
    }

    @Override
    public void countAttempt(String jobId, int attempt) {
        jobs.setAttempts(jobId, attempt);
    }

    @Override
    public void recordOutcome(String jobId, String status, String draftJson) {
        jobs.complete(jobId, status, draftJson);
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
