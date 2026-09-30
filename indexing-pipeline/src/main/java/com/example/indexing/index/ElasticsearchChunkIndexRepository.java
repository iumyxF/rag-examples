package com.example.indexing.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.common.PipelineException;
import com.example.indexing.config.RagProperties;
import com.example.indexing.retrieval.RetrievalCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

@Repository
public class ElasticsearchChunkIndexRepository implements ChunkIndexRepository {
    static final String INDEX = "rag_chunk_v1";
    private final ElasticsearchClient client;
    private final ObjectMapper mapper;
    private final int dimension;

    public ElasticsearchChunkIndexRepository(
            ElasticsearchClient client, ObjectMapper mapper, RagProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.dimension = properties.embedding().dimension();
    }

    @PostConstruct
    public void initialize() {
        ensureIndex();
    }

    @Override
    public void ensureIndex() {
        try {
            if (client.indices().exists(e -> e.index(INDEX)).value()) {
                return;
            }
            String mapping =
                    """
                            {"settings":{"number_of_shards":1,"number_of_replicas":0},"mappings":{"properties":{
                            "chunk_id":{"type":"keyword"},"document_id":{"type":"keyword"},"version_id":{"type":"keyword"},
                            "file_name":{"type":"keyword"},"title":{"type":"text","analyzer":"standard"},
                            "section_path":{"type":"text","analyzer":"standard"},
                            "content":{"type":"text","analyzer":"standard"},
                            "content_vector":{"type":"dense_vector","dims":%d,"index":true,"similarity":"cosine"},
                            "page_number":{"type":"integer"},"page_start":{"type":"integer"},"page_end":{"type":"integer"},
                            "source_block_ids":{"type":"keyword"},"sheet_name":{"type":"keyword"},"block_types":{"type":"keyword"},
                            "estimated_tokens":{"type":"integer"},"source_metadata":{"type":"flattened"}}}}
                            """
                            .formatted(dimension);
            client.indices().create(c -> c.index(INDEX).withJson(new StringReader(mapping)));
        } catch (Exception e) {
            throw new PipelineException(
                    "elasticsearch-init", "无法初始化 Elasticsearch 索引: " + e.getMessage(), e);
        }
    }

    @Override
    public void index(List<DocumentChunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("chunk 与 vector 数量不一致");
        }
        try {
            for (int from = 0; from < chunks.size(); from += 200) {
                BulkRequest.Builder bulk = new BulkRequest.Builder();
                for (int i = from; i < Math.min(chunks.size(), from + 200); i++) {
                    DocumentChunk c = chunks.get(i);
                    Map<String, Object> source = toSource(c, vectors.get(i));
                    bulk.operations(op -> op.index(idx -> idx.index(INDEX).id(c.id()).document(source)));
                }
                var response = client.bulk(bulk.build());
                if (response.errors()) {
                    throw new IOException("批量写入存在失败项: " + response.items());
                }
            }
        } catch (Exception e) {
            throw new PipelineException("elasticsearch-index", "写入 chunk 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void refresh() {
        try {
            client.indices().refresh(r -> r.index(INDEX));
        } catch (IOException e) {
            throw new PipelineException("elasticsearch-refresh", e.getMessage(), e);
        }
    }

    @Override
    public List<RetrievalCandidate> searchBm25(
            String query, Map<String, String> activeVersions, int topK) {
        if (activeVersions.isEmpty()) {
            return List.of();
        }
        ObjectNode root = mapper.createObjectNode().put("size", topK);
        ObjectNode bool = root.putObject("query").putObject("bool");
        bool.putArray("must")
                .addObject()
                .putObject("multi_match")
                .put("query", query)
                .putArray("fields")
                .add("content^3")
                .add("title^2")
                .add("section_path");
        addVersionFilter(bool.putArray("filter"), activeVersions);
        return search(root, "bm25");
    }

    @Override
    public List<RetrievalCandidate> searchVector(
            float[] vector, Map<String, String> activeVersions, int topK) {
        if (activeVersions.isEmpty()) {
            return List.of();
        }
        ObjectNode root = mapper.createObjectNode().put("size", topK);
        ObjectNode script = root.putObject("query").putObject("script_score");
        ObjectNode bool = script.putObject("query").putObject("bool");
        addVersionFilter(bool.putArray("filter"), activeVersions);
        ObjectNode scriptBody =
                script
                        .putObject("script")
                        .put("source", "cosineSimilarity(params.q, 'content_vector') + 1.0");
        ArrayNode array = scriptBody.putObject("params").putArray("q");
        for (float v : vector) {
            array.add(v);
        }
        return search(root, "vector");
    }

    private void addVersionFilter(ArrayNode filter, Map<String, String> versions) {
        ArrayNode values = filter.addObject().putObject("terms").putArray("version_id");
        versions.values().forEach(values::add);
    }

    @Override
    public List<DocumentChunk> findByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        ObjectNode root = mapper.createObjectNode().put("size", ids.size());
        ArrayNode values = root.putObject("query").putObject("terms").putArray("chunk_id");
        ids.forEach(values::add);
        return search(root, "lookup").stream().map(RetrievalCandidate::chunk).toList();
    }

