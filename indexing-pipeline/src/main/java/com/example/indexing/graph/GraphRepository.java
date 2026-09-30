package com.example.indexing.graph;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.retrieval.QueryPlan;
import com.example.indexing.retrieval.RetrievalCandidate;

import java.util.List;
import java.util.Map;

public interface GraphRepository {
    GraphWriteStats save(
            String documentId, String versionId, DocumentChunk chunk, ExtractedGraph graph);

    List<RetrievalCandidate> search(
            QueryPlan plan, Map<String, String> activeVersions, int topK, int maxNodes, int maxEdges);

    GraphSnapshot documentGraph(String documentId, String versionId, int maxNodes, int maxEdges);

    GraphSnapshot evidenceGraph(List<String> chunkIds, int maxNodes, int maxEdges);

    void deleteVersion(String versionId);

    void deleteOrphanEntities();

    record GraphWriteStats(int entities, int relations) {
    }
}
