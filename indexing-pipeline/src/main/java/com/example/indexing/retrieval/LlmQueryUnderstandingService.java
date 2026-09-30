package com.example.indexing.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class LlmQueryUnderstandingService implements QueryUnderstandingService {
    private final ChatModel model;
    private final ObjectMapper mapper;

    public LlmQueryUnderstandingService(ChatModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public QueryPlan understand(String question, List<String> documentIds) {
        String prompt =
                """
                        分析用户的 RAG 查询，仅返回 JSON：
                        {"rewrittenQuery":"","keywords":[""],"entities":[""],"intent":"","graphDepth":1}
                        graphDepth 只能是 1 或 2，只有需要关系链推理时才使用 2。用户问题：
                        """
                        + question;
        for (int i = 0; i < 2; i++) {
            try {
                String value = strip(model.chat(prompt + (i == 0 ? "" : "\n严格返回合法 JSON。")));
                RawPlan p = mapper.readValue(value, RawPlan.class);
                return new QueryPlan(
                        question,
                        blank(p.rewrittenQuery) ? question : p.rewrittenQuery,
                        p.keywords == null ? List.of() : p.keywords,
                        p.entities == null ? List.of() : p.entities,
                        blank(p.intent) ? "question_answering" : p.intent,
                        p.graphDepth == 2 ? 2 : 1,
                        documentIds == null ? List.of() : documentIds);
            } catch (Exception ignored) {
                // Retry once because model output can be transiently malformed; fall back below afterward.
            }
        }
        return new QueryPlan(
                question,
                question,
                List.of(question),
                List.of(),
                "question_answering",
                1,
                documentIds == null ? List.of() : documentIds);
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private String strip(String s) {
        return s.replaceFirst("(?s)^\\s*```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```\\s*$", "")
                .trim();
    }

    public static class RawPlan {
        public String rewrittenQuery;
        public List<String> keywords;
        public List<String> entities;
        public String intent;
        public int graphDepth;
    }
}