    @Override
    public List<DocumentChunk> findByVersion(String versionId, int offset, int limit) {
        ObjectNode root = mapper.createObjectNode().put("from", offset).put("size", limit);
        root.putObject("query").putObject("term").put("version_id", versionId);
        root.putArray("sort").addObject().putObject("ordinal").put("order", "asc");
        return search(root, "preview").stream().map(RetrievalCandidate::chunk).toList();
    }

    @Override
    public long countVersion(String versionId) {
        try {
            return client.count(c -> c.index(INDEX)
                            .query(q -> q.term(t -> t.field("version_id").value(versionId))))
                    .count();
        } catch (Exception e) {
            throw new PipelineException("elasticsearch-count", "统计索引失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteVersion(String versionId) {
        if (versionId == null) {
            return;
        }
        try {
            client.deleteByQuery(
                    d ->
                            d.index(INDEX)
                                    .query(q -> q.term(t -> t.field("version_id").value(versionId)))
                                    .refresh(true));
        } catch (IOException e) {
            throw new PipelineException("elasticsearch-cleanup", e.getMessage(), e);
        }
    }

    private List<RetrievalCandidate> search(ObjectNode body, String source) {
        try {
            String json = mapper.writeValueAsString(body);
            SearchRequest request =
                    SearchRequest.of(s -> s.index(INDEX).withJson(new StringReader(json)));
            SearchResponse<Map> response = client.search(request, Map.class);
            List<RetrievalCandidate> result = new ArrayList<>();
            int rank = 1;
            for (Hit<Map> hit : response.hits().hits()) {
                DocumentChunk chunk = fromSource(hit.source());
                result.add(
                        new RetrievalCandidate(
                                chunk,
                                source,
                                rank++,
                                hit.score() == null ? 0 : hit.score(),
                                0,
                                null,
                                List.of(),
                                List.of(),
                                Map.of()));
            }
            return result;
        } catch (Exception e) {
            throw new PipelineException("elasticsearch-search", "检索失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toSource(DocumentChunk c, float[] vector) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunk_id", c.id());
        m.put("document_id", c.documentId());
        m.put("version_id", c.versionId());
        m.put("file_name", c.fileName());
        m.put("ordinal", c.ordinal());
        m.put("title", c.title());
        m.put("section_path", c.sectionPath());
        m.put("content", c.content());
        m.put("content_vector", vector);
        m.put("page_number", c.pageNumber());
        m.put("page_start", c.pageStart());
        m.put("page_end", c.pageEnd());
        m.put("source_block_ids", c.sourceBlockIds());
        m.put("sheet_name", c.sheetName());
        m.put("block_types", c.blockTypes());
        m.put("estimated_tokens", c.estimatedTokens());
        m.put("source_metadata", c.metadata());
        return m;
    }

    @SuppressWarnings("unchecked")
    static DocumentChunk fromSource(Map<String, Object> src) {
        Number ordinal = (Number) src.get("ordinal");
        Number tokens = (Number) src.get("estimated_tokens");
        Number page = (Number) src.get("page_number");
        Number pageStart = (Number) src.get("page_start");
        Number pageEnd = (Number) src.get("page_end");
        return new DocumentChunk(
                (String) src.get("chunk_id"),
                (String) src.get("document_id"),
                (String) src.get("version_id"),
                intValueOrDefault(ordinal, 0),
                (String) src.get("file_name"),
                (String) src.get("content"),
                (String) src.get("title"),
                (String) src.get("section_path"),
                nullableInt(page, null),
                nullableInt(pageStart, page),
                nullableInt(pageEnd, page),
                (List<String>) src.getOrDefault("source_block_ids", List.of()),
                (String) src.get("sheet_name"),
                (List<String>) src.getOrDefault("block_types", List.of()),
                intValueOrDefault(tokens, 0),
                (Map<String, Object>) src.getOrDefault("source_metadata", Map.of()));
    }

    private static Integer nullableInt(Number value, Number fallback) {
        if (value != null) {
            return value.intValue();
        }
        return fallback == null ? null : fallback.intValue();
    }

    private static int intValueOrDefault(Number value, int fallback) {
        return value == null ? fallback : value.intValue();
    }
}
