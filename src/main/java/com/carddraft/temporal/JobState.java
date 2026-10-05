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

    public static JobState fromWireName(String value) {
        for (JobState state : values()) {
            if (state.wireName.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown job state: " + value);
    }

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == FAILED;
    }
}
