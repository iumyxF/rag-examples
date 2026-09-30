package com.example.evaluation.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.evaluation.run.RunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparisonServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunService runs = mock(RunService.class);
    private final ComparisonService service = new ComparisonService(runs, mapper);

    @Test
    void rejectsDifferentDatasetRevisions() {
        when(runs.get("a")).thenReturn(Map.of("revision_id", "r1", "summary_metrics_json", mapper.createObjectNode()));
        when(runs.get("b")).thenReturn(Map.of("revision_id", "r2", "summary_metrics_json", mapper.createObjectNode()));

        assertThatThrownBy(() -> service.compare("a", "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datasetRevision");
    }

    @Test
    void classifiesHigherRecallAsImprovement() throws Exception {
        var summaryA = mapper.readTree("{\"retrieval\":{\"reranked\":{\"recallAt10\":0.0}}}");
        var summaryB = mapper.readTree("{\"retrieval\":{\"reranked\":{\"recallAt10\":1.0}}}");
        when(runs.get("a")).thenReturn(Map.of("revision_id", "r1", "summary_metrics_json", summaryA));
        when(runs.get("b")).thenReturn(Map.of("revision_id", "r1", "summary_metrics_json", summaryB));
        when(runs.results("a")).thenReturn(List.of(caseResult(0, 0)));
        when(runs.results("b")).thenReturn(List.of(caseResult(1, 1)));

        Map<String, Object> result = service.compare("a", "b");

        assertThat((List<?>) result.get("improved")).hasSize(1);
        assertThat((List<?>) result.get("regressed")).isEmpty();
    }

    private Map<String, Object> caseResult(double recall, double mrr) throws Exception {
        return Map.of(
                "externalCaseId", "case-1",
                "question", "问题",
                "retrievalMetrics", mapper.readTree(
                        "{\"reranked\":{\"recallAt10\":" + recall + ",\"mrr\":" + mrr + "}}"),
                "failureCategories", mapper.createArrayNode());
    }
}
