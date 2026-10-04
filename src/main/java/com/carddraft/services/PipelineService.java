package com.carddraft.services;

import java.util.List;

import org.springframework.stereotype.Service;

import com.carddraft.agents.ProductCard;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.llm.LlmClient;

/**
 * The generation pipeline: extract, then draft-and-review until the reviewer approves or the
 * budget runs out.
 *
 * <p>Imports no web framework and no orchestration library. That is the property worth
 * protecting — when a second transport is added in a later step, this file does not change, and
 * {@code git log} on it proves it.
 */
@Service
public class PipelineService {
    private final LlmClient llmClient;
    private final GenerationSettings settings;

    public PipelineService(LlmClient llmClient, GenerationSettings settings) {
        this.llmClient = llmClient;
        this.settings = settings;
    }

    public PipelineOutcome run(String supplierText) {
        SupplierFacts facts = llmClient.extractFacts(supplierText);

        List<String> issues = List.of();
        ProductCard draft = null;
        int attempts = 0;

        while (attempts < settings.maxRewriteRounds()) {
            attempts++;
            draft = llmClient.draftCard(facts, issues);
            var report = llmClient.reviewDraft(facts, draft);
            if (report.verdict() == com.carddraft.agents.Verdict.APPROVE) {
                return new PipelineOutcome(draft, attempts, PipelineVerdict.APPROVED, draft.awaitsHuman(settings.confidenceThreshold()));
            }
            issues = report.issues();
            log.info("pipeline_regenerate attempt={} issues={}", attempts, issues);
        }

        return new PipelineOutcome(draft, attempts, PipelineVerdict.REJECTED, true);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PipelineService.class);
}
