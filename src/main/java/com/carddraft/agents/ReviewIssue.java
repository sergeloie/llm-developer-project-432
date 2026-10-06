package com.carddraft.agents;

/**
 * One objection from the reviewer, addressed to a field.
 *
 * <p>Structured rather than a sentence, and the reason is that the review prompt has always asked
 * for the addressee. "What is wrong, one item per problem, naming the characteristic and the label"
 * reads to any model as an object per problem, and the reviewer did exactly that — which is how
 * {@code List<String>} came to be unable to read a single objection the model had actually produced.
 * The prompt was not wrong; the type was thinner than the question.
 *
 * <p>One shape for both reviewers, deliberately. The reviewer that checks a draft against extracted
 * facts and the reviewer that checks it against cited fragments ask different questions, but they
 * hand the same list to the same generator, and a list that means different things in two places is
 * a list that will be read wrongly in one of them. So the fragment label, which only one of them has,
 * is asked for inside the sentence rather than as a third field — the information survives and the
 * type does not grow a component that is null in half its uses.
 *
 * @param field   the characteristic or field the objection is about, blank when it is about the
 *                card as a whole rather than one line of it
 * @param problem what is wrong, in one sentence. Never blank: an objection a generator cannot act on
 *                is a wasted round, and this list is the only feedback the next attempt gets
 */
public record ReviewIssue(String field, String problem) {

    public ReviewIssue {
        field = field == null ? "" : field.strip();
        problem = problem == null ? "" : problem.strip();
    }

    public ReviewIssue(String problem) {
        this("", problem);
    }

    /**
     * The one line a generator is shown.
     *
     * <p>The field is repeated even when the sentence names it, because the generator is shown a
     * list of lines rather than prose, and a line that says which field it concerns is one the
     * generator can find in its own draft.
     */
    public String asFeedback() {
        return field.isEmpty() ? problem : field + ": " + problem;
    }
}