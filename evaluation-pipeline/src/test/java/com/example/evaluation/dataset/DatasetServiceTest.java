package com.example.evaluation.dataset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

class DatasetServiceTest {
    private final DatasetService service = new DatasetService(
            mock(JdbcTemplate.class), mock(TransactionTemplate.class), new ObjectMapper());

    @Test
    void rejectsAlternativeWithoutStableLocator() {
        String json = """
                {"id":"case-1","question":"问题","documentId":"doc",\
                "sourceContentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",\
                "evidenceGroups":[{"alternatives":[{"quoteText":"原文"}]}]}
                """;
        var file = new MockMultipartFile("file", "cases.jsonl", "application/x-ndjson", json.getBytes());

        assertThatThrownBy(() -> service.importJsonl("dataset", null, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alternative");
    }

    @Test
    void rejectsNonSha256SourceHash() {
        String json = """
                {"id":"case-1","question":"问题","documentId":"doc","sourceContentHash":"bad",\
                "evidenceGroups":[{"alternatives":[{"sourceBlockIds":["block"]}]}]}
                """;
        var file = new MockMultipartFile("file", "cases.jsonl", "application/x-ndjson", json.getBytes());

        assertThatThrownBy(() -> service.importJsonl("dataset", null, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }
}
