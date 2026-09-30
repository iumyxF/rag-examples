package com.example.indexing.graph.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.config.RagProperties;
import com.example.indexing.graph.ExtractedGraph;
import com.example.indexing.graph.GraphRepository;
import com.example.indexing.graph.GraphSnapshot;
import com.example.indexing.index.ChunkIndexRepository;
import com.example.indexing.retrieval.QueryPlan;
import com.example.indexing.retrieval.RetrievalCandidate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisGraphRepository implements GraphRepository {
    private final EntityMapper entities;
    private final MentionMapper mentions;
    private final RelationMapper relations;
    private final JdbcTemplate jdbc;
    private final ChunkIndexRepository chunks;
    private final double minimumConfidence;

    public MybatisGraphRepository(
            EntityMapper entities,
            MentionMapper mentions,
            RelationMapper relations,
            JdbcTemplate jdbc,
            ChunkIndexRepository chunks,
            RagProperties properties) {
        this.entities = entities;
        this.mentions = mentions;
        this.relations = relations;
        this.jdbc = jdbc;
        this.chunks = chunks;
        this.minimumConfidence = properties.graph().minimumConfidence();
    }

    @Override
    public GraphWriteStats save(
            String documentId, String versionId, DocumentChunk chunk, ExtractedGraph graph) {
        Map<String, String> ids = new HashMap<>();
        int savedEntities = 0;
        for (ExtractedGraph.Node node : graph.nodes()) {
            String key = normalize(node.canonicalName());
            EntityRecord entity =
                    entities.selectOne(new QueryWrapper<EntityRecord>().eq("normalized_key", key));
            if (entity == null) {
                entity = new EntityRecord();
                entity.id = UUID.randomUUID().toString();
                entity.canonicalName = node.canonicalName();
                entity.normalizedKey = key;
                entity.entityType = node.type();
                entity.description = node.description();
                entities.insert(entity);
                savedEntities++;
            }
            ids.put(node.canonicalName(), entity.id);
            MentionRecord mention = new MentionRecord();
            mention.id = UUID.randomUUID().toString();
            mention.entityId = entity.id;
            mention.documentId = documentId;
            mention.versionId = versionId;
            mention.chunkId = chunk.id();
            mention.displayName = node.displayName();
            mention.pageNumber = chunk.pageNumber();
            mention.sheetName = chunk.sheetName();
            mention.sectionPath = chunk.sectionPath();
            mention.evidenceText = chunk.content();
            mentions.insert(mention);
        }
        int relationCount = 0;
        for (ExtractedGraph.Edge edge : graph.edges()) {
            if (edge.confidence() < minimumConfidence
                    || !ids.containsKey(edge.source())
                    || !ids.containsKey(edge.target())) {
                continue;
            }
            RelationRecord r = new RelationRecord();
            r.id = UUID.randomUUID().toString();
            r.sourceEntityId = ids.get(edge.source());
            r.targetEntityId = ids.get(edge.target());
            r.relationType = edge.relation();
            r.rawRelation = edge.rawRelation();
            r.confidence = edge.confidence();
            r.documentId = documentId;
            r.versionId = versionId;
            r.chunkId = chunk.id();
            r.evidenceText = edge.evidence();
            relations.insert(r);
            relationCount++;
        }
        return new GraphWriteStats(savedEntities, relationCount);
    }

    @Override
    public List<RetrievalCandidate> search(
            QueryPlan plan, Map<String, String> activeVersions, int topK, int maxNodes, int maxEdges) {
        if (activeVersions.isEmpty()) {
            return List.of();
        }
        List<String> terms = plan.entities().isEmpty() ? plan.keywords() : plan.entities();
        if (terms.isEmpty()) {
            return List.of();
        }
        List<String> versions = new ArrayList<>(activeVersions.values());
        String in = placeholders(versions.size());
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>(versions);
        for (String term : terms) {
            if (!where.isEmpty()) {
                where.append(" OR ");
            }
            where.append("LOWER(e.canonical_name) LIKE ?");
            args.add("%" + term.toLowerCase(Locale.ROOT) + "%");
        }
        String sql =
                "SELECT DISTINCT e.id,e.canonical_name,m.chunk_id FROM kg_entity e "
                        + "JOIN kg_entity_mention m ON m.entity_id=e.id "
                        + "WHERE m.version_id IN ("
                        + in
                        + ") AND ("
                        + where
                        + ") LIMIT "
                        + maxNodes;
        List<Map<String, Object>> roots = jdbc.queryForList(sql, args.toArray());
        Set<String> entityIds = new LinkedHashSet<>();
        Map<String, Set<String>> chunkMatches = new LinkedHashMap<>();
        roots.forEach(
                r -> {
                    String eid = (String) r.get("id");
                    entityIds.add(eid);
                    chunkMatches
                            .computeIfAbsent((String) r.get("chunk_id"), k -> new LinkedHashSet<>())
                            .add((String) r.get("canonical_name"));
                });
        Set<String> frontier = new LinkedHashSet<>(entityIds);
        List<String> paths = new ArrayList<>();
        // Bound traversal by both configured depth and node count to keep graph expansion predictable.
        for (int depth = 0;
             depth < Math.min(plan.graphDepth(), 2)
                     && !frontier.isEmpty()
                     && entityIds.size() < maxNodes;
             depth++) {
            List<String> f = new ArrayList<>(frontier);
            List<Object> relArgs = new ArrayList<>(versions);
            relArgs.addAll(f);
            relArgs.addAll(f);
            String relSql =
                    "SELECT r.source_entity_id,r.target_entity_id,r.relation_type,r.chunk_id,"
                            + "s.canonical_name source_name,t.canonical_name target_name "
                            + "FROM kg_relation r JOIN kg_entity s ON s.id=r.source_entity_id "
                            + "JOIN kg_entity t ON t.id=r.target_entity_id "
                            + "WHERE r.version_id IN ("
                            + in
                            + ") AND (r.source_entity_id IN ("
                            + placeholders(f.size())
                            + ") OR r.target_entity_id IN ("
                            + placeholders(f.size())
                            + ")) LIMIT "
                            + maxEdges;
            Set<String> next = new LinkedHashSet<>();
            for (Map<String, Object> r : jdbc.queryForList(relSql, relArgs.toArray())) {
                String s = (String) r.get("source_entity_id"), t = (String) r.get("target_entity_id");
                next.add(s);
                next.add(t);
                String path =
                        r.get("source_name") + " -[" + r.get("relation_type") + "]-> " + r.get("target_name");
                paths.add(path);
                chunkMatches
                        .computeIfAbsent((String) r.get("chunk_id"), k -> new LinkedHashSet<>())
                        .add(path);
            }
            next.removeAll(entityIds);
            entityIds.addAll(next);
            frontier = next;
        }
        List<DocumentChunk> found = chunks.findByIds(new ArrayList<>(chunkMatches.keySet()));
        List<RetrievalCandidate> result = new ArrayList<>();
        int rank = 1;
        for (DocumentChunk c :
                found.stream()
                        .sorted(
                                Comparator.comparingInt(x -> -chunkMatches.getOrDefault(x.id(), Set.of()).size()))
                        .limit(topK)
                        .toList()) {
            List<String> match = new ArrayList<>(chunkMatches.getOrDefault(c.id(), Set.of()));
            result.add(
                    new RetrievalCandidate(
                            c,
                            "graph",
                            rank,
                            1.0 / rank,
                            0,
                            null,
                            match,
                            paths,
                            Map.of("depth", plan.graphDepth())));
            rank++;
        }
        return result;
    }

    @Override
    public GraphSnapshot documentGraph(
            String documentId, String versionId, int maxNodes, int maxEdges) {
        return snapshot(
                "m.document_id=? AND m.version_id=?",
                List.of(documentId, versionId),
                "r.document_id=? AND r.version_id=?",
                List.of(documentId, versionId),
                maxNodes,
                maxEdges);
    }

    @Override
    public GraphSnapshot evidenceGraph(List<String> chunkIds, int maxNodes, int maxEdges) {
        if (chunkIds.isEmpty()) {
            return new GraphSnapshot(List.of(), List.of(), false);
        }
        String condition = "chunk_id IN (" + placeholders(chunkIds.size()) + ")";
        return snapshot(
                "m." + condition,
                new ArrayList<>(chunkIds),
                "r." + condition,
                new ArrayList<>(chunkIds),
                maxNodes,
                maxEdges);
    }

    private GraphSnapshot snapshot(
            String mentionPredicate,
            List<?> mentionArgs,
            String relationPredicate,
            List<?> relationArgs,
            int maxNodes,
            int maxEdges) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT DISTINCT e.id,e.canonical_name,e.entity_type FROM kg_entity e "
                                + "JOIN kg_entity_mention m ON m.entity_id=e.id WHERE "
                                + mentionPredicate
                                + " LIMIT "
                                + (maxNodes + 1),
                        mentionArgs.toArray());
        boolean truncated = rows.size() > maxNodes;
        if (truncated) {
            rows = rows.subList(0, maxNodes);
        }
        List<GraphSnapshot.Node> nodes =
                rows.stream()
                        .map(
                                r ->
                                        new GraphSnapshot.Node(
                                                (String) r.get("id"),
                                                (String) r.get("canonical_name"),
                                                (String) r.get("entity_type")))
                        .toList();
        if (nodes.isEmpty()) {
            return new GraphSnapshot(nodes, List.of(), truncated);
        }
        List<String> ids = nodes.stream().map(GraphSnapshot.Node::id).toList();
        List<Object> edgeArgs = new ArrayList<>(relationArgs);
        edgeArgs.addAll(ids);
        edgeArgs.addAll(ids);
        List<Map<String, Object>> edgeRows =
                jdbc.queryForList(
                        "SELECT source_entity_id,target_entity_id,relation_type,evidence_text FROM kg_relation r WHERE "
                                + relationPredicate
                                + " AND source_entity_id IN ("
                                + placeholders(ids.size())
                                + ") AND target_entity_id IN ("
                                + placeholders(ids.size())
                                + ") LIMIT "
                                + (maxEdges + 1),
                        edgeArgs.toArray());
        if (edgeRows.size() > maxEdges) {
            edgeRows = edgeRows.subList(0, maxEdges);
            truncated = true;
        }
        List<GraphSnapshot.Edge> edges =
                edgeRows.stream()
                        .map(
                                r ->
                                        new GraphSnapshot.Edge(
                                                (String) r.get("source_entity_id"),
                                                (String) r.get("target_entity_id"),
                                                (String) r.get("relation_type"),
                                                (String) r.get("evidence_text")))
                        .toList();
        return new GraphSnapshot(nodes, edges, truncated);
    }

    @Override
    public void deleteVersion(String versionId) {
        relations.delete(new QueryWrapper<RelationRecord>().eq("version_id", versionId));
        mentions.delete(new QueryWrapper<MentionRecord>().eq("version_id", versionId));
    }

    @Override
    public void deleteOrphanEntities() {
        jdbc.update(
                "DELETE e FROM kg_entity e LEFT JOIN kg_entity_mention m ON m.entity_id=e.id WHERE m.id IS NULL");
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-]+", "")
                .trim();
    }
}
