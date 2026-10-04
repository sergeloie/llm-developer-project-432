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

/**
 * Document upload and state.
 *
 * <p>202 and an identifier, not the parsed result. Parsing is real work over a file of unknown
 * size, and answering it inline would hold a connection open for as long as it takes.
 */
@RestController
@RequestMapping("/documents")
public class DocumentsController {

    private final DocumentService documentService;
    private final ChunkingSettings settings;

    public DocumentsController(DocumentService documentService, ChunkingSettings settings) {
        this.documentService = documentService;
        this.settings = settings;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > settings.maxUploadBytes()) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "the file is larger than the "
                            + (settings.maxUploadBytes() / (1024 * 1024)) + " MB limit"));
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!isSupported(filename)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unsupported file type: " + filename
                            + ". Supported: " + settings.formats()));
        }
        try {
            DocumentsRepository.DocumentRow document =
                    documentService.register(filename, file.getBytes());
            return ResponseEntity.accepted().body(describe(document));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "the upload could not be read"));
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

    private boolean isSupported(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx")
                || lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }
}
