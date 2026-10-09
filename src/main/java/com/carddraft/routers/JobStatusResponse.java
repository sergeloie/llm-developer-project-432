package com.carddraft.routers;

import com.carddraft.services.JobSubmissionService;

/**
 * A job as a client reads it: where it is, how far it got, and what it produced.
 *
 * <p>Absent fields are left out by the configured Jackson policy, so a rejection reason that was
 * never set is simply not mentioned rather than sent as null.
 */
public record JobStatusResponse(String id, String status, int attempts, String detail, String result,
                                Double confidence, Boolean awaitingHuman, String error) {

    public static JobStatusResponse from(JobSubmissionService.JobStatus status) {
        return new JobStatusResponse(status.id(), status.status(), status.attempts(),
                status.detail(), status.result(), status.confidence(), status.awaitingHuman(),
                status.error());
    }
}
