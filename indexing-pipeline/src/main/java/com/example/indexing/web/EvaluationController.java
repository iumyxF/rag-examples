package com.example.indexing.web;

import com.example.indexing.config.RagProperties;
import com.example.indexing.graph.GraphSnapshot;
import com.example.indexing.rag.pipeline.EvaluationExecution;
import com.example.indexing.rag.pipeline.RagPipelineService;
import com.example.indexing.retrieval.RetrievalCandidate;
import jakarta.validation.Valid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/evaluation/v1")
public class EvaluationController {
    private final RagPipelineService pipeline;
    private final RagProperties properties;
    private final String codeRevision;

    public EvaluationController(
            RagPipelineService pipeline,
            RagProperties properties,
            @Value("${app.code-revision:unknown}") String codeRevision) {
        this.pipeline = pipeline;
        this.properties = properties;
        this.codeRevision = codeRevision;
    }

    @PostMapping("/query")
    public EvaluationResponse query(@Valid @RequestBody EvaluationQueryRequest request) {
        EvaluationExecution result =
                pipeline.evaluate(
                        request.question(),
                        request.documentIds() == null ? List.of() : request.documentIds(),
                        request.generateAnswer());
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("codeRevision", codeRevision);
        runtime.put("activeDocumentVersions", result.activeVersions());
        runtime.put("sourceContentHashes", result.sourceContentHashes());
        runtime.put("indexRevision", hash(new java.util.TreeMap<>(result.activeVersions()).toString()));
        runtime.put("chunking", properties.chunking());
        runtime.put("embeddingModel", properties.embedding().modelName());
        runtime.put("rerankerModel", properties.jina().modelName());
        runtime.put("answerModel", properties.chat().modelName());
        runtime.put("retrieval", properties.retrieval());
        return new EvaluationResponse(
                result.answer(),
                runtime,
                result.retrieval().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> java.util.stream.IntStream.range(0, entry.getValue().size())
                                        .mapToObj(index -> Candidate.from(entry.getValue().get(index), index + 1))
                                        .toList(),
                                (left, right) -> left,
                                LinkedHashMap::new)),
                GraphTrace.from(result.graphTrace()),
                result.contextCitations(),
                result.usedCitationIds(),
                result.timings(),
                result.citationValidation());
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record EvaluationResponse(
            String answer,
            Map<String, Object> runtimeConfiguration,
            Map<String, List<Candidate>> retrieval,
            GraphTrace graphTrace,
            List<?> contextCitations,
            List<Integer> usedCitationIds,
            Map<String, Long> timings,
            String citationValidation) {
    }

    public record Candidate(
            String chunkId,
            String documentId,
            String documentVersionId,
            int rank,
            double score,
            Integer pageStart,
            Integer pageEnd,
            String sectionPath,
            List<String> sourceBlockIds,
            String excerpt,
            List<String> matchedEntities,
            List<String> graphPath,
            Map<String, Object> metadata) {
        static Candidate from(RetrievalCandidate value, int rank) {
            var chunk = value.chunk();
            double score =
                    value.rerankScore() != null
                            ? value.rerankScore()
                            : value.rrfScore() != 0 ? value.rrfScore() : value.originalScore();
            return new Candidate(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.versionId(),
                    rank,
                    score,
                    chunk.pageStart(),
                    chunk.pageEnd(),
                    chunk.sectionPath(),
                    chunk.sourceBlockIds(),
                    chunk.content().length() <= 500
                            ? chunk.content()
                            : chunk.content().substring(0, 500) + "…",
                    value.matchedEntities(),
                    value.graphPath(),
                    value.metadata());
        }
    }

    public record GraphTrace(List<Entity> entities, List<Relation> relations, boolean truncated) {
        static GraphTrace from(GraphSnapshot value) {
            Map<String, String> names = value.nodes().stream()
                    .collect(java.util.stream.Collectors.toMap(GraphSnapshot.Node::id, GraphSnapshot.Node::name));
            return new GraphTrace(
                    value.nodes().stream()
                            .map(node -> new Entity(node.name(), node.type()))
                            .toList(),
                    value.edges().stream()
                            .map(edge -> new Relation(
                                    names.getOrDefault(edge.source(), edge.source()),
                                    edge.relation(),
                                    names.getOrDefault(edge.target(), edge.target()),
                                    true,
                                    edge.evidence()))
                            .toList(),
                    value.truncated());
        }
    }

    public record Entity(String canonicalName, String entityType) {
    }

    public record Relation(
            String sourceEntity,
            String relationType,
            String targetEntity,
            boolean directional,
            String evidence) {
    }
}
