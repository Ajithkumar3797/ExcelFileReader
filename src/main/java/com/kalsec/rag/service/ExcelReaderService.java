package com.kalsec.rag.service;

import com.kalsec.rag.model.ExcelChunk;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

@Slf4j
@Service
public class ExcelReaderService {

    @Value("${ingest.chunk-size:100}")
    private int chunkSize = 100;

    private static final int TARGET_TOKENS_PER_CHUNK = 1500;
    private static final int MAX_COLS = 500;
    private static final int MAX_ROWS = 500_000;

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENC = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    private static final Set<String> PIVOT_SHEETS = Set.of(
            "Executive Summary", "ServZoneD", "WeightZoneD"
    );

    private static final Set<String> SKIP_SHEETS = Set.of(
            "NDAEARLY", "NDA", "NDASV", "2DA", "3DASL",
            "GroundCommercial", "GroundResidential", "GroundReturns",
            "WorldwideExpeditedExport", "WorldwideExpeditedImport",
            "WorldwideExpressExport", "DOC_WorldwideExpeditedExport",
            "DOC_WorldwideExpressExport", "DOC_WorldwideSaverExport",
            "WorldwideExpressImport", "WorldwideSaverExport",
            "WorldwideSaverImport", "WorldwideStandardExport",
            "Dims", "Exclude Tab"
    );

