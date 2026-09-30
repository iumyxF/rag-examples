package com.example.indexing.web;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record RagQueryRequest(@NotBlank String question, List<String> documentIds) {
}
