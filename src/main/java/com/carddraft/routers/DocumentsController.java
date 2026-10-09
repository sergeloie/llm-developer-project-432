package com.carddraft.routers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.carddraft.documents.ChunkingSettings;
import com.carddraft.documents.DocumentService;
import com.carddraft.repositories.DocumentsRepository;
import com.carddraft.temporal.DocumentWorkflowService;

/**
 * Document upload and state.
 *
 * <p>202 and an identifier, not the parsed result. Parsing is real work over a file of unknown
 * size, and answering it inline would hold a connection open for as long as it takes — which for a
 * large supplier document is minutes, and for every other caller on the connection is a wait they
 * did not ask for.
 *
 * <p>The upload starts the parse and returns. From there the document is readable at
 * {@code GET /documents/{id}} while it moves from new to indexed or rejected, which is the same
 * thing the metrics harness waits on and the same thing a content manager would watch. Registering
 * the document and starting the parse are one action from the caller's side, and splitting them
 * into two would make forgetting the second one look like success: a document with no chunks and
 * nothing working on it is indistinguishable from one still being worked on.
 */
@RestController
@RequestMapping("/documents")
public class DocumentsController {

    private final DocumentService documentService;
    private final DocumentWorkflowService parses;
    private final ChunkingSettings settings;

    public DocumentsController(DocumentService documentService, DocumentWorkflowService parses,
                               ChunkingSettings settings) {
        this.documentService = documentService;
        this.parses = parses;
        this.settings = settings;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > settings.maxUploadBytes()) {
            throw new ApiRefusalException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "the file is larger than the "
                            + (settings.maxUploadBytes() / (1024 * 1024)) + " MB limit");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!documentService.supports(filename)) {
            throw new IllegalArgumentException("unsupported file type: " + filename
                    + ". Supported: " + String.join(",", documentService.supportedFormats()));
        }
        try {
            DocumentsRepository.DocumentRow document =
                    documentService.register(filename, file.getBytes());
            parses.start(document.id());
            return ResponseEntity.accepted().body(describe(document));
        } catch (IOException e) {
            throw new IllegalArgumentException("the upload could not be read", e);
        }
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<Map<String, Object>> state(@PathVariable String documentId) {
        return documentService.find(documentId)
                .map(document -> ResponseEntity.ok(describe(document)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The chunks themselves, so a client can show what will be searched. */
    @GetMapping("/{documentId}/chunks")
    public ResponseEntity<Map<String, Object>> chunks(@PathVariable String documentId) {
        if (documentService.find(documentId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("chunks", documentService.chunksOf(documentId)));
    }

    private Map<String, Object> describe(DocumentsRepository.DocumentRow document) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", document.id());
        body.put("filename", document.filename());
        body.put("state", document.state());
        body.put("chunkCount", document.chunkCount());
        body.put("sizeBytes", document.sizeBytes());
        if (document.rejectionReason() != null) {
            body.put("rejectionReason", document.rejectionReason());
        }
        return body;
    }
}
