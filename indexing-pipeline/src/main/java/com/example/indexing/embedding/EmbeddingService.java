package com.example.indexing.embedding;

import java.util.List;

public interface EmbeddingService {
    List<float[]> embedAll(List<String> texts);

    float[] embed(String text);
}
