package com.carddraft.documents;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carddraft.agents.Chunk;
import com.carddraft.agents.StructuralUnit;

/**
 * Cuts structural units into chunks.
 *
 * <p>Two rules, and the second is the one that gets forgotten. Prose is sliced with an overlap,
 * because a statement that lands on a boundary is otherwise lost — the chunk that held the first
 * half no longer contains the claim, and the one holding the second half is missing the subject.
 * Table rows are never sliced, because half a specification row says nothing.
 */
@Component
public class Chunker {

    private final ChunkingSettings settings;

    public Chunker(ChunkingSettings settings) {
        this.settings = settings;
    }

    public List<Chunk> chunk(String documentId, List<StructuralUnit> units) {
        List<Chunk> chunks = new ArrayList<>();
        int ordinal = 0;

        for (StructuralUnit unit : units) {
            if (unit.table()) {
                chunks.add(new Chunk(documentId, ordinal++, unit.page(), unit.section(), unit.text(), true));
                continue;
            }
            ordinal = sliceProse(documentId, ordinal, unit, chunks);
        }
        return chunks;
    }

    private int sliceProse(String documentId, int ordinal, StructuralUnit unit, List<Chunk> chunks) {
        String text = unit.text().strip();
        if (text.isEmpty()) {
            return ordinal;
        }
        int size = settings.chunkSize();
        int overlap = Math.min(settings.chunkOverlap(), size - 1);
        int start = 0;
        int nextOrdinal = ordinal;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            String slice = text.substring(start, end).strip();
            if (!slice.isEmpty()) {
                chunks.add(new Chunk(documentId, nextOrdinal++, unit.page(), unit.section(), slice, false));
            }
            if (end == text.length()) {
                break;
            }
            start = end - overlap;
        }
        return nextOrdinal;
    }
}
