package com.example.indexing.chunking;

import com.example.indexing.parsing.NormalizedDocument;

import java.util.List;

public interface DocumentChunker {
    List<DocumentChunk> chunk(String documentId, String versionId, NormalizedDocument document);
}
