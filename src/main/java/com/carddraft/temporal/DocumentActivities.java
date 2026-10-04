package com.carddraft.temporal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.carddraft.documents.DocumentService;

/**
 * Runs document parsing as a durable step.
 *
 * <p>Parsing blocks — it reads a file off disk and writes rows — so it belongs in an activity where
 * blocking is allowed, never in workflow code. The workflow holds only the identifier and the
 * outcome.
 *
 * <p>The bytes travel through the activity rather than through history: a document can be tens of
 * megabytes and history is bounded at roughly fifty megabytes per process, so passing content as
 * an argument would spend the entire budget on one job. The activity re-reads the file from the
 * path it is given, which is why that path is part of the request.
 */
@Component
public class DocumentActivities {

    private final DocumentService documentService;

    public DocumentActivities(DocumentService documentService) {
        this.documentService = documentService;
    }

    public record DocumentResult(String documentId, String state, int chunkCount, String reason) {
    }

    public DocumentResult process(String documentId, String filename, String path) {
        try {
            byte[] content = Files.readAllBytes(Path.of(path));
            var document = documentService.process(documentId, filename, content);
            return new DocumentResult(document.id(), document.state(), document.chunkCount(),
                    document.rejectionReason());
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
