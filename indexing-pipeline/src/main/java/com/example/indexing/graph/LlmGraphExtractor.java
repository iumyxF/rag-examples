package com.example.indexing.graph;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.common.PipelineException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class LlmGraphExtractor implements GraphExtractor {
    private static final Set<String> BASE =
            Set.of(
                    "contains",
                    "is_a",
                    "part_of",
                    "depends_on",
                    "causes",
                    "precedes",
                    "implements",
                    "related_to");
    private final ChatModel model;
    private final ObjectMapper mapper;

    public LlmGraphExtractor(ChatModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public ExtractedGraph extract(DocumentChunk chunk) {
        String prompt =
                """
                        你是知识图谱抽取器。仅返回合法 JSON，不要 Markdown。节点名称必须有原文 displayName 和归一化 canonicalName。
                        关系优先使用 contains,is_a,part_of,depends_on,causes,precedes,implements,related_to；无法表达时使用简短 snake_case。
                        每个文本块最多返回 15 个最重要节点和 20 条最重要关系，避免重复、泛化或低价值节点。
                        description 不超过 50 个字符；evidence 必须直接摘自原文且不超过 100 个字符。
                        输出紧凑的单行 JSON，不要缩进。每条关系必须给出原文 evidence 和 0~1 confidence。JSON 格式：
                        {"nodes":[{"displayName":"","canonicalName":"","type":"","description":""}],
                         "edges":[{"source":"canonicalName","target":"canonicalName","relation":"",
                         "rawRelation":"","evidence":"","confidence":0.0}]}
                        文本：
                        """
                        + chunk.content();
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String json;
            try {
                json =
                        stripFence(
                                model.chat(
                                        prompt
                                                + (attempt == 0
                                                        ? ""
                                                        : "\n上次格式错误，请严格只返回 JSON。")));
            } catch (RuntimeException e) {
                throw modelRequestFailure(e);
            }
            try {
                RawGraph raw = mapper.readValue(json, RawGraph.class);
                List<ExtractedGraph.Node> nodes =
                        raw.nodes == null
                                ? List.of()
                                : raw.nodes.stream()
                                .filter(n -> present(n.displayName) && present(n.canonicalName))
                                .map(
                                        n ->
                                                new ExtractedGraph.Node(
                                                        n.displayName.trim(),
                                                        n.canonicalName.trim(),
                                                        n.type,
                                                        n.description))
                                .distinct()
                                .toList();
                Set<String> names =
                        nodes.stream()
                                .map(ExtractedGraph.Node::canonicalName)
                                .collect(java.util.stream.Collectors.toSet());
                List<ExtractedGraph.Edge> edges =
                        raw.edges == null
                                ? List.of()
                                : raw.edges.stream()
                                .filter(
                                        e ->
                                                names.contains(e.source)
                                                        && names.contains(e.target)
                                                        && !e.source.equals(e.target)
                                                        && present(e.evidence))
                                .map(
                                        e ->
                                                new ExtractedGraph.Edge(
                                                        e.source,
                                                        e.target,
                                                        normalizeRelation(e.relation),
                                                        e.rawRelation,
                                                        e.evidence,
                                                        clamp(e.confidence)))
                                .toList();
                return new ExtractedGraph(nodes, edges);
            } catch (Exception e) {
                last = e;
            }
        }
        String detail = last.getMessage();
        String hint = isUnexpectedEnd(last)
                ? "，响应可能被截断，请适当增大 OPENAI_MAX_COMPLETION_TOKENS"
                : "";
        throw new PipelineException(
                "graph-extraction", "图谱抽取模型返回了无效 JSON" + hint + ": " + detail, last);
    }

    private PipelineException modelRequestFailure(RuntimeException error) {
        if (isTimeout(error)) {
            return new PipelineException(
                    "graph-extraction",
                    "图谱抽取模型请求超时。请检查 OPENAI_BASE_URL 与模型服务是否可访问，"
                            + "或通过 OPENAI_TIMEOUT（例如 300s）增大等待时间。",
                    error);
        }
        return new PipelineException(
                "graph-extraction", "图谱抽取模型请求失败: " + error.getMessage(), error);
    }

    private boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current.getClass().getSimpleName().equals("TimeoutException")
                    || (current.getMessage() != null
                            && current.getMessage().toLowerCase(Locale.ROOT).contains("timed out"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnexpectedEnd(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("unexpected end-of-input")) {
                return true;
            }
        }
        return false;
    }

    private String normalizeRelation(String value) {
        String r =
                present(value)
                        ? value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
                        : "related_to";
        return BASE.contains(r) ? r : "custom:" + r;
    }

    private double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private String stripFence(String s) {
        return s.replaceFirst("(?s)^\\s*```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```\\s*$", "")
                .trim();
    }

    public static class RawGraph {
        public List<RawNode> nodes;
        public List<RawEdge> edges;
    }

    public static class RawNode {
        public String displayName;
        public String canonicalName;
        public String type;
        public String description;
    }

    public static class RawEdge {
        public String source;
        public String target;
        public String relation;
        public String rawRelation;
        public String evidence;
        public double confidence;
    }
}
