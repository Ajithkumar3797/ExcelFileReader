package com.kalsec.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class IngestResponse {
    private int    totalChunks;
    private int    sheetsProcessed;
    private int    sheetsSkipped;
    private List<String> processedSheets;
    private String message;
}
