package com.carddraft.metrics;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Makes two spellings of one value compare equal.
 *
 * <p>This lives in the codebase rather than in the comparison, and that is the whole point. A
 * metric that normalised its inputs inside the comparison would be a metric whose numbers nobody can
 * reproduce by hand, and the first time a score moved there would be no way to tell whether the card
 * got better or the comparison got more forgiving. Both the metric and the judge prompt use this.
 *
 * <p>Deliberately conservative. Normalisation folds case, collapses whitespace, strips punctuation a
 * supplier would not intend, and unifies number formatting. It does not try to bridge units
 * ("800 W" and "0.8 kW" are the same fact and are not equal here), reorder words, or stem: a
 * normaliser that guesses at meaning will make a wrong card look right, which is the one direction
 * a quality metric must never err in.
 */
public final class ValueNormaliser {

    private ValueNormaliser() {
    }

    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);

        // A comma as a decimal separator is a Russian-locale habit, and both spellings appear in
        // the same reference set. Only between digits: a comma in "1,5 л, 0,9 кг" separates list
        // items there and removing it would join two facts.
        text = text.replaceAll("(?<=\\d),(?=\\d)", ".");

        text = text.replaceAll("\\s+", " ").strip();

        // Punctuation a supplier added as prose rather than as part of the value. Stripped from the
        // ends only - "220 V, 50 Hz" keeps its separator because dropping it would merge two facts
        // into one string that appears nowhere in the document.
        text = stripSurroundingPunctuation(text);

        return text;
    }

    /**
     * Whether two values are the same after normalisation.
     *
     * <p>An empty reference never matches, not even an empty card value. A characteristic the
     * reference set does not know about is counted as a miss rather than as a hit, because the
     * alternative rewards the model for inventing something.
     */
    public static boolean matches(String cardValue, String referenceValue) {
        String expected = normalise(referenceValue);
        if (expected.isEmpty()) {
            return false;
        }
        return expected.equals(normalise(cardValue));
    }

    private static String stripSurroundingPunctuation(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isDecorator(text.charAt(start))) {
            start++;
        }
        while (end > start && isDecorator(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** Punctuation that carries no value at the end of a value: quotes, trailing stops, brackets. */
    private static boolean isDecorator(char c) {
        return switch (c) {
            case '.', ',', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']', '{', '}', '—', '–', '-'
                    -> true;
            default -> false;
        };
    }
}