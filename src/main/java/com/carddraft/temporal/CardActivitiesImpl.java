package com.carddraft.temporal;

import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import com.carddraft.agents.CardDraft;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.Verdict;
import com.carddraft.llm.LlmClient;
import com.carddraft.repositories.JobsRepository;

/**
 * The steps, as ordinary Java.
 *
 * <p>Everything here runs outside the workflow's constraints: it may read configuration, block on
 * the model, touch the database and use a clock. The workflow gets an interface and a return
 * value; it never learns any of this happened.
 *
 * <p>This is the only place that bridges the two worlds, and it is thin on purpose. The logic is
 * in the services below it — the activities decide what to call, not how it behaves.
 */
@Component
public class CardActivitiesImpl implements CardActivities {

    private final LlmClient llmClient;
    private final JobsRepository jobs;
    private final ObjectMapper mapper;

    public CardActivitiesImpl(LlmClient llmClient, JobsRepository jobs, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @Override
    public String extractFacts(String jobId, String supplierText) {
        return toJson(llmClient.extractFacts(supplierText));
    }

    @Override
    public String generateDraft(String jobId, String factsJson, List<String> issues) {
        return toJson(llmClient.draftCard(fromJson(factsJson, SupplierFacts.class), issues));
    }

    @Override
    public ReviewOutcome reviewDraft(String jobId, String factsJson, String draftJson) {
        CritiqueReport report = llmClient.reviewDraft(
                fromJson(factsJson, SupplierFacts.class),
                fromJson(draftJson, CardDraft.class));
        return new ReviewOutcome(report.verdict() == Verdict.APPROVE, report.issues());
    }

    @Override
    public void writeStatus(String jobId, String state, String detail) {
        jobs.setStatus(jobId, state, detail);
    }

    @Override
    public void countAttempt(String jobId) {
        jobs.recordAttempt(jobId);
    }

    @Override
    public void recordFailure(String jobId, String error) {
        jobs.fail(jobId, error);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("could not read " + type.getSimpleName(), e);
        }
    }
}
