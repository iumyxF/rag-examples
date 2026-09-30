package com.example.indexing.rag.answer;

import com.example.indexing.rag.context.BuiltContext;

public interface AnswerGenerator {
    AnswerDraft generate(String question, BuiltContext context, String correction);
}
