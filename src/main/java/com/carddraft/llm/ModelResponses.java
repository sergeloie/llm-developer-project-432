package com.carddraft.llm;

import java.util.regex.Pattern;

/**
 * Recovers the JSON object from a model response, without interpreting it.
 *
 * <p>A response is a string, and a dirty one. Local models wrap JSON in markdown fences, put a
 * sentence in front of it and a note after it. All of that is handled here rather than at each
 * call site, so no call site has to know which model it is talking to.
 *
 * <p>Extraction only. Whether the recovered text parses is the caller's question, and keeping the
 * two apart means a formatting problem and a schema problem stay distinguishable.
 */
public final class ModelResponses {

    private static final Pattern FENCE = Pattern.compile("(?s)^```[a-zA-Z]*\\s*(.*?)\\s*```$");
    private static final Pattern EMBEDDED_OBJECT = Pattern.compile("(?s)\\{.*}");

    private ModelResponses() {
    }

    /**
     * @throws ModelResponseFormatException when no JSON object can be recovered, with a message
     *         naming the operation and quoting the start of the text rather than dumping all of it
     */
    public static String jsonObject(String raw, String operation) {
        if (raw == null || raw.isBlank()) {
            throw new ModelResponseFormatException(operation + ": model returned an empty response");
        }

        String candidate = raw.strip();
        var fenced = FENCE.matcher(candidate);
        if (fenced.matches()) {
            candidate = fenced.group(1).strip();
        }
        if (candidate.startsWith("{") && candidate.endsWith("}")) {
            return candidate;
        }

        var embedded = EMBEDDED_OBJECT.matcher(candidate);
        if (embedded.find()) {
            return embedded.group();
        }

        throw new ModelResponseFormatException(operation
                + ": no JSON object found in the response. First 200 characters: " + abbreviate(raw));
    }

    private static String abbreviate(String text) {
        String collapsed = text.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "…";
    }
}
