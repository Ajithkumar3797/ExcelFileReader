package com.kalsec.rag.service;

import com.kalsec.rag.dto.ChatRequest;
import com.kalsec.rag.dto.ChatResponse;
import com.kalsec.rag.dto.ChatResponse.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    @Value("${retrieval.top-k:6}")
    private int topK;

    private final OpenAiService   openAi;
    private final ChromaDbService chromaDb;

    public ChatResponse answer(ChatRequest request) throws Exception {
        String question = request.getQuestion();
        log.info("Question: {}", question);

        // 1. Embed the query
        List<Double> queryEmbedding = openAi.embedBatch(List.of(question)).get(0);

        // 2. Retrieve top-k chunks
        List<RetrievedChunk> retrieved = chromaDb.query(queryEmbedding, topK);
        log.info("Retrieved {} chunks", retrieved.size());

        // 3. Build annotated context strings
        List<String> contextChunks = retrieved.stream()
                .map(c -> String.format(
                        "[Source: Sheet=%s  Chunk=%d  Relevance=%.3f]\n%s",
                        c.getSheet(), c.getChunkIndex(), c.getRelevance(), c.getText()))
                .collect(Collectors.toList());

        // 4. Map conversation history
        List<Map<String, String>> history = null;
        if (request.getHistory() != null && !request.getHistory().isEmpty()) {
            history = new ArrayList<>();
            for (ChatRequest.ConversationTurn turn : request.getHistory()) {
                history.add(Map.of("role", turn.getRole(), "content", turn.getContent()));
            }
        }

        // 5. Call OpenAI
        String answer = openAi.chat(contextChunks, question, history);
        log.info("Answer generated ({} chars)", answer.length());

        return ChatResponse.builder()
                .answer(answer)
                .sources(retrieved)
                .build();
    }
}
