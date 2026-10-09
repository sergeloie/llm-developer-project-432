package com.carddraft.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.carddraft.context.AssembledContext;
import com.carddraft.context.ContextChunk;
import com.carddraft.documents.DocumentService;
import com.carddraft.repositories.DocumentsRepository;
import com.carddraft.repositories.JobContextRepository;
import com.carddraft.repositories.JobsRepository;
import com.carddraft.temporal.CardWorkflowService;
import com.carddraft.temporal.JobDecision;
import com.carddraft.temporal.WorkflowRequest;

import tools.jackson.databind.ObjectMapper;

/**
 * The job orchestration, proven without HTTP.
 *
 * <p>This is the seam the controller now delegates to: the submission and decision rules, the
 * state read back, and the confidence contract. A web request adds routing and status codes
 * around it, which is what {@code JobsControllerTest} covers.
 */
class JobSubmissionServiceTest {

    private static final String CARD_JSON = """
            {"title":"Blender","description":"A blender.","characteristics":{},"benefits":[],
             "missingFields":[],"confidence":0.4,"sources":{}}
            """;

    private final JobsRepository jobs = mock(JobsRepository.class);
    private final CardWorkflowService workflows = mock(CardWorkflowService.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final JobContextRepository contexts = mock(JobContextRepository.class);
    private final GenerationSettings settings = new GenerationSettings(3, 0.7);
    private final JobSubmissionService service = new JobSubmissionService(
            jobs, workflows, documents, settings, contexts, new ObjectMapper());

    @Test
    void submitStartsAWorkflowOnceAndReturnsTheCreatedJob() {
        JobsRepository.Job created = job("job-1", "pending", 0, null);
        given(jobs.createOrFindByIdempotencyKey(eq("key"), eq("pending"), anyString()))
                .willReturn(new JobsRepository.JobCreation(created, true));

        JobSubmissionService.SubmitOutcome outcome =
                service.submit("key", "a blender", List.of(), null);

        assertThat(outcome).isInstanceOf(JobSubmissionService.SubmitOutcome.Accepted.class);
        assertThat(((JobSubmissionService.SubmitOutcome.Accepted) outcome).job().id()).isEqualTo("job-1");
        verify(workflows).start("job-1",
                new WorkflowRequest("job-1", "a blender", 3, "", List.of()));
    }

    @Test
    void submitRefusesADocumentThatIsNotIndexed() {
        given(documents.find("doc-1")).willReturn(Optional.of(document("doc-1", "parsing")));

        JobSubmissionService.SubmitOutcome outcome =
                service.submit(null, null, List.of("doc-1"), null);

        assertThat(outcome).isInstanceOf(JobSubmissionService.SubmitOutcome.DocumentsNotIndexed.class);
        assertThat(((JobSubmissionService.SubmitOutcome.DocumentsNotIndexed) outcome).states())
                .containsEntry("doc-1", "parsing");
        verify(jobs, never()).createOrFindByIdempotencyKey(any(), any(), any());
    }

    @Test
    void submitReportsAnIdentifierNobodyHasAsUnknown() {
        given(documents.find("ghost")).willReturn(Optional.empty());

        JobSubmissionService.SubmitOutcome outcome =
                service.submit(null, "a blender", List.of("ghost"), null);

        assertThat(((JobSubmissionService.SubmitOutcome.DocumentsNotIndexed) outcome).states())
                .containsEntry("ghost", "unknown");
    }

    @Test
    void submitDoesNotStartASecondWorkflowForARepeatedRequest() {
        JobsRepository.Job existing = job("job-1", "awaiting_human", 1, "{}");
        given(jobs.createOrFindByIdempotencyKey(eq("key"), any(), anyString()))
                .willReturn(new JobsRepository.JobCreation(existing, false));
        given(workflows.exists("job-1")).willReturn(true);

        service.submit("key", "a blender", List.of(), null);

        verify(workflows, never()).start(any(), any());
    }

    @Test
    void submitRestartsWhenTheProcessIsGone() {
        JobsRepository.Job existing = job("job-1", "pending", 0, null);
        given(jobs.createOrFindByIdempotencyKey(eq("key"), any(), anyString()))
                .willReturn(new JobsRepository.JobCreation(existing, false));
        given(workflows.exists("job-1")).willReturn(false);

        service.submit("key", "a blender", List.of(), null);

        verify(workflows).start(eq("job-1"), any());
    }

    @Test
    void approveSignalsTheWaitingWorkflow() {
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "awaiting_human", 1, CARD_JSON)));
        given(workflows.exists("job-1")).willReturn(true);

        JobSubmissionService.DecisionOutcome outcome = service.decide("job-1", JobDecision.APPROVE);

        assertThat(outcome).isInstanceOf(JobSubmissionService.DecisionOutcome.Accepted.class);
        verify(workflows).approve("job-1");
        verify(workflows, never()).reject(any());
    }

    @Test
    void rejectSignalsTheWaitingWorkflow() {
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "awaiting_human", 1, CARD_JSON)));
        given(workflows.exists("job-1")).willReturn(true);

        service.decide("job-1", JobDecision.REJECT);

        verify(workflows).reject("job-1");
        verify(workflows, never()).approve(any());
    }

    @Test
    void aTerminalJobIsReportedAsItStandsWithoutSignalling() {
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "approved", 1, CARD_JSON)));

        JobSubmissionService.DecisionOutcome outcome = service.decide("job-1", JobDecision.APPROVE);

        assertThat(outcome).isInstanceOf(JobSubmissionService.DecisionOutcome.Settled.class);
        verify(workflows, never()).approve(any());
        verify(workflows, never()).reject(any());
    }

    @Test
    void approvingAJobWithNoDraftIsRefused() {
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "awaiting_human", 0, "{}")));

        JobSubmissionService.DecisionOutcome outcome = service.decide("job-1", JobDecision.APPROVE);

        assertThat(outcome).isInstanceOf(JobSubmissionService.DecisionOutcome.NoDraft.class);
        verify(workflows, never()).approve(any());
    }

    @Test
    void aJobWhoseProcessIsGoneIsReported() {
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "awaiting_human", 1, CARD_JSON)));
        given(workflows.exists("job-1")).willReturn(false);

        JobSubmissionService.DecisionOutcome outcome = service.decide("job-1", JobDecision.APPROVE);

        assertThat(outcome).isInstanceOf(JobSubmissionService.DecisionOutcome.ProcessGone.class);
    }

    @Test
    void anUnknownJobIsReported() {
        given(jobs.findById("no-such-job")).willReturn(Optional.empty());

        assertThat(service.decide("no-such-job", JobDecision.APPROVE))
                .isInstanceOf(JobSubmissionService.DecisionOutcome.UnknownJob.class);
    }

    @Test
    void aLowConfidenceCardIsFlaggedAsAwaitingAHuman() {
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "approved", 1, CARD_JSON)));

        JobSubmissionService.JobStatus status = service.status("job-1").orElseThrow();

        assertThat(status.confidence()).isEqualTo(0.4);
        assertThat(status.awaitingHuman()).isTrue();
    }

    @Test
    void aConfidentCardIsNotFlaggedAsAwaitingAHuman() {
        String confident = CARD_JSON.replace("0.4", "0.9");
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "approved", 1, confident)));

        assertThat(service.status("job-1").orElseThrow().awaitingHuman()).isFalse();
    }

    @Test
    void aResultThatIsNotACardCarriesNoConfidence() {
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "failed", 1, "{}")));

        JobSubmissionService.JobStatus status = service.status("job-1").orElseThrow();

        assertThat(status.confidence()).isNull();
        assertThat(status.awaitingHuman()).isNull();
    }

    @Test
    void sourcesReadBackTheRetainedContext() {
        ContextChunk found = new ContextChunk("C1", 5L, "doc-1", 3, "Spec", "800 W");
        ContextChunk noPage = new ContextChunk("C2", 6L, "doc-2", 0, null, "text");
        given(jobs.findById("job-1")).willReturn(Optional.of(job("job-1", "approved", 1, CARD_JSON)));
        given(contexts.load("job-1"))
                .willReturn(new AssembledContext("job-1", List.of(found, noPage), 0, 0));

        JobSubmissionService.JobSources sources = service.sources("job-1").orElseThrow();

        assertThat(sources.jobId()).isEqualTo("job-1");
        assertThat(sources.context()).hasSize(2);
        assertThat(sources.context().get(0).page()).isEqualTo("3");
        assertThat(sources.context().get(0).section()).isEqualTo("Spec");
        assertThat(sources.context().get(1).page())
                .as("a fragment that knows no page reads as blank rather than as page zero")
                .isEmpty();
        assertThat(sources.context().get(1).section()).isEmpty();
    }

    private JobsRepository.Job job(String id, String status, int attempts, String result) {
        return new JobsRepository.Job(id, null, status, null, "{}", result, attempts, null, null, null);
    }

    private DocumentsRepository.DocumentRow document(String id, String state) {
        return new DocumentsRepository.DocumentRow(id, "file.pdf", "sha", 10, state, null, 0, null, null);
    }
}
