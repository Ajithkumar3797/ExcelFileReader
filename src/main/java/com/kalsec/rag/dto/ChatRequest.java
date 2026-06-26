package com.kalsec.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    @NotBlank(message = "Question must not be blank")
    private String question;

    /**
     * Optional: pass previous turns so Claude can answer follow-up questions.
     * Each element is {"role":"user"|"assistant", "content":"..."}
     */
    private List<ConversationTurn> history;

    @Data
    public static class ConversationTurn {
        private String role;
        private String content;
    }
}
