package com.carddraft.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import com.carddraft.agents.ProductCard;
import com.carddraft.documents.DocumentService;
import com.carddraft.repositories.DocumentsRepository;
import com.carddraft.repositories.JobContextRepository;
import com.carddraft.repositories.JobsRepository;
import com.carddraft.temporal.CardWorkflowService;
import com.carddraft.temporal.JobDecision;
import com.carddraft.temporal.JobState;
import com.carddraft.temporal.WorkflowRequest;

/**
 * The asynchronous job's business rules, without a web framework in sight.
 *
 * <p>Submission and decision are the two transitions a caller can drive, and both carry rules the
 * transport must not own: a job needs something to work from, its documents have to be searchable,
 * a repeated request must not buy a second process, and a job with no draft cannot be approved.
 * Keeping them here means they are testable by calling a method, and the controller is left with
 * routing, status codes and response shaping.
 *
 * <p>Refusals come back as outcomes rather than exceptions, because the status a refusal maps to is
 * an HTTP concern. The service says what happened; the controller says what that means.
 */
@Service
public class JobSubmissionService {

    private final JobsRepository jobs;
    private final CardWorkflowService workflows;
    private final DocumentService documents;
    private final GenerationSettings generationSettings;
    private final JobContextRepository contexts;
    private final ObjectMapper mapper;

    public JobSubmissionService(JobsRepository jobs, CardWorkflowService workflows,
                                DocumentService documents, GenerationSettings generationSettings,
                                JobContextRepository contexts, ObjectMapper mapper) {
        this.jobs = jobs;
        this.workflows = workflows;
        this.documents = documents;
        this.generationSettings = generationSettings;
        this.contexts = contexts;
        this.mapper = mapper;
    }

    /** The job a submission created or found, or the documents that made it impossible. */
    public sealed interface SubmitOutcome {
        record Accepted(JobsRepository.Job job) implements SubmitOutcome {
        }

        record DocumentsNotIndexed(Map<String, String> states) implements SubmitOutcome {
        }
    }

    /**
     * What a decision did to the job.
     *
     * <p>{@code Settled} is the idempotent case — the job already reached a terminal state — and
     * {@code NoDraft} and {@code ProcessGone} are the two refusals a running decision can meet.
     */
    public sealed interface DecisionOutcome {
        record Accepted(JobsRepository.Job job) implements DecisionOutcome {
        }

        record Settled(JobStatus status) implements DecisionOutcome {
        }

        record NoDraft(JobsRepository.Job job) implements DecisionOutcome {
        }

        record ProcessGone(JobsRepository.Job job) implements DecisionOutcome {
        }

        record UnknownJob() implements DecisionOutcome {
        }
    }

    /**
     * The card, how far it got, and whether a person still has to look at it.
     *
     * <p>{@code confidence} and {@code awaitingHuman} are absent together when the result is not a
     * card at all: a failed job carries an error instead, and a result written by an older version
     * may not parse. There is no confidence to report, so none is.
     */
    public record JobStatus(String id, String status, int attempts, String detail, String result,
                            Double confidence, Boolean awaitingHuman, String error) {
    }

    public record JobSources(String jobId, List<SourceFragment> context) {
    }

    public record SourceFragment(String reference, String documentId, String page, String section,
                                 String text) {
    }

    /**
     * Accepts the job, or names the documents that are not searchable yet.
     *
     * <p>The caller has already established that there is something to work from; this refuses when
     * that something is a document whose chunks are not retrievable, because a card built from a
     * document that cannot be searched is a card built from nothing.
     */
    public SubmitOutcome submit(String idempotencyKey, String supplierText, List<String> documentIds,
                                String productHint) {
        List<String> ids = documentIds == null ? List.of() : List.copyOf(documentIds);
        String hint = productHint == null ? "" : productHint;

        Map<String, String> notIndexed = documentsNotIndexed(ids);
        if (!notIndexed.isEmpty()) {
            return new SubmitOutcome.DocumentsNotIndexed(notIndexed);
        }

        JobsRepository.JobCreation creation = jobs.createOrFindByIdempotencyKey(
                idempotencyKey, "pending", payloadFor(supplierText, ids, hint));
        JobsRepository.Job job = creation.job();

        // Start only when this request is the one that created the job. A repeated request must not
        // launch a second process for the same identifier: that is a second generation billed once.
        // The workflow-exists check covers the other gap - a job whose row landed but whose process
        // did not, because the engine was unreachable at the time.
        if (creation.created() || !workflows.exists(job.id())) {
            workflows.start(job.id(), new WorkflowRequest(
                    job.id(), supplierText, generationSettings.maxRewriteRounds(), hint, ids));
        }
        return new SubmitOutcome.Accepted(job);
    }

