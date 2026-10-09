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
 * Parse, chunk and store — and say plainly when it could not.
 *
 * <p>Document state moves as the work happens rather than only at the end, because parsing a
 * hundred-document set takes long enough that a client watching a single "new" would have no way
 * to tell progress from a hang.
 *
 * <p>A parsed document is left at {@code parsing}, not {@code indexed}, and the difference is the
 * point of the vocabulary: {@code indexed} is what tells a caller the document can be *searched*, and
 * a stored chunk with no vector is invisible to the vector index however much text it holds. The
 * vectors are written by the indexing step that follows, and the state moves when they are.
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
     * Records a document and the bytes it arrived as, or returns the existing one when this exact
     * content has been seen.
     *
     * <p>Idempotent on content, so a client that retries an upload after a timeout does not get a
     * second document and a second set of chunks.
     *
     * <p>The content is stored rather than handed on, which is what makes this the whole of what an
     * upload does. Parsing happens later and somewhere else — a retryable step, after the request
     * that carried the bytes has been answered — so the file has to be somewhere it can be read
     * from again.
     */
    public DocumentsRepository.DocumentRow register(String filename, byte[] content) {
        String hash = sha256(content);
        Optional<DocumentsRepository.DocumentRow> existing = documents.findByContentHash(hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        String id = "doc-" + hash.substring(0, 12);
        try {
            return documents.create(id, filename, hash, content.length, content);
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            return documents.findByContentHash(hash)
                    .orElseThrow(() -> new IllegalStateException("document " + id + " vanished after insert", raced));
        }
    }

    /**
     * Parses, cuts and stores, reading the file back rather than being handed it.
     *
     * <p>Never throws for a document it cannot read: the refusal is recorded on the document and
     * returned, because a batch upload must not be undone by one bad file.
     *
     * <p>Content that is not there is one of those refusals rather than an error. A row can predate
     * the column, and a document that cannot be parsed because its content is gone is a document
     * with a stated reason — not a document that quietly produces no chunks and reports success.
     */
    public DocumentsRepository.DocumentRow process(String documentId) {
        DocumentsRepository.DocumentRow document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("no document " + documentId));

        Optional<byte[]> stored = documents.contentOf(documentId);
        if (stored.isEmpty()) {
            return documents.markRejected(documentId, "the stored content is no longer available");
        }
        byte[] content = stored.get();

        documents.markParsing(documentId);
        List<StructuralUnit> units;
        List<Chunk> chunks;
        try {
            DocumentParser parser = parserFor(document.filename());
            units = parser.parse(content);
            chunks = chunker.chunk(documentId, units);
        } catch (DocumentParser.DocumentRejectedException rejected) {
            return documents.markRejected(documentId, rejected.reason());
        } catch (RuntimeException corrupt) {
            return documents.markRejected(documentId,
                    "the file could not be parsed: " + corrupt.getMessage());
        }
        if (chunks.isEmpty()) {
            return documents.markRejected(documentId,
                    "the file was parsed but yielded no searchable fragments");
        }
        try {
            documents.deleteChunks(documentId);
            documents.insertChunks(documentId, chunks.stream()
                    .map(c -> new DocumentsRepository.ChunkRow(0, c.documentId(), c.ordinal(),
                            c.page(), c.section(), c.text(), c.table()))
                    .toList());
            return documents.findById(documentId).orElseThrow();
        } catch (DocumentParser.DocumentRejectedException rejected) {
            return documents.markRejected(documentId, rejected.reason());
        }
    }

    /**
     * Moves the document to {@code indexed} once its chunks can all be found.
     *
     * <p>The condition and the write are one statement inside the repository, so this cannot be
     * talked into claiming a document is searchable while a vector is still missing.
     */
    public DocumentsRepository.DocumentRow settle(String documentId) {
        return documents.markIndexedIfComplete(documentId);
    }

    public Optional<DocumentsRepository.DocumentRow> find(String documentId) {
        return documents.findById(documentId);
    }

    public java.util.List<DocumentsRepository.ChunkRow> chunksOf(String documentId) {
        return documents.chunksOf(documentId);
    }

    /**
     * Whether any parser handles the named file.
     *
     * <p>Asked of the parsers rather than answered from a list kept here, so the upload path and
     * the message it refuses with cannot disagree with what parsing would actually accept.
     */
    public boolean supports(String filename) {
        return parsers.stream().anyMatch(parser -> parser.supports(filename));
    }

    /**
     * The extensions the parsers accept, without their leading dot, sorted for a stable message.
     */
    public List<String> supportedFormats() {
        return parsers.stream()
                .flatMap(parser -> parser.extensions().stream())
                .map(extension -> extension.startsWith(".") ? extension.substring(1) : extension)
                .distinct()
                .sorted()
                .toList();
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
