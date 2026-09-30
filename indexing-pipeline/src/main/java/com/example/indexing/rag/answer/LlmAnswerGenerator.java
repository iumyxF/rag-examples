package com.example.indexing.rag.answer;

import com.example.indexing.common.PipelineException;
import com.example.indexing.rag.context.BuiltContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class LlmAnswerGenerator implements AnswerGenerator {
    private final ChatModel model;
    private final ObjectMapper mapper;

    public LlmAnswerGenerator(ChatModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public AnswerDraft generate(String question, BuiltContext context, String correction) {
        String prompt =
                """
                        只能依据给定证据回答。如果证据不足请明确说明。事实陈述后使用 [1] 形式引用，编号只能来自证据。
                        仅返回 JSON：{"answer":"带行内引用的回答","usedCitationIds":[1]}
                        问题：%s
                        %s
                        证据：
                        %s
                        """
                        .formatted(question, correction == null ? "" : "纠正要求：" + correction, context.text());
        try {
            String json =
                    model
                            .chat(prompt)
                            .replaceFirst("(?s)^\\s*```(?:json)?\\s*", "")
                            .replaceFirst("(?s)\\s*```\\s*$", "")
                            .trim();
            RawAnswer raw = mapper.readValue(json, RawAnswer.class);
            return new AnswerDraft(
                    raw.answer == null ? "" : raw.answer,
                    raw.usedCitationIds == null ? List.of() : raw.usedCitationIds);
        } catch (Exception e) {
            throw new PipelineException("answer-generation", "回答模型返回无效 JSON", e);
        }
    }

    public static class RawAnswer {
        public String answer;
        public List<Integer> usedCitationIds;
    }
}
