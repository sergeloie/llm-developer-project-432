package com.carddraft.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.carddraft.repositories.ChunkSearchRepository.Hit;

/**
 * Turns a ranked result list into a context the model can be held to.
 *
 * <p>Three things happen here, in this order, and the order is the design.
 *
 * <p>Deduplication comes before numbering. A specification is frequently uploaded twice under
 * different names, and the retrieval happily returns both copies of the same paragraph; numbering
 * first would hand the model C1 and C4 with identical text under two labels, and it would then cite
 * whichever one it happened to read. Worse, the budget would be spent twice on one fact.
 *
 * <p>Numbering comes before nothing else that can drop a chunk, and labels are contiguous from C1.
 * That contiguity is what makes a citation checkable by membership rather than by pattern: if the
 * model returns {@code C7} from a five-chunk context, no lookup is needed to know it is fabricated.
 *
 * <p>The budget comes last, so what gets cut is the worst-ranked remainder rather than something
 * chosen earlier.
 */
@Service
public class ContextAssembler {

    private final ContextSettings settings;

    public ContextAssembler(ContextSettings settings) {
        this.settings = settings;
    }

    public AssembledContext assemble(String jobId, List<Hit> hits) {
        List<ContextChunk> accepted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int duplicates = 0;
        int characters = 0;

        for (Hit hit : hits) {
            String fingerprint = fingerprint(hit.text());
            if (!seen.add(fingerprint)) {
                duplicates++;
                continue;
            }
            if (accepted.size() >= settings.maxChunks()
                    || characters + hit.text().length() > settings.maxCharacters()) {
                continue;
            }
            characters += hit.text().length();
            accepted.add(ContextChunk.from(hit, accepted.size() + 1));
        }

        return new AssembledContext(jobId, accepted, duplicates,
                hits.size() - duplicates - accepted.size());
    }

    /**
     * Identity for deduplication, not equality.
     *
     * <p>Whitespace is collapsed and case folded, because a PDF and the DOCX of the same manual
     * differ in line breaks and capitalisation and are the same fragment — deduplicating on the raw
     * string would miss exactly the duplicates this exists to catch. Character-level identity would
     * go further and treat paraphrases as one fragment, which is not what this is for.
     *
     * <p>A blank fragment fingerprints to the empty string, so all blanks collapse to one. That is
     * the right outcome: a chunk with no text is worth no context budget, and dropping it silently
     * would hide a parsing problem behind a smaller prompt.
     */
    static String fingerprint(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    public ContextSettings settings() {
        return settings;
    }
}