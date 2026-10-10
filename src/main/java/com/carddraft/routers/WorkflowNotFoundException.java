package com.carddraft.routers;

import org.springframework.http.HttpStatus;

/**
 * A decision or lookup for a job whose process does not exist.
 *
 * <p>Distinct from a job identifier nobody knows, which is a 404: the row exists, the workflow
 * that should be waiting on a decision does not — a metrics job, or a job whose process the
 * engine lost. The caller must not be able to tell "still working" from "nothing is working"
 * apart, so this is a 409 that only fires once the process is verifiably gone.
 */
public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String jobStatus) {
        super("no process is waiting on a decision for this job; its state is " + jobStatus);
    }

    public HttpStatus getStatus() {
        return HttpStatus.CONFLICT;
    }
}
