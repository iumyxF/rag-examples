package com.example.evaluation.run;

import com.example.evaluation.common.ApiExceptionHandler.ConflictException;
import com.example.evaluation.dataset.DatasetService;
import com.example.evaluation.metric.MetricEngine;
import com.example.evaluation.metric.MetricEngine.CaseMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final JdbcTemplate jdbc;
    private final DatasetService datasets;
    private final TargetClient target;
    private final MetricEngine metrics;
    private final ObjectMapper mapper;
    private final String codeRevision;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "evaluation-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean active = new AtomicBoolean();

    public RunService(
            JdbcTemplate jdbc,
            DatasetService datasets,
            TargetClient target,
            MetricEngine metrics,
            ObjectMapper mapper,
            @Value("${evaluation.code-revision:unknown}") String codeRevision) {
        this.jdbc = jdbc;
        this.datasets = datasets;
        this.target = target;
        this.metrics = metrics;
        this.mapper = mapper;
        this.codeRevision = codeRevision;
    }

    @PostConstruct
    void interruptOrphans() {
        jdbc.update(
                "UPDATE eval_run SET status='INTERRUPTED',finished_at=CURRENT_TIMESTAMP(6),"
                        + "error_message='应用重启导致运行中断' WHERE status IN ('PENDING','RUNNING')");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public synchronized Map<String, Object> create(CreateRun request) {
        if (request.revisionId() == null || request.revisionId().isBlank()) {
            throw new IllegalArgumentException("revisionId 不能为空");
        }
        target.validateBaseUrl(request.targetBaseUrl());
        if (active.get() || jdbc.queryForObject(
                "SELECT COUNT(*) FROM eval_run WHERE status IN ('PENDING','RUNNING')", Integer.class) > 0) {
            throw new ConflictException("已有评测运行正在执行");
        }
        List<Map<String, Object>> cases = datasets.cases(request.revisionId());
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("revision 不存在或没有案例");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO eval_run(id,revision_id,name,status,target_base_url,generate_answer,"
                        + "request_options_json,code_revision,total_cases) VALUES (?,?,?,'PENDING',?,?,?,?,?)",
                id,
                request.revisionId(),
                request.name() == null || request.name().isBlank() ? "Evaluation " + LocalDateTime.now() : request.name(),
                request.targetBaseUrl(),
                request.generateAnswer(),
                "{\"retryCount\":1}",
                codeRevision,
                cases.size());
        active.set(true);
        executor.submit(() -> execute(id, request, cases));
        return get(id);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT id,revision_id,name,status,target_base_url,generate_answer,total_cases,"
                        + "completed_cases,failed_cases,started_at,finished_at,created_at "
                        + "FROM eval_run ORDER BY created_at DESC");
    }

    public Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM eval_run WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Run 不存在: " + id);
        }
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        parseJsonField(result, "request_options_json");
        parseJsonField(result, "runtime_snapshot_json");
        parseJsonField(result, "summary_metrics_json");
        return result;
    }

    public List<Map<String, Object>> results(String runId) {
        requireRun(runId);
        return jdbc.query(
                "SELECT r.*,c.external_case_id,c.question,c.reference_answer,c.document_id,"
                        + "c.source_content_hash,c.evidence_groups_json,c.expected_graph_json "
                        + "FROM eval_case_result r JOIN eval_case c ON c.id=r.case_id "
                        + "WHERE r.run_id=? ORDER BY c.ordinal_no",
                (rs, row) -> resultRow(rs),
                runId);
    }

    public Map<String, Object> result(String runId, String caseId) {
        return results(runId).stream()
                .filter(value -> caseId.equals(value.get("caseId"))
                        || caseId.equals(value.get("externalCaseId")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("单题结果不存在: " + caseId));
    }

    public String failureExport(String runId) {
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> result : results(runId)) {
            JsonNode failures = (JsonNode) result.get("failureCategories");
            if ("FAILED".equals(result.get("status")) || (failures != null && !failures.isEmpty())) {
                try {
                    jsonl.append(mapper.writeValueAsString(result)).append('\n');
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }
        }
        return jsonl.toString();
    }

    private void execute(String runId, CreateRun request, List<Map<String, Object>> cases) {
        JsonNode snapshot = null;
        List<CaseMetrics> successfulMetrics = new ArrayList<>();
        int completed = 0;
        int failed = 0;
        try {
            jdbc.update(
                    "UPDATE eval_run SET status='RUNNING',started_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    runId);
            for (Map<String, Object> evaluationCase : cases) {
                try {
                    long started = System.nanoTime();
                    JsonNode response = callWithRetry(request, evaluationCase);
                    long latency = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    JsonNode runtime = response.path("runtimeConfiguration");
                    validateSource(evaluationCase, runtime);
                    if (snapshot == null) {
                        snapshot = runtime.deepCopy();
                        jdbc.update(
                                "UPDATE eval_run SET runtime_snapshot_json=? WHERE id=?",
                                json(snapshot),
                                runId);
                    } else if (!snapshot.equals(runtime)) {
                        throw new FatalRunException("运行期间被测目标的 runtimeConfiguration 发生变化");
                    }
                    JsonNode caseNode = mapper.valueToTree(evaluationCase);
                    CaseMetrics calculated = metrics.calculate(caseNode, response, request.generateAnswer());
                    successfulMetrics.add(calculated);
                    saveSuccess(runId, evaluationCase, latency, response, calculated);
                } catch (FatalRunException fatal) {
                    throw fatal;
                } catch (Exception error) {
                    failed++;
                    log.error(
                            "Evaluation case failed: runId={}, caseId={}, externalCaseId={}, question={}",
                            runId,
                            evaluationCase.get("id"),
                            evaluationCase.get("externalCaseId"),
                            evaluationCase.get("question"),
                            error);
                    saveFailure(runId, evaluationCase, error);
                }
                completed++;
                jdbc.update(
                        "UPDATE eval_run SET completed_cases=?,failed_cases=? WHERE id=?",
                        completed,
                        failed,
                        runId);
            }
            String status = failed == 0 ? "SUCCEEDED" : "COMPLETED_WITH_ERRORS";
            jdbc.update(
                    "UPDATE eval_run SET status=?,summary_metrics_json=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    status,
                    json(metrics.aggregate(successfulMetrics)),
                    runId);
        } catch (Exception fatal) {
            jdbc.update(
                    "UPDATE eval_run SET status='FAILED',finished_at=CURRENT_TIMESTAMP(6),error_message=? WHERE id=?",
                    message(fatal),
                    runId);
            log.error("Evaluation run failed: runId={}", runId, fatal);
        } finally {
            active.set(false);
        }
    }

    private JsonNode callWithRetry(CreateRun request, Map<String, Object> evaluationCase) {
        RuntimeException first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return target.query(
                        request.targetBaseUrl(),
                        String.valueOf(evaluationCase.get("question")),
                        String.valueOf(evaluationCase.get("documentId")),
                        request.generateAnswer());
            } catch (RuntimeException error) {
                first = error;
            }
        }
        throw new IllegalStateException("调用被测服务失败（已重试一次）: " + message(first), first);
    }

    private void validateSource(Map<String, Object> evaluationCase, JsonNode runtime) {
        String documentId = String.valueOf(evaluationCase.get("documentId"));
        String expected = String.valueOf(evaluationCase.get("sourceContentHash"));
        String actual = runtime.path("sourceContentHashes").path(documentId).asText();
        if (actual.isBlank() || !expected.equalsIgnoreCase(actual)) {
            throw new FatalRunException(
                    "源文档哈希不匹配: documentId=" + documentId + ", expected=" + expected + ", actual=" + actual);
        }
    }

    private void saveSuccess(
            String runId,
            Map<String, Object> evaluationCase,
            long latency,
            JsonNode response,
            CaseMetrics calculated) {
        jdbc.update(
                "INSERT INTO eval_case_result(id,run_id,case_id,status,latency_ms,raw_response_json,"
                        + "retrieval_metrics_json,citation_metrics_json,graph_metrics_json,failure_categories_json) "
                        + "VALUES (?,?,?,'SUCCEEDED',?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                runId,
                evaluationCase.get("id"),
                latency,
                json(response),
                json(calculated.retrieval()),
                json(calculated.citation()),
                json(calculated.graph()),
                json(calculated.failures()));
    }

    private void saveFailure(String runId, Map<String, Object> evaluationCase, Exception error) {
        ArrayNode failures = mapper.createArrayNode().add("TARGET_ERROR");
        jdbc.update(
                "INSERT INTO eval_case_result(id,run_id,case_id,status,failure_categories_json,error_message) "
                        + "VALUES (?,?,?,'FAILED',?,?)",
                UUID.randomUUID().toString(),
                runId,
                evaluationCase.get("id"),
                json(failures),
                message(error));
    }

    private Map<String, Object> resultRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", rs.getString("id"));
        value.put("runId", rs.getString("run_id"));
        value.put("caseId", rs.getString("case_id"));
        value.put("externalCaseId", rs.getString("external_case_id"));
        value.put("question", rs.getString("question"));
        value.put("referenceAnswer", rs.getString("reference_answer"));
        value.put("documentId", rs.getString("document_id"));
        value.put("sourceContentHash", rs.getString("source_content_hash"));
        value.put("status", rs.getString("status"));
        value.put("latencyMs", rs.getObject("latency_ms"));
        value.put("evidenceGroups", readJson(rs.getString("evidence_groups_json")));
        value.put("expectedGraph", readJson(rs.getString("expected_graph_json")));
        value.put("rawResponse", readJson(rs.getString("raw_response_json")));
        value.put("retrievalMetrics", readJson(rs.getString("retrieval_metrics_json")));
        value.put("citationMetrics", readJson(rs.getString("citation_metrics_json")));
        value.put("graphMetrics", readJson(rs.getString("graph_metrics_json")));
        value.put("failureCategories", readJson(rs.getString("failure_categories_json")));
        value.put("errorMessage", rs.getString("error_message"));
        return value;
    }

    private void parseJsonField(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw != null) {
            value.put(field, readJson(String.valueOf(raw)));
        }
    }

    private JsonNode readJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String json(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void requireRun(String id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM eval_run WHERE id=?", Integer.class, id) == 0) {
            throw new IllegalArgumentException("Run 不存在: " + id);
        }
    }

    private String message(Throwable error) {
        return error == null || error.getMessage() == null ? String.valueOf(error) : error.getMessage();
    }

    public record CreateRun(
            String revisionId, String name, String targetBaseUrl, boolean generateAnswer) {
    }

    private static class FatalRunException extends RuntimeException {
        FatalRunException(String message) {
            super(message);
        }
    }
}
