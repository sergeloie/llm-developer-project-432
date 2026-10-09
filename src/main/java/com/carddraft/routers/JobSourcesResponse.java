package com.carddraft.routers;

import java.util.List;

import com.carddraft.services.JobSubmissionService;

/**
 * The card's sources together with the fragments they name.
 *
 * <p>Both are carried because either alone leaves the caller guessing: labels alone do not say what
 * {@code C3} was, and fragments alone do not say which claim each supports.
 */
public record JobSourcesResponse(String jobId, List<Fragment> context) {

    public static JobSourcesResponse from(JobSubmissionService.JobSources sources) {
        return new JobSourcesResponse(sources.jobId(), sources.context().stream()
                .map(fragment -> new Fragment(fragment.reference(), fragment.documentId(),
                        fragment.page(), fragment.section(), fragment.text()))
                .toList());
    }

    public record Fragment(String reference, String documentId, String page, String section,
                           String text) {
    }
}
