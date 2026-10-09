package com.carddraft.routers;

import com.carddraft.repositories.JobsRepository;

/**
 * The immediate answer to a submission or a decision: an identifier and where the job stands.
 *
 * <p>202 rather than 201, because the resource does not exist yet: there is an accepted intention
 * to generate, and nothing more.
 */
public record JobAcceptedResponse(String id, String status) {

    public static JobAcceptedResponse from(JobsRepository.Job job) {
        return new JobAcceptedResponse(job.id(), job.status());
    }
}
