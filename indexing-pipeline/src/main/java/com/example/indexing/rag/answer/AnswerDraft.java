package com.example.indexing.rag.answer;

import java.util.List;

public record AnswerDraft(String answer, List<Integer> usedCitationIds) {
}
