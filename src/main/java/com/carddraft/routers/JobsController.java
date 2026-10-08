package com.carddraft.routers;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import com.carddraft.repositories.JobContextRepository;
import com.carddraft.documents.DocumentService;
import com.carddraft.repositories.DocumentsRepository;
import com.carddraft.repositories.JobsRepository;
import com.carddraft.services.GenerationSettings;
import com.carddraft.temporal.CardWorkflowService;
import com.carddraft.temporal.JobState;
import com.carddraft.temporal.WorkflowRequest;

/**
 * The asynchronous entry point.
 *
 * <p>202 rather than 201, because the resource does not exist yet: there is an accepted intention
 * to generate, and nothing more. The response is immediate, which is the entire point — a local
 * model answers in tens of seconds and a full pipeline in minutes, and an HTTP connection held
 * that long will be closed by something, retried by something, and paid for twice.
 */
@RestController
@RequestMapping("/jobs")
public class JobsController {

    /** The only two answers, and they travel to the workflow exactly as they are written here. */
    private static final String DECISION_APPROVE = "approve";
    private static final String DECISION_REJECT = "reject";

    private final JobsRepository jobs;
    private final CardWorkflowService workflows;
    private final DocumentService documents;
    private final GenerationSettings generationSettings;
    private final JobContextRepository contexts;
    private final ObjectMapper mapper;

    public JobsController(JobsRepository jobs, CardWorkflowService workflows, DocumentService documents,
                          GenerationSettings settings, JobContextRepository contexts,
                          ObjectMapper mapper) {
        this.jobs = jobs;
        this.workflows = workflows;
        this.documents = documents;
        this.generationSettings = settings;
        this.contexts = contexts;
        this.mapper = mapper;
    }

/**
     * @param supplierText content to work from on the facts branch; may be blank when documents
     *                     are supplied
     * @param documentIds  the documents a content manager picked. Non-empty switches the job onto
     *                     the retrieval branch, where the card is built from retrieved fragments and
     *                     every citation is verified against what the model was shown.
     * @param productHint  what the product is, used as the retrieval query. Optional because a
     *                     caller who selects documents and says nothing should still get a card —
     *                     the document names stand in for a missing hint.
     */
    public record SubmitJobRequest(String supplierText, java.util.List<String> documentIds,
                                   String productHint) {
    }

    /**
     * @param decision {@code approve} or {@code reject}; case and surrounding space are ignored,
     *                 because this is a person's answer rather than a machine's enum
     */
    public record DecisionRequest(String decision) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(
            @RequestBody SubmitJobRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        java.util.List<String> documentIds = request.documentIds() == null
                ? java.util.List.of() : request.documentIds();
        String hint = request.productHint() == null ? "" : request.productHint();

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("supplierText", request.supplierText());
        payload.put("documentIds", String.join(",", documentIds));
        payload.put("productHint", hint);

        if (documentIds.isEmpty() && (request.supplierText() == null || request.supplierText().isBlank())) {
            // Refused rather than accepted and failed later: with neither text nor documents there
            // is nothing to work from, and a job that runs three minutes to report that is worse
            // than a 400 at the door.
            throw new IllegalArgumentException(
                    "supply supplierText, documentIds, or both - a job needs something to work from");
        }

        Map<String, String> notIndexed = documentsNotIndexed(documentIds);
        if (!notIndexed.isEmpty()) {
            // Refused rather than accepted and failed later, for the same reason. A card built from
            // a document whose chunks do not exist yet is a card built from nothing, and it would
            // report itself as a confident refusal of the product rather than as a job that ran
            // before its input was ready. The state is returned per document so a caller can tell
            // work still in progress from a document that will never be searchable.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "these documents are not indexed; poll GET /documents/{id} until the "
                            + "state is indexed, and submit again",
                    "documents", notIndexed));
        }

        JobsRepository.JobCreation creation = jobs.createOrFindByIdempotencyKey(
                idempotencyKey, "pending", mapper.writeValueAsString(payload));

        JobsRepository.Job job = creation.job();

        // Start only when this request is the one that created the job. A repeated request must
        // not launch a second process for the same identifier: that is a second generation billed
        // once. The workflow-exists check covers the other gap �?" a job whose row landed but whose
        // process did not, because the engine was unreachable at the time.
        if (creation.created() || !workflows.exists(job.id())) {
            workflows.start(job.id(), new WorkflowRequest(
                    job.id(), request.supplierText(), generationSettings.maxRewriteRounds(),
                    hint, documentIds));
        }

        return ResponseEntity.accepted().body(Map.of("id", job.id(), "status", job.status()));
    }

    /**
     * The person's answer, and the only way a job ever leaves {@code awaiting_human}.
     *
     * <p>202 rather than 200: the decision has been taken and the card has not been written yet, so
     * the client polls {@code GET /jobs/{jobId}} for the outcome and the card. Answering with the
     * card here would mean holding the connection for as long as the write takes, and the write is a
     * database statement.
     *
     * <p>Idempotent on purpose. A person clicking twice, or a client retrying after a timeout, must
     * not receive an error for doing something that has already happened — so a job that has already
     * reached a terminal state is reported as it stands. A decision that is not one of the two is
     * refused with both of them named, since a caller guessing at the vocabulary has to be told it.
     */
    @PostMapping("/{jobId}/decision")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable String jobId,
                                                     @RequestBody DecisionRequest request) {
        JobsRepository.Job job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        String decision = normalise(request.decision());
        if (decision == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "decision must be approve or reject"));
        }

        if (JobState.fromWireName(job.status()).isTerminal()) {
            return ResponseEntity.ok(describe(job));
        }
        if (!workflows.exists(jobId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "no process is waiting on a decision for this job; its state is "
                            + job.status()));
        }

        if (DECISION_APPROVE.equals(decision)) {
            workflows.approve(jobId);
        } else {
            workflows.reject(jobId);
        }
        return ResponseEntity.accepted().body(Map.of("id", jobId, "status", job.status()));
    }

    /** The decision as the workflow spells it, or null when it is not one of the two. */
    private String normalise(String decision) {
        if (decision == null) {
            return null;
        }
        String spoken = decision.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (spoken) {
            case DECISION_APPROVE, DECISION_REJECT -> spoken;
            default -> null;
        };
    }

