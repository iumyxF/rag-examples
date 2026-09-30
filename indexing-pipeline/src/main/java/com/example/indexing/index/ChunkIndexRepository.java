package com.example.indexing.index;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.retrieval.RetrievalCandidate;

import java.util.List;
import java.util.Map;

public interface ChunkIndexRepository {
    void ensureIndex();

    void index(List<DocumentChunk> chunks, List<float[]> vectors);

    void refresh();

    List<RetrievalCandidate> searchBm25(String query, Map<String, String> activeVersions, int topK);

    List<RetrievalCandidate> searchVector(
            float[] vector, Map<String, String> activeVersions, int topK);

    List<DocumentChunk> findByIds(List<String> chunkIds);

    List<DocumentChunk> findByVersion(String versionId, int offset, int limit);

    long countVersion(String versionId);

    void deleteVersion(String versionId);
}
