package com.carddraft.routers;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import com.carddraft.context.JobContextRepository;
import com.carddraft.repositories.JobsRepository;
import com.carddraft.services.GenerationSettings;
import com.carddraft.temporal.CardWorkflowService;
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

    private final JobsRepository jobs;
    private final CardWorkflowService workflows;
    private final GenerationSettings generationSettings;
    private final JobContextRepository contexts;
    private final ObjectMapper mapper;

    public JobsController(JobsRepository jobs, CardWorkflowService workflows,
                          GenerationSettings settings, JobContextRepository contexts,
                          ObjectMapper mapper) {
        this.jobs = jobs;
        this.workflows = workflows;
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
        }
        if (job.error() != null) {
            body.put("error", job.error());
        }
        return body;
    }
}
