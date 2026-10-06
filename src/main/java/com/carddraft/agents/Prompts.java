package com.carddraft.agents;

import com.carddraft.llm.ResultContract;

import java.util.List;

/**
 * The three roles' instructions, as named functions.
 *
 * <p>Prompts live here rather than inline in the pipeline so that changing wording is a change to
 * one file, and so that a prompt can be read on its own without reading the code that sends it.
 *
 * <p>The schema handed to each role is derived from the record it must satisfy, not written out
 * here, so the instruction cannot drift from the type the application parses into.
 */
public final class Prompts {

    private Prompts() {
    }

    public static String extractor(String supplierText) {
        return """
                You are the extractor. From the supplier text below, pull out only the facts it \
                actually states. Do not invent anything: whatever is absent goes into \
                missingFields by name, never into the characteristics.

                Reply with a JSON object matching this schema and nothing else. No prose, no \
                markdown fences.

                SCHEMA:
                %s

                SUPPLIER TEXT:
                %s
                """.formatted(ResultContract.schemaFor(SupplierFacts.class), supplierText);
    }

    public static String generator(String factsJson, List<ReviewIssue> issues) {
        StringBuilder prompt = new StringBuilder("""
                You are the generator. Write a product card using only the facts below.

                Reply with a JSON object matching this schema and nothing else. No prose, no \
                markdown fences. The title must be at most 60 characters. Every characteristic \
                must be present in the facts; do not promise anything the facts do not support. \
                For each characteristic, sources maps the characteristic name to the identifier \
                of the fragment it came from. If the facts carry no identifiers, leave sources \
                empty rather than inventing one. Put anything absent into missingFields.

                SCHEMA:
                %s

                FACTS:
                %s
                """.formatted(ResultContract.schemaFor(ProductCard.class), factsJson));

if (issues != null && !issues.isEmpty()) {
            prompt.append("\nThe reviewer rejected the previous draft. Address every point:\n");
            for (ReviewIssue issue : issues) {
                prompt.append("- ").append(issue.asFeedback()).append('\n');
            }
        }
        return prompt.toString();
    }

public static String critic(String factsJson, String draftJson) {
        return """
                You are the reviewer. Check the draft card against the facts by these rules:
                1. the title is at most 60 characters;
                2. no characteristic appears that is absent from the facts;
                3. the description and the benefits are not empty;
                4. there are no empty promises such as "high quality" or "premium";
                5. every characteristic has a source, or the facts carried no identifiers.

                Reply with a JSON object matching this schema and nothing else, with these two \
                keys:
                  verdict  string   "APPROVE" if the card is fit, otherwise "REGENERATE"
                  issues   array    one object per problem, each with `field` naming the \
                characteristic or "" when the objection is about the card as a whole, and `problem` \
                saying what is wrong in one sentence. Empty when approving.

                Do not wrap the JSON in markdown. Do not add commentary.

                SCHEMA:
                %s

                FACTS:
                %s

                DRAFT TO REVIEW:
                %s
                """.formatted(ResultContract.schemaFor(CritiqueReport.class), factsJson, draftJson);
    }

/**
     * The generator, working from retrieved fragments rather than extracted facts.
     *
     * <p>The difference from {@link #generator} is not stylistic. Here the model is shown numbered
     * fragments and is required to name the number for every characteristic, which is the only way
     * a claim can later be tied to a source a reviewer can open. It also states the failure mode
     * explicitly: a characteristic that no fragment supports belongs in missingFields, because the
     * alternative — citing the nearest plausible fragment — produces a card that cites well and
     * says nothing true.
     *
     * <p>The prohibition on inventing labels is not a warning. Labels are allocated by the
     * application and verified against the retained set, so an invented one does not merely read
     * wrong, it fails the card.
     */
    public static String generatorFromContext(String contextText, List<ReviewIssue> issues) {
        StringBuilder prompt = new StringBuilder("""
                You are the generator. Write a product card using only the fragments below.

Each fragment is labelled, for example [C3]. For every characteristic you write, \
sources must map that characteristic name to the reference of the fragment it came from. Write \
that reference exactly as it appears inside the brackets and without them: for the fragment shown \
as [C3] the value is C3, not [C3]. Use a reference that appears in the fragments above and no \
other. Never invent a reference: if no fragment supports a characteristic, leave that \
characteristic out and name it in missingFields instead. A characteristic with no supporting \
fragment is far better than a characteristic citing a fragment that does not support it.

                Reply with a JSON object matching this schema and nothing else. No prose, no \
                markdown fences. The title must be at most 60 characters.

                SCHEMA:
                %s

                FRAGMENTS:
                %s
                """.formatted(ResultContract.schemaFor(ProductCard.class), contextText));

        if (issues != null && !issues.isEmpty()) {
            prompt.append("\nThe previous draft was rejected. Address every point:\n");
            for (ReviewIssue issue : issues) {
                prompt.append("- ").append(issue.asFeedback()).append('\n');
            }
        }
        return prompt.toString();
    }

    /**
     * The reviewer, checking claims against the fragments they cite.
     *
     * <p>This is the review's new job in the retrieval branch: not just that a characteristic is in
     * the card, but that the fragment it names actually says so. The instruction names the specific
     * error worth catching — a right fact attributed to the wrong fragment — because that is the
     * one a reader cannot see, since both the fact and the fragment are individually plausible.
     *
     * <p>Checking is the model's judgement and is not sufficient on its own; the application then
     * verifies the labels mechanically. Both are kept because they fail differently: the model
     * catches a wrong attribution the label cannot express, and the check catches a plausible label
     * pointing at a fragment the model never saw.
     */
    public static String criticAgainstContext(String contextText, String draftJson) {
        return """
                You are the reviewer. Check the draft card against the fragments it cites.

                For each characteristic, find the label the card gives it in sources, then read \
                the fragment with that label and decide whether the fragment supports the value. A \
                right fact attributed to the wrong fragment is a failure: the value and the \
                fragment are both plausible, and a reader cannot see the mismatch.

                Reply with a JSON object matching this schema and nothing else, with these two \
                keys:
                  verdict  string   "APPROVE" if every cited characteristic is supported, \
                otherwise "REGENERATE"
                  issues   array    one object per problem, each with `field` naming the \
                characteristic or "" when the objection is about the card as a whole, and `problem` \
                saying in one sentence what is wrong - naming the fragment label when the problem \
                is that the card cites the wrong one. Empty when approving.

                Do not wrap the JSON in markdown. Do not add commentary.

                SCHEMA:
                %s

                FRAGMENTS:
                %s

                DRAFT TO REVIEW:
                %s
                """.formatted(ResultContract.schemaFor(CritiqueReport.class), contextText, draftJson);
    }

    /**
     * Repairs exactly one field and nothing else.
     *
     * <p>The current draft is included whole, because the model has to see what it is changing,
     * and the instruction is explicit that everything else must come back byte-identical — a
     * rewrite of the description here would cost a second generation for no reason and would show
     * up as a silent change nobody asked for.
     */
    public static String repairField(ProductCard current, String field, String problem) {
        return """
                You are correcting one field of a product card.

                Change the field "%s" and nothing else. Every other field must be returned \
                exactly as it appears below, unchanged, character for character.

                The problem with that field: %s

                Reply with the corrected JSON object and nothing else. No prose, no markdown \
                fences.

                SCHEMA:
                %s

                CURRENT CARD:
                %s
                """.formatted(field, problem,
                ResultContract.schemaFor(ProductCard.class),
                ResultContract.describe(current));
    }
}
