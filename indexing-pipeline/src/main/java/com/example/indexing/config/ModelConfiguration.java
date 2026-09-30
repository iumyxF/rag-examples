package com.example.indexing.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.jina.JinaScoringModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfiguration {
    @Bean
    ChatModel chatModel(RagProperties p) {
        return OpenAiChatModel.builder()
                .baseUrl(p.chat().baseUrl())
                .apiKey(p.chat().apiKey())
                .modelName(p.chat().modelName())
                .temperature(0.0)
                .responseFormat("json_object")
                .customParameters(Map.of("enable_thinking", p.chat().enableThinking()))
                .maxCompletionTokens(p.chat().maxCompletionTokens())
                .timeout(p.chat().timeout())
                .maxRetries(p.chat().maxRetries())
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel(RagProperties p) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(p.embedding().baseUrl())
                .apiKey(p.embedding().apiKey())
                .modelName(p.embedding().modelName())
                .dimensions(p.embedding().dimension())
                .build();
    }

    @Bean
    ScoringModel scoringModel(RagProperties p) {
        return JinaScoringModel.builder()
                .apiKey(p.jina().apiKey())
                .modelName(p.jina().modelName())
                .timeout(p.jina().timeout())
                .maxRetries(0)
                .build();
    }
}