/**
     * The named documents that cannot be retrieved from yet, each with the state it is in.
     *
     * <p>An identifier nobody has is reported alongside the rest as {@code unknown}, rather than
     * filtered out: a caller that mistyped an identifier and one that named a document whose parse
     * is still running both submitted a job that cannot work, and only one of them can tell which
     * from this response.
     */
    private Map<String, String> documentsNotIndexed(java.util.List<String> documentIds) {
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

    /**
     * The card together with the sources it cites and what became of them.
     *
     * <p>The sources are only meaningful alongside the fragments they name, so the response carries
     * both. A list of labels alone would leave a caller to guess what "C3" was; a list of fragments
     * alone would not say which claim each supports.
     */
    @GetMapping("/{jobId}/sources")
    public ResponseEntity<Map<String, Object>> sources(@PathVariable String jobId) {
        return jobs.findById(jobId)
                .map(job -> ResponseEntity.ok().body(sourceBody(jobId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> sourceBody(String jobId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("context", contexts.load(jobId).chunks().stream()
                .map(chunk -> Map.of(
                        "reference", chunk.reference(),
                        "documentId", chunk.documentId(),
                        "page", chunk.page() == 0 ? "" : String.valueOf(chunk.page()),
                        "section", chunk.section() == null ? "" : chunk.section(),
                        "text", chunk.text()))
                .toList());
        return body;
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String jobId) {
        return jobs.findById(jobId)
                .map(this::describe)
                .map(body -> ResponseEntity.ok().body((Map<String, Object>) body))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> describe(JobsRepository.Job job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", job.id());
        body.put("status", job.status());
        body.put("attempts", job.attempts());
        if (job.detail() != null) {
            body.put("detail", job.detail());
        }
        if (job.result() != null) {
            body.put("result", job.result());
            confidenceOf(job.result()).ifPresent(confidence -> {
                body.put("confidence", confidence);
                body.put("awaitingHuman",
                        confidence < generationSettings.confidenceThreshold());
            });
        }
        if (job.error() != null) {
            body.put("error", job.error());
        }
        return body;
    }

    /**
     * The finished card's confidence, when the result is one.
     *
     * <p>Empty when the result is not a card at all, which is not an error: a failed job carries
     * an error instead of a result, and a result written by an older version may not parse.
     * Either way there is no confidence to report, so none is.
     */
    private java.util.Optional<Double> confidenceOf(String resultJson) {
        try {
            return java.util.Optional.ofNullable(
                    mapper.readValue(resultJson, com.carddraft.agents.ProductCard.class)
                            .confidence());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
