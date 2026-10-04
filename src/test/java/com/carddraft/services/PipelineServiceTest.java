package com.carddraft.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddraft.agents.ProductCard;
import com.carddraft.agents.CritiqueReport;
import com.carddraft.agents.SupplierFacts;
import com.carddraft.agents.Verdict;
import com.carddraft.llm.LlmClient;

/**
 * The rewrite loop, observed through the model boundary.
 *
 * <p>What matters here is not that generation produces text but that the loop's budget holds,
 * that feedback actually reaches the generator, and that extraction is paid for once.
 */
@ExtendWith(MockitoExtension.class)
class PipelineServiceTest {

    private static final SupplierFacts FACTS =
            new SupplierFacts("Blender MixerPro 800", Map.of("Power", "800 W"), List.of());

    @Mock
    LlmClient llmClient;

    @Captor
    ArgumentCaptor<List<String>> issuesCaptor;

    PipelineService pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new PipelineService(llmClient, new GenerationSettings(3, 0.7));
    }

    @Test
    void approvesOnTheFirstRoundWithoutRewriting() {
        given(llmClient.extractFacts("supplier text")).willReturn(FACTS);
        given(llmClient.draftCard(FACTS, List.of())).willReturn(draft("First"));
        given(llmClient.reviewDraft(eq(FACTS), any())).willReturn(new CritiqueReport(Verdict.APPROVE, List.of()));

        PipelineOutcome outcome = pipeline.run("supplier text");

        assertThat(outcome.verdict()).isEqualTo(PipelineVerdict.APPROVED);
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.draft().title()).isEqualTo("First");
        verify(llmClient, times(1)).draftCard(any(), any());
    }

    @Test
    void sendsTheReviewersIssuesBackToTheGeneratorOnTheNextRound() {
        given(llmClient.extractFacts("supplier text")).willReturn(FACTS);
        given(llmClient.draftCard(eq(FACTS), any())).willReturn(draft("First"), draft("Second"));
        given(llmClient.reviewDraft(eq(FACTS), any()))
                .willReturn(new CritiqueReport(Verdict.REGENERATE, List.of("title is longer than 60 characters")),
                        new CritiqueReport(Verdict.APPROVE, List.of()));

        PipelineOutcome outcome = pipeline.run("supplier text");

        assertThat(outcome.verdict()).isEqualTo(PipelineVerdict.APPROVED);
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.draft().title()).isEqualTo("Second");

        verify(llmClient, times(2)).draftCard(eq(FACTS), issuesCaptor.capture());
        assertThat(issuesCaptor.getAllValues().get(0)).isEmpty();
        assertThat(issuesCaptor.getAllValues().get(1))
                .containsExactly("title is longer than 60 characters");
    }

    @Test
    void stopsWhenTheRewriteBudgetIsExhaustedAndKeepsTheLastDraft() {
        given(llmClient.extractFacts("supplier text")).willReturn(FACTS);
        given(llmClient.draftCard(eq(FACTS), any())).willReturn(draft("First"), draft("Second"), draft("Third"));
        given(llmClient.reviewDraft(eq(FACTS), any()))
                .willReturn(new CritiqueReport(Verdict.REGENERATE, List.of("issue one")),
                        new CritiqueReport(Verdict.REGENERATE, List.of("issue two")),
                        new CritiqueReport(Verdict.REGENERATE, List.of("issue three")));

        PipelineOutcome outcome = pipeline.run("supplier text");

        assertThat(outcome.verdict()).isEqualTo(PipelineVerdict.REJECTED);
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(outcome.draft()).as("a rejected draft is still kept").isNotNull();
        assertThat(outcome.draft().title()).isEqualTo("Third");
        verify(llmClient, times(3)).draftCard(eq(FACTS), any());
        verify(llmClient, times(3)).reviewDraft(eq(FACTS), any());
    }

    @Test
    void extractsFactsOnceRegardlessOfHowManyRoundsRun() {
        given(llmClient.extractFacts("supplier text")).willReturn(FACTS);
        given(llmClient.draftCard(eq(FACTS), any())).willReturn(draft("First"), draft("Second"), draft("Third"));
        given(llmClient.reviewDraft(eq(FACTS), any()))
                .willReturn(new CritiqueReport(Verdict.REGENERATE, List.of("a")),
                        new CritiqueReport(Verdict.REGENERATE, List.of("b")),
                        new CritiqueReport(Verdict.REGENERATE, List.of("c")));

        pipeline.run("supplier text");

        verify(llmClient, times(1)).extractFacts("supplier text");
    }

    private ProductCard draft(String title) {
        return new ProductCard(title, "A blender.", Map.of("Power", "800 W"),
                List.of("Quiet"), List.of(), 0.9, Map.of());
    }
}
