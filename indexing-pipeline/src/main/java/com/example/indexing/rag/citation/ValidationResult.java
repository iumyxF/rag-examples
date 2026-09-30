package com.example.indexing.rag.citation;

import com.example.indexing.rag.answer.AnswerDraft;

import java.util.List;

public record ValidationResult(
        boolean valid, AnswerDraft answer, List<Integer> invalidIds, String message) {
}