    /**
     * The person's answer, and the only way a job ever leaves {@code awaiting_human}.
     *
     * <p>Idempotent on purpose: a job that already reached a terminal state is reported as it
     * stands rather than refused, because clicking twice is not an error.
     */
    public DecisionOutcome decide(String jobId, JobDecision decision) {
        JobsRepository.Job job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return new DecisionOutcome.UnknownJob();
        }

        if (JobState.fromWireName(job.status()).map(JobState::isTerminal).orElse(false)) {
            return new DecisionOutcome.Settled(describe(job));
        }
        // A job escalated before generation never produced a draft to approve: no generation
        // attempt was ever recorded and its draft stayed an empty object, and signalling approval
        // would make the workflow record a success with nothing to show. A job that attempted
        // generation carries its draft in the workflow and only writes it at the outcome, so a
        // null result alone is not the signal - zero attempts is.
        if (decision == JobDecision.APPROVE && job.attempts() == 0 && !hasDraft(job)) {
            return new DecisionOutcome.NoDraft(job);
        }
        if (!workflows.exists(jobId)) {
            return new DecisionOutcome.ProcessGone(job);
        }

        if (decision == JobDecision.APPROVE) {
            workflows.approve(jobId);
        } else {
            workflows.reject(jobId);
        }
        return new DecisionOutcome.Accepted(job);
    }

    public Optional<JobStatus> status(String jobId) {
        return jobs.findById(jobId).map(this::describe);
    }

    public Optional<JobSources> sources(String jobId) {
        return jobs.findById(jobId).map(job -> new JobSources(jobId,
                contexts.load(jobId).chunks().stream().map(this::fragment).toList()));
    }

    private JobStatus describe(JobsRepository.Job job) {
        Optional<Double> confidence = confidenceOf(job.result());
        return new JobStatus(job.id(), job.status(), job.attempts(), job.detail(), job.result(),
                confidence.orElse(null),
                confidence.map(value -> value < generationSettings.confidenceThreshold()).orElse(null),
                job.error());
    }

    private SourceFragment fragment(com.carddraft.context.ContextChunk chunk) {
        return new SourceFragment(chunk.reference(), chunk.documentId(),
                chunk.page() == 0 ? "" : String.valueOf(chunk.page()),
                chunk.section() == null ? "" : chunk.section(), chunk.text());
    }

    private String payloadFor(String supplierText, List<String> documentIds, String productHint) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("supplierText", supplierText);
        payload.put("documentIds", String.join(",", documentIds));
        payload.put("productHint", productHint);
        return mapper.writeValueAsString(payload);
    }

    private Map<String, String> documentsNotIndexed(List<String> documentIds) {
        Map<String, String> states = new LinkedHashMap<>();
        for (String documentId : documentIds) {
            String state = documents.find(documentId)
                    .map(DocumentsRepository.DocumentRow::state)
                    .orElse("unknown");
            if (!"indexed".equals(state)) {
                states.put(documentId, state);
            }
        }
        return states;
    }

    /** Whether the job already carries a draft to approve. */
    private boolean hasDraft(JobsRepository.Job job) {
        String result = job.result();
        if (result == null) {
            return false;
        }
        String trimmed = result.strip();
        return !trimmed.isEmpty() && !"{}".equals(trimmed);
    }

    /**
     * The finished card's confidence, when the result is one.
     *
     * <p>Empty when the result is not a card at all, which is not an error: a failed job carries an
     * error instead of a result, and a result written by an older version may not parse.
     */
    private Optional<Double> confidenceOf(String resultJson) {
        if (resultJson == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    mapper.readValue(resultJson, ProductCard.class).confidence());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
