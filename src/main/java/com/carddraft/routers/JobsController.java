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
    private final ObjectMapper mapper;

    public JobsController(JobsRepository jobs, CardWorkflowService workflows,
                          GenerationSettings settings, ObjectMapper mapper) {
        this.jobs = jobs;
        this.workflows = workflows;
        this.generationSettings = settings;
        this.mapper = mapper;
    }

    public record SubmitJobRequest(String supplierText) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(
            @RequestBody SubmitJobRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("supplierText", request.supplierText());

        JobsRepository.JobCreation creation = jobs.createOrFindByIdempotencyKey(
                idempotencyKey, "pending", mapper.writeValueAsString(payload));

        JobsRepository.Job job = creation.job();

        // Start only when this request is the one that created the job. A repeated request must
        // not launch a second process for the same identifier: that is a second generation billed
        // once. The workflow-exists check covers the other gap — a job whose row landed but whose
        // process did not, because the engine was unreachable at the time.
        if (creation.created() || !workflows.exists(job.id())) {
            workflows.start(job.id(), new WorkflowRequest(
                    job.id(), request.supplierText(), generationSettings.maxRewriteRounds()));
        }

        return ResponseEntity.accepted().body(Map.of("id", job.id(), "status", job.status()));
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
