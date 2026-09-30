package com.example.evaluation.comparison;

import com.example.evaluation.run.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class ComparisonService {
    private final RunService runs;
    private final ObjectMapper mapper;

    public ComparisonService(RunService runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    public Map<String, Object> compare(String baselineId, String candidateId) {
        Map<String, Object> baseline = runs.get(baselineId);
        Map<String, Object> candidate = runs.get(candidateId);
        if (!String.valueOf(baseline.get("revision_id"))
                .equals(String.valueOf(candidate.get("revision_id")))) {
            throw new IllegalArgumentException("只有相同 datasetRevision 的 Run 可以正式对比");
        }
        JsonNode baselineSummary = (JsonNode) baseline.get("summary_metrics_json");
        JsonNode candidateSummary = (JsonNode) candidate.get("summary_metrics_json");
        if (baselineSummary == null || candidateSummary == null) {
            throw new IllegalArgumentException("两个 Run 都必须已经完成并产生汇总指标");
        }
        Map<String, Map<String, Object>> baselineCases = index(runs.results(baselineId));
        Map<String, Map<String, Object>> candidateCases = index(runs.results(candidateId));
        List<Map<String, Object>> improved = new ArrayList<>();
        List<Map<String, Object>> regressed = new ArrayList<>();
        List<Map<String, Object>> unchanged = new ArrayList<>();
        for (String id : baselineCases.keySet()) {
            if (!candidateCases.containsKey(id)) {
                continue;
            }
            Map<String, Object> left = baselineCases.get(id);
            Map<String, Object> right = candidateCases.get(id);
            int comparison = quality(right).compareTo(quality(left));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("externalCaseId", id);
            row.put("question", left.get("question"));
            row.put("baseline", quality(left));
            row.put("candidate", quality(right));
            row.put("baselineFailures", left.get("failureCategories"));
            row.put("candidateFailures", right.get("failureCategories"));
            (comparison > 0 ? improved : comparison < 0 ? regressed : unchanged).add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baselineRun", baseline);
        result.put("candidateRun", candidate);
        result.put("metricDelta", delta(baselineSummary, candidateSummary));
        result.put("improved", improved);
        result.put("regressed", regressed);
        result.put("unchanged", unchanged);
        return result;
    }

    private Map<String, Map<String, Object>> index(List<Map<String, Object>> cases) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        cases.forEach(value -> result.put(String.valueOf(value.get("externalCaseId")), value));
        return result;
    }

    private Quality quality(Map<String, Object> value) {
        JsonNode retrieval = (JsonNode) value.get("retrievalMetrics");
        return new Quality(
                retrieval == null ? 0 : retrieval.path("reranked").path("recallAt10").asDouble(0),
                retrieval == null ? 0 : retrieval.path("reranked").path("mrr").asDouble(0));
    }

    private JsonNode delta(JsonNode baseline, JsonNode candidate) {
        if (!baseline.isObject() || !candidate.isObject()) {
            return mapper.nullNode();
        }
        ObjectNode result = mapper.createObjectNode();
        candidate.fields().forEachRemaining(entry -> {
            JsonNode before = baseline.path(entry.getKey());
            JsonNode after = entry.getValue();
            if (before.isNumber() && after.isNumber()) {
                result.put(entry.getKey(), after.asDouble() - before.asDouble());
            } else if (before.isObject() && after.isObject()) {
                result.set(entry.getKey(), delta(before, after));
            }
        });
        return result;
    }

    public record Quality(double recallAt10, double mrr) implements Comparable<Quality> {
        @Override
        public int compareTo(Quality other) {
            int recall = Double.compare(recallAt10, other.recallAt10);
            return recall != 0 ? recall : Double.compare(mrr, other.mrr);
        }
    }
}
