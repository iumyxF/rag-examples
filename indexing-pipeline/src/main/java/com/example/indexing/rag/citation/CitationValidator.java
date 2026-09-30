package com.example.indexing.rag.citation;

import com.example.indexing.rag.answer.AnswerDraft;
import com.example.indexing.rag.context.BuiltContext;

public interface CitationValidator {
    ValidationResult validate(AnswerDraft draft, BuiltContext context);
}
