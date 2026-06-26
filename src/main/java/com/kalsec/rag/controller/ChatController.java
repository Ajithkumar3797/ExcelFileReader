package com.kalsec.rag.controller;

import com.kalsec.rag.dto.ChatRequest;
import com.kalsec.rag.dto.ChatResponse;
import com.kalsec.rag.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * POST /api/chat
     * Ask a question about the Kalsec contract data.
     *
     * Single-turn example:
     * curl -X POST http://localhost:8080/api/chat \
     *      -H "Content-Type: application/json" \
     *      -d '{"question":"What is the total net spend for Kalsec?"}'
     *
     * Multi-turn example (pass history from previous response):
     * curl -X POST http://localhost:8080/api/chat \
     *      -H "Content-Type: application/json" \
     *      -d '{
     *            "question": "What about the proposed discount?",
     *            "history": [
     *              {"role":"user",      "content":"What is the total net spend?"},
     *              {"role":"assistant", "content":"The total net spend is $64,573.10."}
     *            ]
     *          }'
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        try {
            ChatResponse response = chatService.answer(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Chat error", e);
            return ResponseEntity.internalServerError()
                    .body(ChatResponse.builder()
                            .answer("An error occurred: " + e.getMessage())
                            .build());
        }
    }
}