    private FormulaEvaluator evaluator;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public List<ExcelChunk> readAndChunk(String filePath) throws Exception {
        log.info("Loading workbook: {}", filePath);
        List<ExcelChunk> all = new ArrayList<>();

        try (InputStream is = new FileInputStream(new File(filePath));
             Workbook wb   = new XSSFWorkbook(is)) {

            evaluator = wb.getCreationHelper().createFormulaEvaluator();

            log.info("Total sheets: {}", wb.getNumberOfSheets());
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                log.info("  Sheet[{}]: '{}'", si, wb.getSheetAt(si).getSheetName());
            }

            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet  sheet = wb.getSheetAt(si);
                String name  = sheet.getSheetName();

                boolean shouldSkip = SKIP_SHEETS.stream()
                        .anyMatch(s -> s.trim().equalsIgnoreCase(name.trim()));
                if (shouldSkip) {
                    log.info("  Skipping (blocklist): '{}'", name);
                    continue;
                }

                int physicalRows = sheet.getPhysicalNumberOfRows();
                int lastCol      = 0;
                Row firstRow     = sheet.getRow(sheet.getFirstRowNum());
                if (firstRow != null) lastCol = firstRow.getLastCellNum();

                if (physicalRows > MAX_ROWS) {
                    log.warn("  Skipping '{}': {} rows exceeds MAX_ROWS={}", name, physicalRows, MAX_ROWS);
                    continue;
                }
                if (lastCol > MAX_COLS) {
                    log.warn("  Skipping '{}': {} columns exceeds MAX_COLS={}", name, lastCol, MAX_COLS);
                    continue;
                }

                log.info("  Processing '{}' ({} rows, ~{} cols) …", name, physicalRows, lastCol);

                List<ExcelChunk> sheetChunks;
                if (PIVOT_SHEETS.stream().anyMatch(p -> p.trim().equalsIgnoreCase(name.trim()))) {
                    sheetChunks = readPivotStreaming(sheet, name, all.size());
                } else {
                    sheetChunks = readTabularStreaming(sheet, name, all.size());
                }

                log.info("  → {} chunks from '{}'", sheetChunks.size(), name);
                all.addAll(sheetChunks);
            }
        }

        log.info("Total chunks produced: {}", all.size());
        return all;
    }

    // -----------------------------------------------------------------------
    // Tabular reader — multi-section aware
    //
    // The key fix: sheets like "Final Summary" contain multiple logical
    // sections separated by blank rows. Each section has its OWN header row.
    //
    // Old behaviour: the very first non-blank row was locked in as headers
    // forever. So "Type | UPS to UPS" (row 1) became the headers for ALL
    // rows including the main data table which starts at row 10 with proper
    // headers "SERVICE/CHARGE TYPE | VOLUME | BASELINE CALCULATED SPEND ...".
    // Every data row was therefore chunked with wrong column names, making
    // the bottom section completely unsearchable.
    //
    // Fix: after any blank row(s), if the next non-blank row looks like a
    // header (majority of cells are text, not numbers), flush the current
    // chunk and adopt it as the new header for the next section.
    // -----------------------------------------------------------------------

    private List<ExcelChunk> readTabularStreaming(Sheet sheet, String sheetName, int idOffset) {
        List<ExcelChunk> chunks = new ArrayList<>();

        String[]      headers          = null;
        StringBuilder body             = new StringBuilder();
        int           rowStart         = 0;
        int           runningTok       = 0;
        int           rowsInChunk      = 0;
        int           chunkIndex       = 0;
        boolean       pendingNewSection = false;

        // Determine true column width from the sheet's own metadata — not from
        // the first non-blank row, which may be narrower than later sections.
        // sheet.getRow(r).getLastCellNum() can vary row-by-row; using the POI
        // sheet-level max guarantees every section's columns are captured.
        int maxCols = 0;
        for (Row r : sheet) {
            if (r != null && r.getLastCellNum() > maxCols)
                maxCols = r.getLastCellNum();
        }
        maxCols = Math.min(maxCols, MAX_COLS);
        log.info("  '{}' maxCols resolved to {}", sheetName, maxCols);

        for (Row row : sheet) {

            // ── Blank row: flag that a section boundary may follow ───────────
            if (isBlankRow(row)) {
                if (headers != null) pendingNewSection = true;
                continue;
            }

            String[] cells = readRow(row, maxCols);

            // ── After blank(s): check if this row starts a new section ───────
            if (pendingNewSection) {
                pendingNewSection = false;

                if (looksLikeHeader(cells)) {
                    // Flush whatever has accumulated from the previous section
                    if (rowsInChunk > 0) {
                        chunks.add(makeChunk(
                                idOffset + chunks.size(), sheetName, chunkIndex++,
                                finalizeChunk(sheetName, rowStart, rowStart + rowsInChunk, body)));
                        body.setLength(0);
                        rowsInChunk = 0;
                        runningTok  = 0;
                        rowStart    = 0;
                    }

                    // Adopt this row as the header for the new section
                    headers = cells;
                    log.info("  New section in '{}', headers: {}", sheetName,
                            Arrays.toString(Arrays.copyOf(headers, Math.min(headers.length, 5))));
                    continue;
                }
                // Not a header row — just a data row that follows blank rows; fall through
            }

            // ── Very first non-blank row ever seen becomes initial header ─────
            if (headers == null) {
                if (looksLikeHeader(cells)) {
                    headers = cells;
                    log.debug("  Initial headers in '{}': {}", sheetName,
                            Arrays.toString(Arrays.copyOf(headers, Math.min(headers.length, 5))));
                    continue;
                } else {
                    // First row is data — use positional column names as headers
                    headers = new String[maxCols];
                    for (int c = 0; c < maxCols; c++) headers[c] = "Col" + c;
                    log.debug("  No header row in '{}' — using positional headers", sheetName);
                    // Fall through so this row is processed as data below
                }
            }

            // ── Normal data row ───────────────────────────────────────────────
            String line = buildLine(headers, cells);
            if (line.isBlank()) continue;

            int lineTok = tokenCount(line);

            // Flush if token budget exceeded
            if (rowsInChunk > 0 && runningTok + lineTok > TARGET_TOKENS_PER_CHUNK) {
                chunks.add(makeChunk(
                        idOffset + chunks.size(), sheetName, chunkIndex++,
                        finalizeChunk(sheetName, rowStart, rowStart + rowsInChunk, body)));
                body.setLength(0);
                rowStart    = rowStart + rowsInChunk;
                rowsInChunk = 0;
                runningTok  = 0;
            }

            body.append(line).append("\n");
            runningTok += lineTok;
            rowsInChunk++;

            // Single oversized row — flush immediately
            if (lineTok > TARGET_TOKENS_PER_CHUNK) {
                chunks.add(makeChunk(
                        idOffset + chunks.size(), sheetName, chunkIndex++,
                        finalizeChunk(sheetName, rowStart, rowStart + rowsInChunk, body)));
                body.setLength(0);
                rowStart    = rowStart + rowsInChunk;
                rowsInChunk = 0;
                runningTok  = 0;
            }
        }

        // Flush remainder
        if (rowsInChunk > 0) {
            chunks.add(makeChunk(
                    idOffset + chunks.size(), sheetName, chunkIndex,
                    finalizeChunk(sheetName, rowStart, rowStart + rowsInChunk, body)));
        }

        return chunks;
    }

    // -----------------------------------------------------------------------
    // Pivot reader (Executive Summary, ServZoneD, WeightZoneD)
    // -----------------------------------------------------------------------

    private List<ExcelChunk> readPivotStreaming(Sheet sheet, String sheetName, int idOffset) {
        List<ExcelChunk> chunks     = new ArrayList<>();
        String[]         headers    = null;
        int              chunkIndex = 0;

        int maxCols = 0;
        for (Row r : sheet) {
            if (r != null && r.getLastCellNum() > maxCols) maxCols = r.getLastCellNum();
        }
        maxCols = Math.min(maxCols, MAX_COLS);

        for (Row row : sheet) {
            if (isBlankRow(row)) continue;

            String[] cells = readRow(row, maxCols);

            if (headers == null) { headers = cells; continue; }

            String metric = cells.length > 0 ? cells[0] : "";
            if (metric.isBlank()) continue;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[Sheet: %s]\nMetric: %s\n", sheetName, metric));
            for (int c = 1; c < Math.min(headers.length, cells.length); c++) {
                String val = cells[c];
                if (val != null && !val.isBlank() && !val.equals("0") && !val.equals("0.0")) {
                    sb.append(headers[c]).append(": ").append(val).append(" | ");
                }
            }

            String text = sb.toString().trim();
            if (text.length() > 60) {
                chunks.add(makeChunk(idOffset + chunks.size(), sheetName, chunkIndex++, text));
            }
        }
        return chunks;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns true if the majority of non-empty cells in this row are text
     * (not numeric). Used to distinguish header rows from data rows when
     * a new section starts after blank rows.
     *
     * e.g. ["SERVICE/CHARGE TYPE","VOLUME","BASELINE CALCULATED SPEND"] → true
     *      ["Ground Commercial","12608","Yes","191246.66"] → false
     */
    private boolean looksLikeHeader(String[] cells) {
        int nonEmpty = 0;
        int textLike = 0;
        for (String cell : cells) {
            if (cell == null || cell.isBlank()) continue;
            nonEmpty++;
            try { Double.parseDouble(cell); }
            catch (NumberFormatException e) { textLike++; }
        }
        return nonEmpty > 0 && (double) textLike / nonEmpty > 0.6;
    }

    private String[] readRow(Row row, int maxCols) {
        String[] cells = new String[maxCols];
        for (int c = 0; c < maxCols; c++) {
            cells[c] = cellValue(row.getCell(c));
        }
        return cells;
    }

    private boolean isBlankRow(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = cellValue(cell);
                if (v != null && !v.isBlank()) return false;
            }
        }
        return true;
    }

    private String cellValue(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell))
                    return cell.getLocalDateTimeCellValue().toString();
                double d = cell.getNumericCellValue();
                return (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case BLANK:   return "";
            case ERROR:   return "";
            default:      return "";
        }
    }

    private String buildLine(String[] headers, String[] row) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < Math.min(headers.length, row.length); c++) {
            String hdr = (headers[c] == null || headers[c].isBlank()) ? "Col" + c : headers[c];
            String val = (row[c] == null) ? "" : row[c];
            if (!val.isBlank()) {
                if (line.length() > 0) line.append(" | ");
                line.append(hdr).append(": ").append(val);
            }
        }
        return line.toString();
    }

    private String finalizeChunk(String sheetName, int start, int end, StringBuilder body) {
    	return String.format("Sheet: %s\n[Rows %d-%d]\n%s", sheetName, start + 1, end, body).trim();
    }

    private int tokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return ENC.countTokens(text);
    }

    private ExcelChunk makeChunk(int globalId, String sheetName, int chunkIndex, String text) {
        return ExcelChunk.builder()
                .id("chunk_" + globalId)
                .sheetName(sheetName)
                .chunkIndex(chunkIndex)
                .text(text)
                .build();
    }
}