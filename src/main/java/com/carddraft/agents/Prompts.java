package com.carddraft.agents;

/**
 * The three roles' instructions, as named functions.
 *
 * <p>Prompts live here rather than inline in the pipeline so that changing wording is a change to
 * one file, and so that a prompt can be read on its own without reading the code that sends it.
 *
 * <p>The field lists are written out by hand for now. Generating the JSON schema from the records
 * themselves, and duplicating it into the instruction when the server cannot enforce it, arrives
 * with the result contract.
 */
public final class Prompts {

    private Prompts() {
    }

    public static String extractor(String supplierText) {
        return """
                You are the extractor. From the supplier text below, pull out only the facts it \
                actually states. Do not invent anything: whatever is absent goes into \
                missingFields by name, never into the characteristics.

                Reply with a JSON object and nothing else:
                  productName       string  the product's name as the text gives it
                  characteristics   object  key-value pairs present in the text
                  missingFields     array   names of fields the text does not contain \
                (for example: warranty, colour, country of manufacture)

                Do not wrap the JSON in markdown. Do not add commentary.

                SUPPLIER TEXT:
                %s
                """.formatted(supplierText);
    }

    public static String generator(String factsJson, java.util.List<String> issues) {
        StringBuilder prompt = new StringBuilder("""
                You are the generator. Write a product card using only the facts below.

                Reply with a JSON object and nothing else:
                  title            string  at most 60 characters
                  description      string  three or four sentences, concrete
                  characteristics  object  key-value pairs, all of them from the facts
                  benefits         array   three to five short benefits

                Every characteristic must be present in the facts. Do not promise anything the \
                facts do not support.

                Do not wrap the JSON in markdown. Do not add commentary.

                FACTS:
                %s
                """.formatted(factsJson));

        if (issues != null && !issues.isEmpty()) {
            prompt.append("\nThe reviewer rejected the previous draft. Address every point:\n")
                    .append(issues.stream().map(issue -> "- " + issue).reduce(new StringBuilder(),
                            (b, i) -> b.append(i).append('\n'), StringBuilder::append))
                    .append('\n');
        }
        return prompt.toString();
    }

    public static String critic(String factsJson, String draftJson) {
        return """
                You are the reviewer. Check the draft card against the facts by these rules:
                1. the title is at most 60 characters;
                2. no characteristic appears that is absent from the facts;
                3. the description and the benefits are not empty;
                4. there are no empty promises such as "high quality" or "premium".

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
}
