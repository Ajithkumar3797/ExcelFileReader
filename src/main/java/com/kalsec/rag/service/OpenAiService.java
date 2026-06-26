package com.kalsec.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kalsec.rag.config.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String SYSTEM_PROMPT = """
            You are a specialist assistant for the Kalsec Contract Summary Report \
            (period 01/26/2025 – 01/24/2026). You have access to retrieved excerpts \
            from the report that are provided with each question.
            
            Guidelines:
            - Answer ONLY from the retrieved context. If the context does not contain \
              enough information, say so clearly — do NOT guess.
            - Be concise but precise. When quoting numbers, include units and date \
              context from the source.
            - If multiple sheets seem relevant, synthesise the information and note \
              which sheets the data came from.
            - Format monetary values with $ and commas (e.g. $370,942.85).
            """;

    private final OkHttpClient   httpClient;
    private final ObjectMapper   objectMapper;
    private final OpenAiProperties props;

    // -----------------------------------------------------------------------
    // Embeddings
    // -----------------------------------------------------------------------

    /**
     * Embeds a batch of texts using OpenAI's embeddings endpoint.
     * Returns one embedding vector per input string.
     */
    public List<List<Double>> embedBatch(List<String> texts) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getModel().getEmbedding());
        ArrayNode inputArray = body.putArray("input");
        texts.forEach(inputArray::add);

        String url          = props.getApi().getBaseUrl() + "/v1/embeddings";
        String responseJson = post(url, body.toString());
        JsonNode root       = objectMapper.readTree(responseJson);

        List<List<Double>> result = new ArrayList<>();
        for (JsonNode item : root.get("data")) {
            List<Double> vec = new ArrayList<>();
            for (JsonNode val : item.get("embedding")) {
                vec.add(val.asDouble());
            }
            result.add(vec);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Chat completion
    // -----------------------------------------------------------------------

    /**
     * Sends a RAG-augmented message to OpenAI chat completions.
     *
     * @param contextChunks  retrieved text chunks to inject as context
     * @param userQuestion   the user's natural-language question
     * @param history        previous conversation turns (may be null/empty)
     * @return the assistant's answer string
     */
    public String chat(List<String> contextChunks,
                       String userQuestion,
                       List<Map<String, String>> history) throws Exception {

        String contextBlock = String.join("\n\n---\n\n", contextChunks);
        String userContent  = "## Retrieved context\n\n" + contextBlock
                            + "\n\n## Question\n\n" + userQuestion;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model",      props.getModel().getChat());
        body.put("max_tokens", 1024);

        ArrayNode messages = body.putArray("messages");

        // System message
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role",    "system");
        sysMsg.put("content", SYSTEM_PROMPT);

        // Prior history turns
        if (history != null) {
            for (Map<String, String> turn : history) {
                ObjectNode msg = messages.addObject();
                msg.put("role",    turn.get("role"));
                msg.put("content", turn.get("content"));
            }
        }

        // Current user turn with injected context
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role",    "user");
        userMsg.put("content", userContent);

        String url          = props.getApi().getBaseUrl() + "/v1/chat/completions";
        String responseJson = post(url, body.toString());
        JsonNode root       = objectMapper.readTree(responseJson);

        // OpenAI response shape: choices[0].message.content
        return root.get("choices").get(0).get("message").get("content").asText();
    }

    // -----------------------------------------------------------------------
    // HTTP helper
    // -----------------------------------------------------------------------

    private String post(String url, String jsonBody) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + props.getApiKey())
                .addHeader("Content-Type",  "application/json")
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI API error " + response.code() + ": " + body);
            }
            return body;
        }
    }
}
