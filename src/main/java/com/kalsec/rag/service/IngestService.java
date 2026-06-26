package com.kalsec.rag.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kalsec.rag.dto.IngestResponse;
import com.kalsec.rag.model.ExcelChunk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    @Value("${ingest.embed-batch-size:96}")
    private int embedBatchSize;

    /** Maximum number of times to retry a single batch on 429 before giving up. */
    private static final int MAX_RETRIES = 5;

    /**
     * Extra buffer added on top of the wait time parsed from the API error message.
     * Provides a cushion so we don't hit the limit again immediately after resuming.
     */
    private static final long RETRY_BUFFER_MS = 500;

    /**
     * Fallback wait time (ms) when no retry-after duration can be parsed from the
     * error message. 60 s is a safe default for OpenAI TPM windows.
     */
    private static final long FALLBACK_WAIT_MS = 60_000;

    /**
     * Matches durations like "1.816s", "2s", "0.5s" that OpenAI embeds in 429
     * error messages ("Please try again in 1.816s").
     */
    private static final Pattern RETRY_AFTER_PATTERN =
            Pattern.compile("try again in\\s+([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);

    private final ExcelReaderService excelReader;
    private final OpenAiService      openAi;
    private final ChromaDbService    chromaDb;

    /**
     * Full pipeline: read Excel → chunk → embed (with 429 back-off) → store.
     *
     * @param filePath absolute path to the .xlsx file
     */
    public IngestResponse ingest(String filePath) throws Exception {
        log.info("Starting ingestion of: {}", filePath);

        // 1. Read and chunk
        List<ExcelChunk> chunks = excelReader.readAndChunk(filePath);
        log.info("Produced {} total chunks", chunks.size());

        if (chunks.isEmpty()) {
            return IngestResponse.builder()
                    .totalChunks(0)
                    .message("No data extracted from the file.")
                    .build();
        }

        // 2. Reset (or create) the collection
        chromaDb.resetCollection();

        // 3. Embed in batches — retries on 429 with back-off
        List<String> texts = chunks.stream().map(ExcelChunk::getText).collect(Collectors.toList());
        log.info("Embedding {} chunks in batches of {}", texts.size(), embedBatchSize);

        int failedBatches = 0;
        int totalBatches  = (texts.size() - 1) / embedBatchSize + 1;

        for (int start = 0; start < texts.size(); start += embedBatchSize) {
            int end   = Math.min(start + embedBatchSize, texts.size());
            int batch = start / embedBatchSize + 1;

            log.info("  Embedding batch {}/{} ({} texts) …", batch, totalBatches, end - start);

            List<String>       batchTexts   = texts.subList(start, end);
            List<ExcelChunk>   batchChunks  = chunks.subList(start, end);
            List<List<Double>> batchEmbeddings = null;

            // --- retry loop for this batch ---
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    batchEmbeddings = openAi.embedBatch(batchTexts);

                    // Validate — every returned embedding must be non-empty
                    for (int i = 0; i < batchEmbeddings.size(); i++) {
                        if (batchEmbeddings.get(i) == null || batchEmbeddings.get(i).isEmpty()) {
                            throw new RuntimeException(
                                "OpenAI returned empty embedding for chunk index " + (start + i));
                        }
                    }

                    // Success — attach embeddings and break out of retry loop
                    for (int i = 0; i < batchChunks.size(); i++) {
                        batchChunks.get(i).setEmbedding(batchEmbeddings.get(i));
                    }
                    break;

                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    boolean isRateLimit = msg.contains("429") || msg.toLowerCase().contains("rate_limit");

                    if (isRateLimit && attempt < MAX_RETRIES) {
                        long waitMs = parseRetryAfterMs(msg);
                        log.warn("  Batch {}/{} rate-limited (attempt {}/{}). " +
                                 "Waiting {}ms before retrying …",
                                 batch, totalBatches, attempt, MAX_RETRIES, waitMs);
                        Thread.sleep(waitMs);
                        // Loop back and retry the same batch

                    } else {
                        // Non-429 error, or exhausted retries — skip this batch
                        log.error("  Embedding batch {}/{} failed after {} attempt(s) — " +
                                  "SKIPPING {} chunks. Reason: {}",
                                  batch, totalBatches, attempt, batchTexts.size(), msg);
                        failedBatches++;
                        batchChunks.forEach(c -> c.setEmbedding(null));
                        break;
                    }
                }
            }

            // Gentle inter-batch delay to stay under the per-minute token limit
            if (end < texts.size()) Thread.sleep(200);
        }

        // 4. Filter out any chunks that failed embedding
        List<ExcelChunk> embeddedChunks = chunks.stream()
                .filter(c -> c.getEmbedding() != null && !c.getEmbedding().isEmpty())
                .collect(Collectors.toList());

        if (embeddedChunks.isEmpty()) {
            return IngestResponse.builder()
                    .totalChunks(0)
                    .message("Embedding failed for all chunks. Check your OPENAI_API_KEY and model name.")
                    .build();
        }

        log.info("Successfully embedded {}/{} chunks ({} failed batches)",
                embeddedChunks.size(), chunks.size(), failedBatches);

        // 5. Insert into ChromaDB in batches
        log.info("Inserting {} chunks into ChromaDB …", embeddedChunks.size());
        int insertBatch = 500;
        for (int start = 0; start < embeddedChunks.size(); start += insertBatch) {
            int end = Math.min(start + insertBatch, embeddedChunks.size());
            chromaDb.insertChunks(embeddedChunks.subList(start, end));
            log.info("  Inserted chunks {}-{}", start, end);
        }

        // Collect metadata for response
        List<String> processedSheets = embeddedChunks.stream()
                .map(ExcelChunk::getSheetName)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        String message = failedBatches == 0
                ? "Ingestion successful."
                : String.format("Ingestion completed with %d failed embedding batch(es). Check logs.", failedBatches);

        log.info("Ingestion complete. {} chunks stored.", embeddedChunks.size());
        return IngestResponse.builder()
                .totalChunks(embeddedChunks.size())
                .sheetsProcessed(processedSheets.size())
                .processedSheets(processedSheets)
                .message(message)
                .build();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Parses the wait duration from an OpenAI 429 error message such as:
     * "Please try again in 1.816s."
     *
     * Returns the parsed milliseconds plus {@link #RETRY_BUFFER_MS}, or
     * {@link #FALLBACK_WAIT_MS} when no duration can be found in the message.
     */
    private long parseRetryAfterMs(String errorMessage) {
        if (errorMessage != null) {
            Matcher m = RETRY_AFTER_PATTERN.matcher(errorMessage);
            if (m.find()) {
                double seconds = Double.parseDouble(m.group(1));
                long waitMs = (long) (seconds * 1000) + RETRY_BUFFER_MS;
                log.debug("  Parsed retry-after: {}s → waiting {}ms", seconds, waitMs);
                return waitMs;
            }
        }
        log.debug("  Could not parse retry-after from error; using fallback {}ms", FALLBACK_WAIT_MS);
        return FALLBACK_WAIT_MS;
    }
}