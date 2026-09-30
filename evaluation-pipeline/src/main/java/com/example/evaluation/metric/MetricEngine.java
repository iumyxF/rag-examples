package com.example.evaluation.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class MetricEngine {
    private static final int[] K_VALUES = {1, 3, 5, 10};
    private static final List<String> STAGES = List.of("bm25", "vector", "graph", "fused", "reranked");
    private final ObjectMapper mapper;

    public MetricEngine(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CaseMetrics calculate(JsonNode evaluationCase, JsonNode response, boolean generateAnswer) {
        JsonNode groups = evaluationCase.path("evidenceGroups");
        ObjectNode retrieval = mapper.createObjectNode();
        for (String stage : STAGES) {
            JsonNode candidates = response.path("retrieval").path(stage);
            if (!candidates.isArray()) {
                continue;
            }
            retrieval.set(stage, retrievalMetrics(groups, candidates));
        }
        ObjectNode citation = generateAnswer
                ? citationMetrics(groups, response.path("contextCitations"), response.path("usedCitationIds"))
                : mapper.createObjectNode().put("applicable", false);
        ObjectNode graph = graphMetrics(evaluationCase.path("expectedGraph"), response.path("graphTrace"));
        ArrayNode failures = failureCategories(retrieval, citation, graph);
        return new CaseMetrics(retrieval, citation, graph, failures);
    }

    public ObjectNode aggregate(List<CaseMetrics> metrics) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode retrieval = root.putObject("retrieval");
        for (String stage : STAGES) {
            List<JsonNode> values = metrics.stream()
                    .map(value -> value.retrieval().path(stage))
                    .filter(JsonNode::isObject)
                    .toList();
            if (!values.isEmpty()) {
                retrieval.set(stage, averageObjects(values));
            }
        }
        List<ObjectNode> citations = metrics.stream()
                .map(CaseMetrics::citation)
                .filter(value -> value.path("applicable").asBoolean(true))
                .toList();
        if (!citations.isEmpty()) {
            root.set("citation", averageObjects(citations));
        }
        List<ObjectNode> graphs = metrics.stream()
                .map(CaseMetrics::graph)
                .filter(value -> value.path("applicable").asBoolean(false))
                .toList();
        if (!graphs.isEmpty()) {
            root.set("graph", averageObjects(graphs));
        }
        return root;
    }

    private ObjectNode retrievalMetrics(JsonNode groups, JsonNode candidates) {
        ObjectNode result = mapper.createObjectNode();
        for (int k : K_VALUES) {
            int covered = coveredGroups(groups, candidates, k).size();
            result.put("hitAt" + k, covered > 0 ? 1.0 : 0.0);
            result.put("recallAt" + k, groups.isEmpty() ? 0.0 : (double) covered / groups.size());
        }
        double mrr = 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (!coveredGroups(groups, candidates, index + 1).isEmpty()) {
                mrr = 1.0 / (index + 1);
                break;
            }
        }
        result.put("mrr", mrr);
        return result;
    }

    private Set<Integer> coveredGroups(JsonNode groups, JsonNode candidates, int limit) {
        Set<Integer> covered = new LinkedHashSet<>();
        for (int candidateIndex = 0;
                candidateIndex < Math.min(limit, candidates.size());
                candidateIndex++) {
            JsonNode candidate = candidates.get(candidateIndex);
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                if (!covered.contains(groupIndex) && matchesGroup(groups.get(groupIndex), candidate)) {
                    covered.add(groupIndex);
                }
            }
        }
        return covered;
    }

    private boolean matchesGroup(JsonNode group, JsonNode candidate) {
        for (JsonNode alternative : group.path("alternatives")) {
            if (matchesAlternative(alternative, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAlternative(JsonNode alternative, JsonNode candidate) {
        Set<String> expectedBlocks = strings(alternative.path("sourceBlockIds"));
        Set<String> actualBlocks = strings(candidate.path("sourceBlockIds"));
        if (!expectedBlocks.isEmpty() && expectedBlocks.stream().anyMatch(actualBlocks::contains)) {
            return true;
        }
        String quote = normalizeText(alternative.path("quoteText").asText(""));
        if (quote.isEmpty()) {
            return false;
        }
        boolean pageMatch = pageOverlap(alternative, candidate);
        String expectedSection = normalizeKey(alternative.path("sectionPath").asText(""));
        String actualSection = normalizeKey(candidate.path("sectionPath").asText(""));
        boolean sectionMatch = !expectedSection.isEmpty() && expectedSection.equals(actualSection);
        String excerpt = normalizeText(candidate.path("excerpt").asText(""));
        return !excerpt.isEmpty()
                && (pageMatch || sectionMatch)
                && (excerpt.contains(quote) || quote.contains(excerpt));
    }

    private boolean pageOverlap(JsonNode expected, JsonNode actual) {
        Integer expectedStart = integer(expected, "pageStart", integer(expected, "pageEnd", null));
        Integer expectedEnd = integer(expected, "pageEnd", expectedStart);
        Integer actualStart = integer(actual, "pageStart", integer(actual, "pageNumber", null));
        Integer actualEnd = integer(actual, "pageEnd", actualStart);
        return expectedStart != null
                && expectedEnd != null
                && actualStart != null
                && actualEnd != null
                && expectedStart <= actualEnd
                && actualStart <= expectedEnd;
    }

    private ObjectNode citationMetrics(JsonNode groups, JsonNode citations, JsonNode usedIds) {
        ObjectNode result = mapper.createObjectNode().put("applicable", true);
        Map<Integer, JsonNode> byId = new HashMap<>();
        if (citations.isArray()) {
            citations.forEach(value -> byId.put(value.path("id").asInt(), value));
        }
        int used = usedIds.isArray() ? usedIds.size() : 0;
        int valid = 0;
        int relevant = 0;
        Set<Integer> covered = new HashSet<>();
        if (usedIds.isArray()) {
            for (JsonNode id : usedIds) {
                JsonNode citation = byId.get(id.asInt());
                if (citation == null) {
                    continue;
                }
                valid++;
                for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                    if (matchesGroup(groups.get(groupIndex), citation)) {
                        covered.add(groupIndex);
                    }
                }
                if (!coveredGroupsForCandidate(groups, citation).isEmpty()) {
                    relevant++;
                }
            }
        }
        result.put("goldEvidencePrecision", used == 0 ? 0.0 : (double) relevant / used);
        result.put("goldEvidenceRecall", groups.isEmpty() ? 0.0 : (double) covered.size() / groups.size());
        result.put("validityRate", used == 0 ? 1.0 : (double) valid / used);
        return result;
    }

    private Set<Integer> coveredGroupsForCandidate(JsonNode groups, JsonNode candidate) {
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < groups.size(); i++) {
            if (matchesGroup(groups.get(i), candidate)) {
                result.add(i);
            }
        }
        return result;
    }

    private ObjectNode graphMetrics(JsonNode expected, JsonNode actual) {
        List<Set<String>> expectedEntities = new ArrayList<>();
        JsonNode requiredEntities = expected.path("requiredEntities");
        if (requiredEntities.isArray()) {
            for (JsonNode entity : requiredEntities) {
                Set<String> alternatives = new HashSet<>();
                alternatives.add(normalizeKey(
                        entity.isTextual() ? entity.asText() : entity.path("canonicalName").asText()));
                if (entity.path("aliases").isArray()) {
                    entity.path("aliases").forEach(alias -> alternatives.add(normalizeKey(alias.asText())));
                }
                alternatives.remove("");
                if (!alternatives.isEmpty()) {
                    expectedEntities.add(alternatives);
                }
            }
        }
        Set<String> expectedRelations = new HashSet<>();
        JsonNode requiredRelations = expected.path("requiredRelations");
        if (requiredRelations.isArray()) {
            requiredRelations.forEach(value -> expectedRelations.add(relationKey(value)));
        }
        ObjectNode result = mapper.createObjectNode();
        boolean applicable = !expectedEntities.isEmpty() || !expectedRelations.isEmpty();
        result.put("applicable", applicable);
        if (!applicable) {
            return result;
        }
        Set<String> actualEntities = new HashSet<>();
        actual.path("entities").forEach(value -> actualEntities.add(normalizeKey(value.path("canonicalName").asText())));
        Set<String> actualRelations = new HashSet<>();
        actual.path("relations").forEach(value -> actualRelations.add(relationKey(value)));
        long entityHits = expectedEntities.stream()
                .filter(alternatives -> alternatives.stream().anyMatch(actualEntities::contains))
                .count();
        long relationHits = expectedRelations.stream().filter(actualRelations::contains).count();
        if (!expectedEntities.isEmpty()) {
            result.put("entityRecall", (double) entityHits / expectedEntities.size());
        }
        if (!expectedRelations.isEmpty()) {
            result.put("relationRecall", (double) relationHits / expectedRelations.size());
        }
        result.put("graphHit", entityHits + relationHits > 0 ? 1.0 : 0.0);
        return result;
    }

    private ArrayNode failureCategories(ObjectNode retrieval, ObjectNode citation, ObjectNode graph) {
        ArrayNode failures = mapper.createArrayNode();
        if (retrieval.path("reranked").path("recallAt10").asDouble(0) < 1.0) {
            failures.add("RETRIEVAL_MISS");
        }
        if (citation.path("applicable").asBoolean(false)) {
            if (citation.path("validityRate").asDouble(1) < 1.0) {
                failures.add("CITATION_INVALID");
            }
            if (citation.path("goldEvidenceRecall").asDouble(0) < 1.0) {
                failures.add("CITATION_MISS");
            }
        }
        if (graph.path("applicable").asBoolean(false)) {
            if (graph.has("entityRecall") && graph.path("entityRecall").asDouble() < 1.0) {
                failures.add("GRAPH_ENTITY_MISS");
            }
            if (graph.has("relationRecall") && graph.path("relationRecall").asDouble() < 1.0) {
                failures.add("GRAPH_RELATION_MISS");
            }
        }
        return failures;
    }

    private ObjectNode averageObjects(List<? extends JsonNode> values) {
        Map<String, Double> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode value : values) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isNumber()) {
                    sums.merge(field.getKey(), field.getValue().asDouble(), Double::sum);
                    counts.merge(field.getKey(), 1, Integer::sum);
                }
            }
        }
        ObjectNode result = mapper.createObjectNode();
        sums.forEach((key, sum) -> result.put(key, sum / counts.get(key)));
        result.put("sampleCount", values.size());
        return result;
    }

    private Set<String> strings(JsonNode values) {
        Set<String> result = new HashSet<>();
        if (values.isArray()) {
            values.forEach(value -> result.add(value.asText()));
        }
        return result;
    }

    private String relationKey(JsonNode value) {
        return normalizeKey(value.path("sourceEntity").asText())
                + "|"
                + normalizeKey(value.path("relationType").asText())
                + "|"
                + normalizeKey(value.path("targetEntity").asText());
    }

    private Integer integer(JsonNode value, String field, Integer fallback) {
        if (value.hasNonNull(field) && value.path(field).canConvertToInt()) {
            return Integer.valueOf(value.path(field).asInt());
        }
        return fallback;
    }

    private String normalizeText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "")
                .trim();
    }

    private String normalizeKey(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-]+", "")
                .trim();
    }

    public record CaseMetrics(
            ObjectNode retrieval, ObjectNode citation, ObjectNode graph, ArrayNode failures) {
    }
}
