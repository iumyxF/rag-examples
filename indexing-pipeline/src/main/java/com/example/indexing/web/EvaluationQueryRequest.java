package com.example.indexing.web;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record EvaluationQueryRequest(
        @NotBlank String question, List<String> documentIds, boolean generateAnswer) {
}
