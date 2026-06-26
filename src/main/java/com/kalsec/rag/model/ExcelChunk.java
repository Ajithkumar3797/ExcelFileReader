package com.kalsec.rag.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ExcelChunk {
    private String       id;
    private String       sheetName;
    private int          chunkIndex;
    private String       text;
    private List<Double> embedding;
}
