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

    public static String generator(String factsJson, List<String> issues) {
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
            for (String issue : issues) {
                prompt.append("- ").append(issue).append('\n');
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

                Reply with a JSON object and nothing else:
                  verdict  string  "APPROVE" if the card is fit, otherwise "REGENERATE"
                  issues   array   what is wrong, one item per problem; empty when approving

                Do not wrap the JSON in markdown. Do not add commentary.

                FACTS:
                %s

                DRAFT TO REVIEW:
                %s
                """.formatted(factsJson, draftJson);
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
