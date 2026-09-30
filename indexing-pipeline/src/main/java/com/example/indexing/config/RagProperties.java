package com.example.indexing.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Storage storage,
        Docling docling,
        Model chat,
        Embedding embedding,
        Jina jina,
        Chunking chunking,
        Retrieval retrieval,
        Graph graph,
        Context context) {
    public record Storage(String uploadDirectory) {
    }

    public record Docling(
            String baseUrl,
            String authHeaderName,
            String authHeaderValue,
            Duration timeout,
            String tokenizer) {
    }

    public record Model(
            String baseUrl,
            String apiKey,
            String modelName,
            Duration timeout,
            int maxRetries,
            boolean enableThinking,
            int maxCompletionTokens) {
    }

    public record Embedding(String baseUrl, String apiKey, String modelName, int dimension) {
    }

    public record Jina(String apiKey, String modelName, Duration timeout) {
    }

    public record Chunking(int maxTokens, int overlapTokens) {
    }

    public record Retrieval(
            int bm25TopK, int vectorTopK, int graphTopK, int rrfK, int fusedTopK, int rerankTopK) {
    }

    public record Graph(int maxDepth, int maxNodes, int maxEdges, double minimumConfidence) {
    }

    public record Context(int maxTokens) {
    }
}
