package com.carddraft.documents;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Cleans text so that searching for it can find it.
 *
 * <p>Every rule here corresponds to a way real supplier documents defeat a naive search: a word
 * broken across a line by a hyphen is invisible to any search for the word, a ligature changes
 * the characters, and a repeated header is counted as content until it drowns it.
 */
@Component
public class TextNormaliser {

    private static final Map<String, String> LIGATURES = ligatures();

    /** PDFBox emits U+00AD for a hyphen that was only there to break a line. It is invisible. */
    static final String SOFT_HYPHEN = "­";

    /**
     * A word broken across lines by a hyphen is joined with nothing between the halves, because
     * that is one word: "mo­del" is "model", not "mo del".
     *
     * <p>A line break with no hyphen is a different case and gets a space, because those are two
     * words and running them together would invent a third.
     */
    private static final Pattern HYPHENATED_BREAK =
            Pattern.compile("([\\p{L}])[-\u2010\u2011][ \\t]*\\n[ \\t]*([\\p{Ll}])");

    private static final Pattern PLAIN_BREAK =
            Pattern.compile("([\\p{L}])[ \\t]*\\n[ \\t]*([\\p{Ll}])");

    private static final Pattern RUNS_OF_SPACES = Pattern.compile("[ \\t\\xA0]{2,}");

    /**
     * A short line is body text, not furniture. Chosen against the shortest plausible header line
     * rather than tuned: a false positive here deletes content, and a false negative only leaves
     * a stray header in one chunk.
     */
    static final int MIN_FURNITURE_LENGTH = 12;

    /** Below this page count there is no repetition to detect, only content to destroy. */
    static final int MIN_PAGES_FOR_FURNITURE = 3;

    /** A line must appear on at least this many pages to count as furniture. */
    static final int MIN_PAGE_REPEATS = 2;

    private static Map<String, String> ligatures() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ﬀ", "ff");
        map.put("ﬁ", "fi");
        map.put("ﬂ", "fl");
        map.put("ﬃ", "ffi");
        map.put("ﬄ", "ffl");
        map.put("ﬅ", "st");
        map.put("ﬆ", "st");
        map.put("Ĳ", "IJ");
        map.put("ĳ", "ij");
        return map;
    }

    /** Ligatures, broken words, and runs of spaces, in that order. */
    public String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace(SOFT_HYPHEN, "");
        text = expandLigatures(text);
        text = HYPHENATED_BREAK.matcher(text).replaceAll("$1$2");
        text = PLAIN_BREAK.matcher(text).replaceAll("$1 $2");
        text = RUNS_OF_SPACES.matcher(text).replaceAll(" ");
        text = Normalizer.normalize(text, Normalizer.Form.NFC);
        return text.replace("\n\n\n", "\n\n").strip();
    }

    private String expandLigatures(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            String single = String.valueOf(text.charAt(i));
            String replacement = LIGATURES.get(single);
            out.append(replacement == null ? single : replacement);
        }
        return out.toString();
    }

    /**
     * Drops the lines that repeat across pages.
     *
     * <p>The page-count guard is the whole point. "Repeats on most pages" is the obvious rule and
     * it is wrong: on a two-page document the majority of two is one, so the rule selects half the
     * document — a page number, a date, a single body line — and deletes it. Requiring at least
     * three pages before the rule may fire means a short document loses nothing.
     */
    public List<String> dropFurniture(List<String> pageTexts) {
        if (pageTexts.size() < MIN_PAGES_FOR_FURNITURE) {
            return pageTexts;
        }
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (String pageText : pageTexts) {
            new LinkedHashSet<>(lines(pageText)).forEach(line -> occurrences.merge(line, 1, Integer::sum));
        }
        Set<String> furniture = new LinkedHashSet<>();
        occurrences.forEach((line, count) -> {
            if (count >= MIN_PAGE_REPEATS && line.length() >= MIN_FURNITURE_LENGTH) {
                furniture.add(line);
            }
        });
        if (furniture.isEmpty()) {
            return pageTexts;
        }
        List<String> cleaned = pageTexts.stream()
                .map(pageText -> lines(pageText).stream()
                        .filter(line -> !furniture.contains(line))
                        .collect(java.util.stream.Collectors.joining("\n")))
                .toList();
        // If the rule would leave nothing, it has misfired: a document whose every line repeats
        // is not a document made of headers. Keeping the original is the safe direction, because a
        // stray header costs a little noise while a deleted document costs the whole card.
        boolean allEmptied = cleaned.stream().allMatch(String::isBlank);
        return allEmptied ? pageTexts : cleaned;
    }

    /**
     * Splits page text into paragraphs, dropping furniture-only pages on the way.
     *
     * <p>Paragraphs rather than lines because a paragraph is the unit a human reads and the unit
     * an embedding is computed over; a line break mid-sentence is a layout artefact.
     */
    public List<String> paragraphsOf(String pageText) {
        return java.util.Arrays.stream(pageText.split("\n{2,}"))
                .map(String::strip)
                .filter(text -> !text.isEmpty())
                .toList();
    }

    private List<String> lines(String text) {
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .sorted()
                .toList();
    }
}
