package com.carddraft.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.carddraft.agents.ProductCard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * The call records, in a real database.
 *
 * <p>The two questions worth asking here are "does one call produce one row" and "does the money
 * survive the round trip". The second is not obvious: NUMERIC arrives back as a different scale than
 * it was written at, and a total assembled from scaled figures is a different number from the total
 * that was stored.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ModelCallRepositoryTest {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("card").withUsername("card").withPassword("card");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("card.db.url", DATABASE::getJdbcUrl);
        registry.add("card.db.username", DATABASE::getUsername);
        registry.add("card.db.password", DATABASE::getPassword);
    }

    @Autowired
    ModelCallRepository calls;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM model_calls").update();
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("INSERT INTO jobs (id, status, attempts, payload) VALUES ('job-1', 'approved', 2, '{}')").update();
    }

    private ModelCallRecord call(String tier, String model, String operation,
                                 int in, int out, String cost, Duration duration) {
        return new ModelCallRecord("job-1", tier, model, operation, in, out,
                new BigDecimal(cost), duration, null, Instant.now());
    }

    @Test
    void oneCallBecomesOneRowWithEveryPartOfIt() {
        calls.record(call("main", "qwen/qwen3.5-9b", "draftCard", 2100, 550, "0.0147",
                Duration.ofMillis(4200)));

        List<ModelCallRecord> stored = calls.forJob("job-1");

        assertThat(stored).hasSize(1);
        ModelCallRecord row = stored.get(0);
        assertThat(row.jobId()).isEqualTo("job-1");
        assertThat(row.tier()).isEqualTo("main");
        assertThat(row.model()).isEqualTo("qwen/qwen3.5-9b");
        assertThat(row.operation()).isEqualTo("draftCard");
        assertThat(row.inputTokens()).isEqualTo(2100);
        assertThat(row.outputTokens()).isEqualTo(550);
        assertThat(row.cost()).isEqualByComparingTo("0.0147");
        assertThat(row.duration()).isEqualTo(Duration.ofMillis(4200));
    }

    /**
     * Money at a scale a float cannot hold.
     *
     * <p>Twelve decimal places is more precision than anyone reads, and is exactly the precision
     * needed for a single token at a cheap per-million rate. If the column had been numeric or
     * double this value would come back rounded or inexact, and the total over a job would be a
     * slightly different number from the one stored.
     */
    @Test
    void aCostKeepsEveryDigitTheColumnPromises() {
        calls.record(call("main", "m", "draftCard", 1, 1, "0.000000000016", Duration.ofMillis(1)));

        assertThat(calls.forJob("job-1").get(0).cost())
                .isEqualByComparingTo("0.000000000016");
        assertThat(calls.costOfJob("job-1")).isEqualByComparingTo("0.000000000016");
    }

    @Test
    void theTotalForAJobIsTheSumOfItsRows() {
        calls.record(call("main", "m", "extractFacts", 1200, 300, "0.0081", Duration.ofMillis(900)));
        calls.record(call("utility", "u", "reviewDraft", 2000, 200, "0.0011", Duration.ofMillis(700)));
        calls.record(call("main", "m", "draftCard", 3000, 600, "0.0180", Duration.ofMillis(5200)));

        assertThat(calls.costOfJob("job-1")).isEqualByComparingTo("0.0272");
        assertThat(calls.forJob("job-1"))
                .as("and the rows are in the order they happened, which is most of the story")
                .extracting(ModelCallRecord::operation)
                .containsExactly("extractFacts", "reviewDraft", "draftCard");
    }

    @Test
    void aJobWithNoCallsCostsZeroRatherThanNull() {
        assertThat(calls.costOfJob("job-1")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calls.forJob("job-1")).isEmpty();
    }

    /**
     * The breakdown the ticket asks for.
     *
     * <p>Grouped by tier rather than by model name, because "where did the spend go" is a question
     * about which job the model was doing, and a rename should not split one tier's history in two.
     */
    @Test
    void spendIsBrokenDownByTier() {
        calls.record(call("main", "qwen/qwen3.5-9b", "extractFacts", 1000, 100, "0.0050", Duration.ofMillis(1)));
        calls.record(call("main", "qwen/qwen3.5-9b", "draftCard", 1000, 100, "0.0070", Duration.ofMillis(1)));
        calls.record(call("utility", "qwen/qwen3-4b-2507", "reviewDraft", 2000, 200, "0.0030", Duration.ofMillis(1)));

        List<ModelCallRepository.TierSpend> breakdown = calls.breakdownByTier();

        assertThat(breakdown).extracting(ModelCallRepository.TierSpend::tier)
                .containsExactly("main", "utility");

        ModelCallRepository.TierSpend main = breakdown.get(0);
        assertThat(main.calls()).isEqualTo(2);
        assertThat(main.inputTokens()).isEqualTo(2000);
        assertThat(main.outputTokens()).isEqualTo(200);
        assertThat(main.cost()).isEqualByComparingTo("0.0120");

        ModelCallRepository.TierSpend utility = breakdown.get(1);
        assertThat(utility.calls()).isEqualTo(1);
        assertThat(utility.cost()).isEqualByComparingTo("0.0030");
    }

    /**
     * A call made with no job behind it is still recorded.
     *
     * <p>The synchronous endpoint has no job, and refusing to record those calls would make the table
     * answer "what did the asynchronous path cost" while looking like it answered "what did the
     * service cost".
     */
    @Test
    void aCallWithNoJobIsRecordedAndLeftOutOfEveryJobTotal() {
        calls.record(new ModelCallRecord(null, "main", "m", "draftCard", 500, 100,
                new BigDecimal("0.0020"), Duration.ofMillis(1), null, Instant.now()));

        assertThat(jdbc.sql("SELECT count(*) FROM model_calls WHERE job_id IS NULL")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(calls.forJob("job-1")).isEmpty();
        assertThat(calls.costOfJob("job-1")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * A failed record must not fail the call.
     *
     * <p>The card is already produced by the time the row is written, and re-running the job would
     * cost a generation to replace a number. A missing cost row is visible in the totals; a
     * duplicated generation is not visible at all.
     */
    @Test
    void aRecordThatCannotBeWrittenIsSwallowedRatherThanFailingTheCall() {
        ModelCallRepository broken = new ModelCallRepository(null);

        broken.record(call("main", "m", "draftCard", 1, 1, "0.0000000001", Duration.ofMillis(1)));
    }

    /**
     * Repair is a generation like any other.
     *
     * <p>It carries its own operation name so it is visible in the breakdown. Left unlabelled it
     * would be folded into the generation that preceded it, and a card that needed two repairs would
     * look like one that needed none.
     */
    @Test
    void repairIsRecordedUnderItsOwnOperation() {
        calls.record(call("main", "m", "repairField:title", 3000, 200, "0.0120", Duration.ofMillis(3100)));

        assertThat(calls.forJob("job-1")).extracting(ModelCallRecord::operation)
                .containsExactly("repairField:title");
        assertThat(calls.forJob("job-1").get(0).operation())
                .as("the field name is part of the operation, so which repair failed is answerable")
                .contains("title");
    }

    /**
     * A load time is kept beside generation when the provider reports one.
     *
     * <p>Worth the extra column because the two call for different remedies: a slow load is a
     * server pulling weights in, a slow generation is the model or the prompt. Null rather than
     * zero when the provider is silent, since zero would claim no load happened.
     */
    @Test
    void aReportedLoadTimeIsStoredSeparatelyFromTheTotal() {
        calls.record(new ModelCallRecord("job-1", "main", "m", "draftCard", 100, 100,
                new BigDecimal("0.0001"), Duration.ofSeconds(12), Duration.ofSeconds(9), Instant.now()));

        ModelCallRecord row = calls.forJob("job-1").get(0);

        assertThat(row.duration()).isEqualTo(Duration.ofSeconds(12));
        assertThat(row.loadDuration())
                .as("so a caller can see that nine seconds of twelve went to loading weights")
                .isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void anAbsentLoadTimeStaysAbsentRatherThanBecomingZero() {
        calls.record(call("main", "m", "draftCard", 100, 100, "0.0001", Duration.ofSeconds(12)));

        assertThat(calls.forJob("job-1").get(0).loadDuration())
                .as("zero would assert that no load happened; nobody said")
                .isNull();
    }

    /**
     * The client writes the row, so no call site can forget.
     *
     * <p>Counted rather than asserted against a mock's expectation, and run through the client so
     * the property being tested is the one the architecture relies on: the recorder is inside the
     * thing that made the call.
     */
    @Test
    void theClientWritesOneRecordPerCallWithoutBeingAsked() {
        var builder = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.Builder.class);
        var chatClient = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class);
        var requestSpec = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        var callSpec = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec.class);

        given(builder.build()).willReturn(chatClient);
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.options(any(org.springframework.ai.chat.prompt.ChatOptions.Builder.class)))
                .willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callSpec);
        given(callSpec.chatResponse()).willReturn(responseWithUsage(900, 120));

        RecordingRepository recorder = new RecordingRepository();
        LlmSettings settings = new LlmSettings("main-model", "utility-model",
                Duration.ofSeconds(30), 1, Duration.ofMillis(1), Duration.ofMillis(1), 0,
                new BigDecimal("3.00"), new BigDecimal("15.00"),
                new BigDecimal("0.50"), new BigDecimal("1.50"));
        SpringAiLlmClient client =
                new SpringAiLlmClient(builder, new tools.jackson.databind.ObjectMapper(), settings, recorder);

        JobLogContext.withJob("job-42", () ->
                client.draftCardFromContext("[C1] Power 800 W", List.of()));

        assertThat(recorder.recorded).singleElement().satisfies(record -> {
            assertThat(record.jobId()).as("taken from the execution context, not passed in").isEqualTo("job-42");
            assertThat(record.tier()).isEqualTo("main");
            assertThat(record.inputTokens()).isEqualTo(900);
            assertThat(record.outputTokens()).isEqualTo(120);
            assertThat(record.cost())
                    .as("900 in at 3.00/M plus 120 out at 15.00/M")
                    .isEqualByComparingTo("0.0045");
        });
    }

    /** A call with no job behind it is recorded rather than refused. */
    @Test
    void aCallOutsideAJobIsStillRecordedWithNoJob() {
        var builder = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.Builder.class);
        var chatClient = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class);
        var requestSpec = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        var callSpec = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec.class);

        given(builder.build()).willReturn(chatClient);
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.options(any(org.springframework.ai.chat.prompt.ChatOptions.Builder.class)))
                .willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callSpec);
        given(callSpec.chatResponse()).willReturn(responseWithUsage(100, 50));

        RecordingRepository recorder = new RecordingRepository();
        LlmSettings settings = new LlmSettings("main-model", "utility-model",
                Duration.ofSeconds(30), 1, Duration.ofMillis(1), Duration.ofMillis(1), 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        SpringAiLlmClient client =
                new SpringAiLlmClient(builder, new tools.jackson.databind.ObjectMapper(), settings, recorder);

        client.draftCardFromContext("[C1] text", List.of());

        assertThat(recorder.recorded).singleElement()
                .extracting(ModelCallRecord::jobId)
                .as("the synchronous endpoint has no job, and must still be counted")
                .isNull();
    }

    /**
     * A card that satisfies the contract.
     *
     * <p>Needed rather than any JSON: the client validates before it records, so a stub response of
     * "{}" throws and the accounting code never runs. The test would then pass for the wrong
     * reason - it would be asserting that a failed call records nothing.
     */
    private static final String VALID_CARD = """
            {"title":"Kettle","description":"A 1.7 litre kettle.","characteristics":{"Power":"2200 W"},
             "benefits":["fast to boil"],"missingFields":[],"confidence":0.9,"sources":{"Power":"C1"}}
            """;

    /** Counts rows instead of restating an expectation about a mock. */
    static final class RecordingRepository extends ModelCallRepository {

        final List<ModelCallRecord> recorded = new ArrayList<>();

        RecordingRepository() {
            super(null);
        }

        @Override
        public void record(ModelCallRecord call) {
            recorded.add(call);
        }
    }

    private static org.springframework.ai.chat.model.ChatResponse responseWithUsage(int in, int out) {
        var usage = new org.springframework.ai.chat.metadata.DefaultUsage(in, out, in + out);
        var metadata = org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .usage(usage)
                .build();
        var generation = new org.springframework.ai.chat.model.Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(VALID_CARD));
        return new org.springframework.ai.chat.model.ChatResponse(
                java.util.List.of(generation), metadata);
    }
}