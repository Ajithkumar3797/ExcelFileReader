package com.kalsec.rag.controller;

import com.kalsec.rag.dto.IngestResponse;
import com.kalsec.rag.service.ChromaDbService;
import com.kalsec.rag.service.IngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService  ingestService;
    private final ChromaDbService chromaDb;

    /**
     * POST /api/ingest/upload
     * Upload and ingest an Excel file.
     *
     * curl -F "file=@Kalsec_...xlsx" http://localhost:8080/api/ingest/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<IngestResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            // Save to temp file
            Path tmp = Files.createTempFile("kalsec_", ".xlsx");
            file.transferTo(tmp);
            log.info("Uploaded file saved to: {}", tmp);

            IngestResponse resp = ingestService.ingest(tmp.toString());
            Files.deleteIfExists(tmp);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("Ingestion failed", e);
            return ResponseEntity.internalServerError()
                    .body(IngestResponse.builder().message("Error: " + e.getMessage()).build());
        }
    }

    /**
     * POST /api/ingest/path
     * Ingest a file already on the server filesystem.
     * Body: { "filePath": "/absolute/path/to/file.xlsx" }
     *
     * curl -X POST http://localhost:8080/api/ingest/path \
     *      -H "Content-Type: application/json" \
     *      -d '{"filePath":"/data/Kalsec_...xlsx"}'
     */
    @PostMapping("/path")
    public ResponseEntity<IngestResponse> ingestByPath(@RequestBody Map<String, String> body) {
        String filePath = body.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(IngestResponse.builder().message("filePath is required").build());
        }
        if (!new File(filePath).exists()) {
            return ResponseEntity.badRequest()
                    .body(IngestResponse.builder().message("File not found: " + filePath).build());
        }
        try {
            return ResponseEntity.ok(ingestService.ingest(filePath));
        } catch (Exception e) {
            log.error("Ingestion failed", e);
            return ResponseEntity.internalServerError()
                    .body(IngestResponse.builder().message("Error: " + e.getMessage()).build());
        }
    }

    /**
     * GET /api/ingest/status
     * Returns the number of chunks currently stored in ChromaDB.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        try {
            long count = chromaDb.count();
            return ResponseEntity.ok(Map.of(
                    "chunksStored", count,
                    "ready", count > 0
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("chunksStored", 0, "ready", false,
                    "error", e.getMessage()));
        }
    }
}
