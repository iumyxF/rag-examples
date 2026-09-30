package com.example.indexing.embedding;

import com.example.indexing.common.PipelineException;
import com.example.indexing.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class LangChainEmbeddingService implements EmbeddingService {
    private static final int MAX_BATCH_SIZE = 10;
    private final EmbeddingModel model;
    private final String modelName;

    public LangChainEmbeddingService(EmbeddingModel model, RagProperties properties) {
        this.model = model;
        this.modelName = properties.embedding().modelName();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += MAX_BATCH_SIZE) {
            List<TextSegment> batch =
                    texts.subList(from, Math.min(texts.size(), from + MAX_BATCH_SIZE)).stream()
                            .map(TextSegment::from)
                            .toList();
            try {
                for (Embedding embedding : model.embedAll(batch).content()) {
                    vectors.add(embedding.vector());
                }
            } catch (RuntimeException e) {
                throw translateModelError(e);
            }
        }
        return vectors;
    }

    @Override
    public float[] embed(String text) {
        try {
            return model.embed(text).content().vector();
        } catch (RuntimeException e) {
            throw translateModelError(e);
        }
    }

    private RuntimeException translateModelError(RuntimeException error) {
        String message = error.getMessage();
        if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("url error")) {
            return new PipelineException(
                    "embedding",
                    "Embedding 模型 " + modelName
                            + " 拒绝了文本请求并将输入识别为 URL。纯文本索引请配置 EMBEDDING_MODEL=text-embedding-v4；"
                            + "qwen3-vl-embedding 必须改用阿里云多模态 Embedding 请求格式。",
                    error);
        }
        return error;
    }
}
