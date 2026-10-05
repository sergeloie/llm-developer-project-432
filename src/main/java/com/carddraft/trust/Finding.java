package com.carddraft.trust;

import java.util.List;

/**
 * Something the trust layer found, and what it did about it.
 *
 * @param kind    what was found, as a name callers branch on rather than a string pattern they
 *                re-match
 * @param masked  the text with the value replaced, safe to put in a prompt or a log line
 * @param excerpt the value itself, held only so a person can see what was found. Deliberately not
 *                serialised into a log or a report: the whole point of masking is that the original
 *                does not travel with the card, and an excerpt in an exception message would put it
 *                straight back into the logs.
 */
public record Finding(Kind kind, String masked, String excerpt, int position) {

    /** What can be found. Named so a caller can react rather than pattern-match. */
    public enum Kind {
        PHONE,
        EMAIL,
        TAXPAYER_NUMBER,
        CARD_NUMBER,
        INJECTION
    }

    public boolean isPersonal() {
        return kind != Kind.INJECTION;
    }

    /** Everything found in one piece of text, in the order it appeared. */
    public record Report(List<Finding> findings, String maskedText) {

        public Report {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        public static Report clean(String text) {
            return new Report(List.of(), text);
        }

        public boolean isClean() {
            return findings.isEmpty();
        }

        /**
         * A one-line summary naming what was found without quoting it.
         *
         * <p>Counts by kind rather than the values themselves, so this string is safe to put in a
         * job's detail column, an exception message or a log.
         */
        public String summary() {
            if (findings.isEmpty()) {
                return "nothing found";
            }
            return findings.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            f -> f.kind().name(), java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .map(entry -> entry.getValue() + " x " + entry.getKey())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("nothing found");
        }
    }
}