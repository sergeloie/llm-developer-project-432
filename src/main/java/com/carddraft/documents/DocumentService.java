package com.carddraft.documents;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.carddraft.agents.Chunk;
import com.carddraft.agents.StructuralUnit;
import com.carddraft.repositories.DocumentsRepository;

/**
 * Parse, normalise, cut, store — and say plainly when it could not.
 *
 * <p>Document state moves as the work happens rather than only at the end, because parsing a
 * hundred-document set takes long enough that a client watching a single "new" would have no way
 * to tell progress from a hang.
 *
 * <p>A refusal is a first-class outcome carrying a reason. The alternative — returning an empty
 * result — produces a card with nothing in it and no explanation, which a content manager cannot
 * act on and cannot distinguish from the service being broken.
 */
@Service
public class DocumentService {

    private final List<DocumentParser> parsers;
    private final TextNormaliser normaliser;
    private final Chunker chunker;
    private final DocumentsRepository documents;

    public DocumentService(List<DocumentParser> parsers, TextNormaliser normaliser,
                           Chunker chunker, DocumentsRepository documents) {
        this.parsers = parsers;
        this.normaliser = normaliser;
        this.chunker = chunker;
        this.documents = documents;
    }

    /**
     * Registers a document, or returns the existing one when this exact content has been seen.
     *
     * <p>Idempotent on content, so a client that retries an upload after a timeout does not get a
     * second document and a second set of chunks.
     */
    public DocumentsRepository.DocumentRow register(String filename, byte[] content) {
        String hash = sha256(content);
        Optional<DocumentsRepository.DocumentRow> existing = documents.findByContentHash(hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        String id = "doc-" + hash.substring(0, 12);
        try {
            return documents.create(id, filename, hash, content.length);
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            return documents.findByContentHash(hash)
                    .orElseThrow(() -> new IllegalStateException("document " + id + " vanished after insert", raced));
        }
    }

    /**
     * Parses, cuts and stores. Never throws for a document it cannot read: the refusal is recorded
     * on the document and returned, because a batch upload must not be undone by one bad file.
     */
    public DocumentsRepository.DocumentRow process(String documentId, String filename, byte[] content) {
        documents.markParsing(documentId);
        try {
            DocumentParser parser = parserFor(filename);
            List<StructuralUnit> units = parser.parse(content);
            List<Chunk> chunks = chunker.chunk(documentId, units);

            documents.deleteChunks(documentId);
            documents.insertChunks(documentId, chunks.stream()
                    .map(c -> new DocumentsRepository.ChunkRow(0, c.documentId(), c.ordinal(),
                            c.page(), c.section(), c.text(), c.table()))
                    .toList());
            return documents.markIndexed(documentId, chunks.size());
        } catch (DocumentParser.DocumentRejectedException rejected) {
            return documents.markRejected(documentId, rejected.reason());
        }
    }

    public Optional<DocumentsRepository.DocumentRow> find(String documentId) {
        return documents.findById(documentId);
    }

    public java.util.List<DocumentsRepository.ChunkRow> chunksOf(String documentId) {
        return documents.chunksOf(documentId);
    }

    private DocumentParser parserFor(String filename) {
        return parsers.stream()
                .filter(parser -> parser.supports(filename))
                .findFirst()
                .orElseThrow(() -> new DocumentParser.DocumentRejectedException(
                        "unsupported file type", filename));
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
