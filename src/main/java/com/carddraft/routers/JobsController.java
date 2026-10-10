package com.carddraft.routers;

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

import jakarta.validation.Valid;

import com.carddraft.repositories.JobsRepository;
import com.carddraft.services.JobSubmissionService;
import com.carddraft.services.JobSubmissionService.DecisionOutcome;
import com.carddraft.services.JobSubmissionService.JobStatus;
import com.carddraft.services.JobSubmissionService.SubmitOutcome;
import com.carddraft.temporal.JobDecision;

/**
 * The asynchronous entry point.
 *
 * <p>202 rather than 201, because the resource does not exist yet: there is an accepted intention
 * to generate, and nothing more. The response is immediate, which is the entire point — a local
 * model answers in tens of seconds and a full pipeline in minutes, and an HTTP connection held
 * that long will be closed by something, retried by something, and paid for twice.
 *
 * <p>The rules behind a submission and a decision live in {@link JobSubmissionService}. What stays
 * here is the transport: routing, which status a refusal maps to, and the shape of the answer.
 */
@RestController
@RequestMapping("/jobs")
public class JobsController {

    private final JobSubmissionService submissions;

    public JobsController(JobSubmissionService submissions) {
        this.submissions = submissions;
    }

    @PostMapping
    public ResponseEntity<JobAcceptedResponse> submit(
            @Valid @RequestBody SubmitJobRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return switch (submissions.submit(idempotencyKey, request.supplierText(),
                request.documentIds(), request.productHint())) {
            case SubmitOutcome.DocumentsNotIndexed(Map<String, String> states) ->
                    throw new ApiRefusalException(HttpStatus.CONFLICT,
                            "these documents are not indexed; poll GET /documents/{id} until the "
                                    + "state is indexed, and submit again",
                            Map.of("documents", states));
            case SubmitOutcome.Accepted(JobsRepository.Job job) ->
                    ResponseEntity.accepted().body(JobAcceptedResponse.from(job));
        };
    }

    /**
     * The person's answer, and the only way a job ever leaves {@code awaiting_human}.
     *
     * <p>202 rather than 200: the decision has been taken and the card has not been written yet, so
     * the client polls {@code GET /jobs/{jobId}} for the outcome and the card. Idempotent on
     * purpose: a job that has already reached a terminal state is reported as it stands.
     */
    @PostMapping("/{jobId}/decision")
    public ResponseEntity<?> decide(@PathVariable String jobId,
                                    @Valid @RequestBody DecisionRequest request) {
        JobDecision decision = JobDecision.fromWireName(request.decision()).orElseThrow();
        return switch (submissions.decide(jobId, decision)) {
            case DecisionOutcome.UnknownJob ignored -> ResponseEntity.notFound().build();
            case DecisionOutcome.Settled(JobStatus status) ->
                    ResponseEntity.ok(JobStatusResponse.from(status));
            // A job escalated before generation never produced a draft to approve: approving it
            // would record a success with nothing to show, so it is refused before the signal
            // reaches the workflow.
            case DecisionOutcome.NoDraft ignored ->
                    throw new ApiRefusalException(HttpStatus.CONFLICT,
                            "this job has no draft card to approve; it was escalated before generation, "
                                    + "and approving it would record a success with nothing to show");
            case DecisionOutcome.ProcessGone(JobsRepository.Job job) ->
                    throw new WorkflowNotFoundException(job.status());
            case DecisionOutcome.Accepted(JobsRepository.Job job) ->
                    ResponseEntity.accepted().body(JobAcceptedResponse.from(job));
        };
    }

    @GetMapping("/{jobId}/sources")
    public ResponseEntity<JobSourcesResponse> sources(@PathVariable String jobId) {
        return submissions.sources(jobId)
                .map(JobSourcesResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> status(@PathVariable String jobId) {
        return submissions.status(jobId)
                .map(JobStatusResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
