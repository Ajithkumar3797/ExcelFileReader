# Kalsec Contract Q&A — Spring Boot RAG API

A production-ready **Spring Boot 3** application that ingests the Kalsec Contract
Summary Excel file into **ChromaDB**, then answers natural-language questions about
the contract data using **Anthropic Claude** (RAG pattern).

---

## Architecture

```
Excel (.xlsx)
    │
    ▼  POST /api/ingest/upload  (or /api/ingest/path)
┌─────────────────────────────────────────────────────┐
│  ExcelReaderService  (Apache POI)                   │
│    → read all sheets                                │
│    → clean & chunk (50 rows/chunk or pivot-aware)   │
│    → serialise to text ("col: val | col: val")      │
└──────────────────────┬──────────────────────────────┘
                       │  List<ExcelChunk>
                       ▼
┌─────────────────────────────────────────────────────┐
│  AnthropicService  — embeddings                     │
│    → Voyage-3 via POST /v1/embeddings               │
│    → batches of 96, with rate-limit delay           │
└──────────────────────┬──────────────────────────────┘
                       │  List<List<Double>> vectors
                       ▼
┌─────────────────────────────────────────────────────┐
│  ChromaDbService  (REST client)                     │
│    → create / reset collection (cosine similarity)  │
│    → bulk insert chunks + embeddings                │
└─────────────────────────────────────────────────────┘

    POST /api/chat  {"question": "..."}
    │
    ▼
┌─────────────────────────────────────────────────────┐
│  ChatService                                        │
│    → embed question (Voyage-3)                      │
│    → query ChromaDB → top-6 chunks                  │
│    → inject chunks as context into Claude prompt    │
│    → Claude claude-sonnet-4-6 → answer + sources        │
└─────────────────────────────────────────────────────┘
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ |
| Maven | 3.9+ |
| Docker | 24+ (for ChromaDB) |
| Anthropic API Key | with Voyage access |

---

## Quick Start

### 1. Start ChromaDB

```bash
docker run -d -p 8000:8000 --name chroma chromadb/chroma
```

### 2. Configure & Run the App

```bash
export ANTHROPIC_API_KEY="sk-ant-..."

# Build
mvn clean package -DskipTests

# Run
java -jar target/kalsec-rag-1.0.0.jar
```

The API is now live at `http://localhost:8080`.

### 3. Ingest the Excel File

**Option A — upload via HTTP:**
```bash
curl -F "file=@Kalsec_Contract_Summary_Report_01-26-2025_01-24-2026_02-02-2026.xlsx" \
     http://localhost:8080/api/ingest/upload
```

**Option B — path on server filesystem:**
```bash
curl -X POST http://localhost:8080/api/ingest/path \
     -H "Content-Type: application/json" \
     -d '{"filePath":"/data/Kalsec_Contract_Summary_Report_....xlsx"}'
```

Sample response:
```json
{
  "totalChunks": 342,
  "sheetsProcessed": 28,
  "processedSheets": ["Accessorial", "Baserate", "Billing Details", "..."],
  "message": "Ingestion successful."
}
```

### 4. Ask Questions

```bash
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{"question":"What is the total net spend for Kalsec?"}'
```

Sample response:
```json
{
  "answer": "The total net spend for Kalsec is $64,573.10, with a gross spend of $168,551.55 and an incentive amount of $103,978.45 (from the Executive Summary sheet).",
  "sources": [
    {
      "sheet": "Executive Summary",
      "chunkIndex": 0,
      "relevance": 0.912,
      "text": "[Sheet: Executive Summary]\nMetric: Net Spend\n..."
    }
  ]
}
```

### Multi-turn Conversation

Pass the `history` array to maintain context across turns:

```bash
curl -X POST http://localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{
       "question": "What about the proposed discount for Ground?",
       "history": [
         {"role":"user",      "content":"What is the total net spend?"},
         {"role":"assistant", "content":"The total net spend is $64,573.10."}
       ]
     }'
```

---

## API Reference

### `POST /api/ingest/upload`
Upload and ingest an Excel file.
- Form field: `file` (multipart, `.xlsx`, max 100 MB)
- Returns: `IngestResponse`

### `POST /api/ingest/path`
Ingest a file already on the server filesystem.
- Body: `{"filePath": "/absolute/path/to/file.xlsx"}`
- Returns: `IngestResponse`

### `GET /api/ingest/status`
Check how many chunks are stored.
- Returns: `{"chunksStored": 342, "ready": true}`

### `POST /api/chat`
Ask a question.
- Body: `ChatRequest` (see below)
- Returns: `ChatResponse`

#### ChatRequest
```json
{
  "question": "string (required)",
  "history":  [{"role":"user|assistant", "content":"string"}]  // optional
}
```

#### ChatResponse
```json
{
  "answer":  "string",
  "sources": [{"sheet":"string", "chunkIndex":0, "relevance":0.9, "text":"string"}]
}
```

---

## Docker Compose (Full Stack)

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
docker-compose up --build
```

This starts both ChromaDB and the Spring Boot app.

---

## Project Structure

```
kalsec-rag/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── README.md
└── src/
    └── main/
        ├── java/com/kalsec/rag/
        │   ├── KalsecRagApplication.java
        │   ├── config/
        │   │   ├── AppConfig.java              # OkHttpClient, ObjectMapper beans
        │   │   └── AnthropicProperties.java    # Typed config binding
        │   ├── controller/
        │   │   ├── ChatController.java         # POST /api/chat
        │   │   ├── IngestController.java       # POST /api/ingest/*
        │   │   └── GlobalExceptionHandler.java
        │   ├── dto/
        │   │   ├── ChatRequest.java
        │   │   ├── ChatResponse.java
        │   │   └── IngestResponse.java
        │   ├── model/
        │   │   └── ExcelChunk.java
        │   └── service/
        │       ├── ExcelReaderService.java     # Apache POI + chunking
        │       ├── AnthropicService.java       # Embeddings + Claude chat
        │       ├── ChromaDbService.java        # ChromaDB REST client
        │       ├── IngestService.java          # Orchestration pipeline
        │       └── ChatService.java            # RAG query pipeline
        └── resources/
            ├── application.properties
            └── application-docker.properties
```

---

## Example Questions

```
What is the total net spend for Kalsec?
What is the proposed discount for Ground service?
What are the top accessorial charges?
What is the current vs proposed minimum for Next Day Air?
What are the revenue tier bands?
Which zones have the most shipments?
What savings are projected from DIM impact changes?
What is the 52-week rolling average for Kalsec?
```

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `anthropic.api.key` | (env) | Your Anthropic API key |
| `anthropic.model.claude` | `claude-sonnet-4-6` | Claude model for answers |
| `anthropic.model.embedding` | `voyage-3` | Voyage embedding model |
| `chroma.base-url` | `http://localhost:8000` | ChromaDB server URL |
| `chroma.collection.name` | `kalsec_contract` | Collection name |
| `ingest.chunk-size` | `50` | Rows per chunk (tabular sheets) |
| `ingest.embed-batch-size` | `96` | Texts per embedding API call |
| `retrieval.top-k` | `6` | Chunks retrieved per query |
