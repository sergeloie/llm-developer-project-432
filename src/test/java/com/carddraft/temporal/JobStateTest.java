package com.carddraft.temporal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status vocabulary a card job reports, which is the job's and not the document's.
 *
 * <p>Parsing and indexing describe where a <em>document</em> is; the document lifecycle owns them
 * and the database holds the list ({@code documents_state_known}). Carrying them on the job enum
 * let a caller read "parsing" as a job state that nothing on the job path can ever write, so the
 * two vocabularies had to be told apart by remembering which class held which constant.
 */
class JobStateTest {

    @Test
    void everyJobStateIsReachableFromItsWireName() {
        for (JobState state : JobState.values()) {
            assertThat(JobState.fromWireName(state.wireName())).contains(state);
        }
    }

    @Test
    void documentLifecycleStatesAreNotJobStates() {
        assertThat(JobState.fromWireName("parsing"))
                .as("a document is parsing; a job is never in that state")
                .isEmpty();
        assertThat(JobState.fromWireName("indexing"))
                .as("a document is being indexed; a job is never in that state")
                .isEmpty();
    }

    @Test
    void theTerminalStatesStayDistinctFromTheWorkingOnes() {
        assertThat(JobState.APPROVED.isTerminal()).isTrue();
        assertThat(JobState.REJECTED.isTerminal()).isTrue();
        assertThat(JobState.FAILED.isTerminal()).isTrue();
        assertThat(JobState.AWAITING_HUMAN.isTerminal()).isFalse();
        assertThat(JobState.AWAITING_HUMAN.awaitsHuman()).isTrue();
        assertThat(JobState.GENERATING.awaitsHuman()).isFalse();
    }
}
