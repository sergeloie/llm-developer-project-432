package com.carddraft.llm;

/**
 * Recovers the JSON object from a model response, without interpreting it.
 *
 * <p>A response is a string, and a dirty one. Local models wrap JSON in markdown fences, put a
 * sentence in front of it and a note after it. All of that is handled here rather than at each
 * call site, so no call site has to know which model it is talking to.
 *
 * <p>Extraction only. Whether the recovered text parses is the caller's question, and keeping the
 * two apart means a formatting problem and a schema problem stay distinguishable.
 *
 * <p>Braces are balanced rather than pattern-matched. The previous shape was a greedy
 * {@code \{.*\}}, which survives nested objects but swallows everything up to the last brace
 * on the page — trailing prose containing a brace came back as part of the "object" and
 * failed parsing with a misleading span. Counting depth while respecting strings and escapes
 * returns the first complete object and nothing after it.
 */
public final class ModelResponses {

    private ModelResponses() {}

    /**
     * The first complete JSON object in the model's reply.
     *
     * @throws ModelResponseFormatException when no JSON object can be recovered, with a message
     *         naming the operation and quoting the start of the text rather than dumping all of it
     */
    public static String jsonObject(String raw, String operation) {
        if (raw == null || raw.isBlank()) {
            throw new ModelResponseFormatException(operation + ": model returned an empty response");
        }

        String candidate = raw.strip();
        String fenced = insideFences(candidate);
        if (fenced != null && !fenced.isBlank()) {
            candidate = fenced.strip();
        }

        for (int start = candidate.indexOf('{'); start >= 0; start = candidate.indexOf('{', start + 1)) {
            String balanced = balancedFrom(candidate, start);
            if (balanced != null) {
                return balanced;
            }
        }

        throw new ModelResponseFormatException(
                operation + ": no JSON object found in the response. First 200 characters: " + abbreviate(raw));
    }

    /**
     * The fenced block, if the response carries one.
     *
     * <p>Found anywhere rather than anchored: models put a sentence before the fence despite
     * being told not to. A first line that is not JSON — the {@code json} language tag — is
     * skipped; a first line that already opens an object is kept.
     */
    private static String insideFences(String text) {
        int open = text.indexOf("```");
        if (open < 0) {
            return null;
        }
        int close = text.indexOf("```", open + 3);
        if (close < 0) {
            return null;
        }
        String inner = text.substring(open + 3, close).strip();
        int newline = inner.indexOf('\n');
        if (newline >= 0 && !inner.substring(0, newline).strip().startsWith("{")) {
            return inner.substring(newline + 1);
        }
        return inner;
    }

    /**
     * The complete object starting at {@code start}, or null when it never closes.
     *
     * <p>Strings and escapes are honoured, so a brace inside a value neither opens nor
     * closes anything. An unclosed object is not an error here — it just means this
     * particular brace was not the start of one, and the caller tries the next.
     */
    private static String balancedFrom(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String abbreviate(String text) {
        String collapsed = text.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "…";
    }
}
