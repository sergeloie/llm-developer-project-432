package com.carddraft.temporal;

/**
 * Job state, as the client sees it.
 *
 * <p>The three terminal states are deliberately distinct. {@code approved} and {@code rejected}
 * are business outcomes; {@code failed} is an incident. Merging them makes it impossible to
 * alert on the right one or to compute a success rate — an alerting rule on rejection trains a
 * team to ignore alerts, and then the real outage is ignored too.
 */
public enum JobState {

    PENDING("pending"),
    PARSING("parsing"),
    INDEXING("indexing"),
    EXTRACTING("extracting"),
    RETRIEVING("retrieving"),
    GENERATING("generating"),
    REVIEWING("reviewing"),
    AWAITING_HUMAN("awaiting_human"),
    APPROVED("approved"),
    REJECTED("rejected"),
    FAILED("failed");

    /**
     * Whether the state means a person or a machine is currently responsible for the job.
     *
     * <p>Exists so the client can tell "still working" from "waiting on you" without hard-coding the
     * list. A job that is merely slow and a job that needs a decision look identical from outside
     * unless the states themselves say which, and the difference is the whole reason a person polls.
     */
    public boolean awaitsHuman() {
        return this == AWAITING_HUMAN;
    }

    private final String wireName;

    JobState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static java.util.Optional<JobState> fromWireName(String value) {
        for (JobState state : values()) {
            if (state.wireName.equals(value)) {
                return java.util.Optional.of(state);
            }
        }
        // The job table holds rows the card-job enum does not know — the metrics harness
        // records its runs in the same table under metrics_complete and pending — so an
        // unknown status is a fact of the data, not a bug. It comes back empty and the
        // caller decides what an unknown state means; it must never throw out of the
        // controller.
        return java.util.Optional.empty();
    }

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == FAILED;
    }
}
