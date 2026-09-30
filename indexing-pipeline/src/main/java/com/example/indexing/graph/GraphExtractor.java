package com.example.indexing.graph;

import com.example.indexing.chunking.DocumentChunk;

public interface GraphExtractor {
    ExtractedGraph extract(DocumentChunk chunk);
}
