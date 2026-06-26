package com.kalsec.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kalsec.rag.dto.ChatResponse.RetrievedChunk;
import com.kalsec.rag.model.ExcelChunk;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChromaDbService {

    private static final MediaType JSON     = MediaType.get("application/json; charset=utf-8");
    private static final String    TENANT   = "default_tenant";
    private static final String    DATABASE = "default_database";

    @Value("${chroma.base-url}")
    private String baseUrl;

    @Value("${chroma.collection.name}")
    private String collectionName;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String collectionId;  // ChromaDB internal UUID

    /** Full path prefix for all collection-scoped calls (ChromaDB 0.6+ API v2) */
    private String apiBase() {
        return "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE;
    }

    // -----------------------------------------------------------------------
    // Initialisation — ensure tenant → database → collection all exist
    // -----------------------------------------------------------------------

    @PostConstruct
    public void init() {
        try {
            ensureTenant();
            ensureDatabase();
            collectionId = getOrCreateCollection();
            log.info("ChromaDB ready. Collection '{}' id={}", collectionName, collectionId);
        } catch (Exception e) {
            log.warn("ChromaDB not reachable at startup ({}). Will retry on first request.", e.getMessage());
        }
    }

    /** Creates the tenant if it doesn't already exist. */
    private void ensureTenant() {
        try {
            get("/api/v2/tenants/" + TENANT);
            log.debug("Tenant '{}' already exists", TENANT);
        } catch (Exception e) {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("name", TENANT);
                post("/api/v2/tenants", body.toString());
                log.info("Created tenant '{}'", TENANT);
            } catch (Exception ex) {
                log.warn("Could not create tenant '{}': {}", TENANT, ex.getMessage());
            }
        }
    }

    /** Creates the database inside the tenant if it doesn't already exist. */
    private void ensureDatabase() {
        try {
            get("/api/v2/tenants/" + TENANT + "/databases/" + DATABASE);
            log.debug("Database '{}' already exists", DATABASE);
        } catch (Exception e) {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("name", DATABASE);
                post("/api/v2/tenants/" + TENANT + "/databases", body.toString());
                log.info("Created database '{}'", DATABASE);
            } catch (Exception ex) {
                log.warn("Could not create database '{}': {}", DATABASE, ex.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Collection management
    // -----------------------------------------------------------------------

    public void resetCollection() throws Exception {
        ensureTenant();
        ensureDatabase();
        try {
            delete(apiBase() + "/collections/" + collectionName);
            log.info("Deleted existing collection '{}'", collectionName);
        } catch (Exception ignored) {}
        collectionId = createCollection();
    }

    private String getOrCreateCollection() throws Exception {
        try {
            String resp = get(apiBase() + "/collections/" + collectionName);
            return objectMapper.readTree(resp).get("id").asText();
        } catch (Exception e) {
            return createCollection();
        }
    }

    private String createCollection() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", collectionName);
        ObjectNode meta = body.putObject("metadata");
        meta.put("hnsw:space", "cosine");

        String resp = post(apiBase() + "/collections", body.toString());
        String id   = objectMapper.readTree(resp).get("id").asText();
        log.info("Created collection '{}' id={}", collectionName, id);
        return id;
    }

    // -----------------------------------------------------------------------
    // Insert
    // -----------------------------------------------------------------------

    public void insertChunks(List<ExcelChunk> chunks) throws Exception {
        if (collectionId == null) collectionId = getOrCreateCollection();

        ObjectNode body       = objectMapper.createObjectNode();
        ArrayNode  ids        = body.putArray("ids");
        ArrayNode  docs       = body.putArray("documents");
        ArrayNode  embeddings = body.putArray("embeddings");
        ArrayNode  metadatas  = body.putArray("metadatas");

        for (ExcelChunk chunk : chunks) {
            ids.add(chunk.getId());
            docs.add(chunk.getText());

            ArrayNode vec = embeddings.addArray();
            for (double v : chunk.getEmbedding()) vec.add(v);

            ObjectNode meta = metadatas.addObject();
            meta.put("sheet",      chunk.getSheetName());
            meta.put("chunkIndex", chunk.getChunkIndex());
        }

        post(apiBase() + "/collections/" + collectionId + "/add", body.toString());
        log.debug("Inserted {} chunks", chunks.size());
    }

    // -----------------------------------------------------------------------
    // Query
    // -----------------------------------------------------------------------

    public List<RetrievedChunk> query(List<Double> queryEmbedding, int topK) throws Exception {
        if (collectionId == null) collectionId = getOrCreateCollection();

        ObjectNode body   = objectMapper.createObjectNode();
        ArrayNode  qEmbed = body.putArray("query_embeddings").addArray();
        for (double v : queryEmbedding) qEmbed.add(v);
        body.put("n_results", topK);

        ArrayNode include = body.putArray("include");
        include.add("documents");
        include.add("metadatas");
        include.add("distances");

        String   resp = post(apiBase() + "/collections/" + collectionId + "/query", body.toString());
        JsonNode root = objectMapper.readTree(resp);

        JsonNode docs      = root.get("documents").get(0);
        JsonNode metas     = root.get("metadatas").get(0);
        JsonNode distances = root.get("distances").get(0);

        List<RetrievedChunk> results = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            double   cosineDistance = distances.get(i).asDouble();
            double   relevance      = 1.0 - cosineDistance;
            JsonNode meta           = metas.get(i);

            results.add(RetrievedChunk.builder()
                    .sheet(meta.get("sheet").asText())
                    .chunkIndex(meta.get("chunkIndex").asInt())
                    .relevance(Math.round(relevance * 1000.0) / 1000.0)
                    .text(docs.get(i).asText())
                    .build());
        }
        return results;
    }

    public long count() throws Exception {
        if (collectionId == null) collectionId = getOrCreateCollection();
        String resp = get(apiBase() + "/collections/" + collectionId + "/count");
        return objectMapper.readTree(resp).asLong();
    }

    // -----------------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------------

    private String get(String path) throws Exception {
        Request req = new Request.Builder().url(baseUrl + path).get().build();
        return execute(req);
    }

    private String post(String path, String jsonBody) throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(jsonBody, JSON))
                .build();
        return execute(req);
    }

    private void delete(String path) throws Exception {
        Request req = new Request.Builder().url(baseUrl + path).delete().build();
        execute(req);
    }

    private String execute(Request req) throws Exception {
        try (Response resp = httpClient.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new RuntimeException("ChromaDB error " + resp.code() + ": " + body);
            }
            return body;
        }
    }
}
