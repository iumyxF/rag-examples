package com.example.indexing.parsing;

import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.chunk.request.HybridChunkDocumentRequest;
import ai.docling.serve.api.chunk.request.options.HybridChunkerOptions;
import com.example.indexing.common.PipelineException;
import com.example.indexing.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.docling.DoclingDocumentParser;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class DoclingDocumentParserAdapter implements DocumentParserPort {
    private final DoclingDocumentParser parser;
    private final ObjectMapper mapper;

    public DoclingDocumentParserAdapter(RagProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        RagProperties.Docling p = properties.docling();
        var builder =
                DoclingServeApi.builder()
                        .baseUrl(p.baseUrl())
                        .readTimeout(p.timeout())
                        .asyncTimeout(p.timeout());
        if (p.authHeaderValue() != null && !p.authHeaderValue().isBlank()) {
            builder.apiKey(p.authHeaderValue().replaceFirst("(?i)^Bearer\\s+", ""));
        }
        parser =
                DoclingDocumentParser.builder()
                        .doclingClient(builder.build())
                        .documentRequest(
                                hybridChunkRequest(
                                        p.tokenizer(), properties.chunking().maxTokens()))
                        .chunkTextExtractor(
                                response -> {
                                    try {
                                        return mapper.writeValueAsString(response.getChunks());
                                    } catch (Exception e) {
                                        throw new IllegalStateException("无法序列化 Docling chunks", e);
                                    }
                                })
                        .build();
    }

    static HybridChunkDocumentRequest hybridChunkRequest(String tokenizer, int maxTokens) {
        var options = HybridChunkerOptions.builder().maxTokens(maxTokens);
        if (tokenizer != null && !tokenizer.isBlank()) {
            options.tokenizer(tokenizer);
        }
        return HybridChunkDocumentRequest.builder().chunkingOptions(options.build()).build();
    }

    @Override
    public NormalizedDocument parse(InputStream input, String fileName, String mediaType) {
        try {
            byte[] source = input.readAllBytes();
            String sourceContentHash = sha256(source);
            Document document = parser.parse(new ByteArrayInputStream(source));
            return new NormalizedDocument(
                    fileName,
                    mediaType,
                    parseChunkJson(document.text(), sourceContentHash),
                    Map.of(
                            "parser", "docling",
                            "operation", "hybrid_chunk",
                            "sourceContentHash", sourceContentHash));
        } catch (Exception e) {
            throw new PipelineException("docling", "Docling 解析失败: " + e.getMessage(), e);
        }
    }

    private List<NormalizedDocument.Block> parseChunkJson(String json, String sourceContentHash)
            throws Exception {
        JsonNode root = mapper.readTree(json);
        List<NormalizedDocument.Block> blocks = new ArrayList<>();
        if (!root.isArray()) {
            return parseMarkdown(json, sourceContentHash);
        }
        for (JsonNode chunk : root) {
            String text = chunk.path("text").asText(chunk.path("rawText").asText(""));
            if (text.isBlank()) {
                continue;
            }
            String section = "";
            JsonNode headings = chunk.path("headings");
            if (headings.isArray()) {
                List<String> values = new ArrayList<>();
                headings.forEach(h -> values.add(h.asText()));
                section = String.join(" > ", values);
            }
            Integer page = null;
            JsonNode pages = chunk.path("pageNumbers");
            if (pages.isArray() && !pages.isEmpty()) {
                page = pages.get(0).asInt();
            }
            NormalizedDocument.BlockType type = classify(text);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (chunk.path("metadata").isObject())
                metadata.putAll(mapper.convertValue(chunk.path("metadata"), Map.class));
            metadata.put("chunkIndex", chunk.path("chunkIndex").asInt(blocks.size()));
            metadata.put("numTokens", chunk.path("numTokens").asInt(0));
            String sheet =
                    chunk
                            .path("metadata")
                            .path("sheet_name")
                            .asText(chunk.path("metadata").path("sheetName").asText(null));
            blocks.add(
                    new NormalizedDocument.Block(
                            stableBlockId(sourceContentHash, blocks.size(), text),
                            type,
                            text,
                            page,
                            sheet,
                            section,
                            metadata));
        }
        return blocks;
    }

    private static NormalizedDocument.BlockType classify(String text) {
        if (text.matches("(?s)^#{1,6}\\s+.*"))
            return text.startsWith("# ")
                    ? NormalizedDocument.BlockType.TITLE
                    : NormalizedDocument.BlockType.HEADING;
        if (text.matches("(?s)^([-*+] |\\d+[.)] ).*")) {
            return NormalizedDocument.BlockType.LIST;
        }
        if (text.contains("|") && text.contains("\n")) {
            return NormalizedDocument.BlockType.TABLE;
        }
        if (text.startsWith("```")) {
            return NormalizedDocument.BlockType.CODE;
        }
        return NormalizedDocument.BlockType.PARAGRAPH;
    }

    static List<NormalizedDocument.Block> parseMarkdown(String markdown) {
        return parseMarkdown(markdown, sha256(markdown.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<NormalizedDocument.Block> parseMarkdown(
            String markdown, String sourceContentHash) {
        List<NormalizedDocument.Block> result = new ArrayList<>();
        String section = "";
        String[] paragraphs = markdown.replace("\r\n", "\n").split("\n\\s*\n");
        for (String raw : paragraphs) {
            String text = raw.trim();
            if (text.isEmpty()) {
                continue;
            }
            NormalizedDocument.BlockType type = classify(text);
            if (type == NormalizedDocument.BlockType.TITLE
                    || type == NormalizedDocument.BlockType.HEADING) {
                section = text.replaceFirst("^#{1,6}\\s+", "").trim();
            }
            result.add(
                    new NormalizedDocument.Block(
                            stableBlockId(sourceContentHash, result.size(), text),
                            type,
                            text,
                            null,
                            null,
                            section,
                            Map.of()));
        }
        return result;
    }

    static String stableBlockId(String sourceContentHash, int ordinal, String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").strip();
        return sha256(
                (sourceContentHash + ":" + ordinal + ":" + normalized)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 SHA-256", e);
        }
    }
}
