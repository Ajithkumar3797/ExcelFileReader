package com.kalsec.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatResponse {
    private String answer;
    private List<RetrievedChunk> sources;

    @Data
    @Builder
    public static class RetrievedChunk {
        private String sheet;
        private int    chunkIndex;
        private double relevance;
        private String text;
    }
}
