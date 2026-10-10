package com.carddraft.llm;

/**
 * Which configured model slot a call went to.
 *
 * <p>An enum rather than the model name, for two reasons. The cost breakdown has to group by slot,
 * and a model rename would otherwise split one tier's history into two. And the two slots are
 * chosen for different reasons — generation for card quality, utility for cheap judgement — so
 * "which tier" is a question with an answer of its own, not a restatement of which model.
 */
public enum ModelTier {

    /** Generates and repairs cards. */
    MAIN("main"),

    /** Reviews, classifies, screens for injection, judges. */
    UTILITY("utility");

    private final String wireName;

    ModelTier(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ModelTier fromWireName(String value) {
        for (ModelTier tier : values()) {
            if (tier.wireName.equals(value)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("unknown model tier: " + value);
    }
}
