package com.example.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasetService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;

    public DatasetService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
    }

    public Map<String, Object> importJsonl(
            String datasetName, String description, MultipartFile file) {
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException("数据集名称不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("JSONL 文件不能为空");
        }
        try {
            byte[] bytes = file.getBytes();
            String payload = new String(bytes, StandardCharsets.UTF_8);
            List<JsonNode> cases = parseAndValidate(payload);
            String hash = sha256(bytes);
            return transaction.execute(status -> persist(
                    datasetName.strip(), description, file.getOriginalFilename(), payload, hash, cases));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("导入评测集失败: " + error.getMessage(), error);
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> datasets = jdbc.queryForList(
                "SELECT d.id,d.name,d.description,d.created_at,d.updated_at,"
                        + "COUNT(r.id) revision_count,COALESCE(SUM(r.case_count),0) imported_case_count "
                        + "FROM eval_dataset d LEFT JOIN eval_dataset_revision r ON r.dataset_id=d.id "
                        + "GROUP BY d.id,d.name,d.description,d.created_at,d.updated_at ORDER BY d.updated_at DESC");
        datasets.forEach(dataset -> dataset.put(
                "revisions",
                jdbc.queryForList(
                        "SELECT id,revision_hash,original_filename,case_count,imported_at "
                                + "FROM eval_dataset_revision WHERE dataset_id=? ORDER BY imported_at DESC",
                        dataset.get("id"))));
        return datasets;
    }

    public Map<String, Object> revision(String id) {
        List<Map<String, Object>> revisions = jdbc.queryForList(
                "SELECT r.*,d.name dataset_name,d.description dataset_description "
                        + "FROM eval_dataset_revision r JOIN eval_dataset d ON d.id=r.dataset_id WHERE r.id=?",
                id);
        if (revisions.isEmpty()) {
            throw new IllegalArgumentException("评测集 revision 不存在: " + id);
        }
        Map<String, Object> result = new LinkedHashMap<>(revisions.getFirst());
        result.remove("source_payload");
        result.put("cases", cases(id));
        return result;
    }

    public List<Map<String, Object>> cases(String revisionId) {
        return jdbc.query(
                "SELECT * FROM eval_case WHERE revision_id=? ORDER BY ordinal_no",
                (rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", rs.getString("id"));
                    value.put("revisionId", rs.getString("revision_id"));
                    value.put("externalCaseId", rs.getString("external_case_id"));
                    value.put("ordinal", rs.getInt("ordinal_no"));
                    value.put("question", rs.getString("question"));
                    value.put("referenceAnswer", rs.getString("reference_answer"));
                    value.put("documentId", rs.getString("document_id"));
                    value.put("sourceContentHash", rs.getString("source_content_hash"));
                    value.put("category", rs.getString("category"));
                    value.put("tags", readJson(rs.getString("tags_json")));
                    value.put("evidenceGroups", readJson(rs.getString("evidence_groups_json")));
                    value.put("expectedGraph", readJson(rs.getString("expected_graph_json")));
                    return value;
                },
                revisionId);
    }

    private Map<String, Object> persist(
            String name,
            String description,
            String fileName,
            String payload,
            String hash,
            List<JsonNode> cases) {
        String datasetId = jdbc.query(
                "SELECT id FROM eval_dataset WHERE name=?",
                rs -> rs.next() ? rs.getString(1) : null,
                name);
        if (datasetId == null) {
            datasetId = UUID.randomUUID().toString();
            jdbc.update(
                    "INSERT INTO eval_dataset(id,name,description) VALUES (?,?,?)",
                    datasetId,
                    name,
                    description);
        }
        List<String> existing = jdbc.queryForList(
                "SELECT id FROM eval_dataset_revision WHERE dataset_id=? AND revision_hash=?",
                String.class,
                datasetId,
                hash);
        if (!existing.isEmpty()) {
            return revision(existing.getFirst());
        }
        String revisionId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO eval_dataset_revision(id,dataset_id,revision_hash,original_filename,case_count,source_payload) "
                        + "VALUES (?,?,?,?,?,?)",
                revisionId,
                datasetId,
                hash,
                fileName == null ? "dataset.jsonl" : fileName,
                cases.size(),
                payload);
        int ordinal = 0;
        for (JsonNode item : cases) {
            jdbc.update(
                    "INSERT INTO eval_case(id,revision_id,external_case_id,ordinal_no,question,reference_answer,"
                            + "document_id,source_content_hash,category,tags_json,evidence_groups_json,expected_graph_json) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    revisionId,
                    item.path("id").asText(),
                    ordinal++,
                    item.path("question").asText(),
                    textOrNull(item, "referenceAnswer"),
                    item.path("documentId").asText(),
                    item.path("sourceContentHash").asText(),
                    textOrNull(item, "category"),
                    json(item.path("tags").isMissingNode() ? mapper.createArrayNode() : item.path("tags")),
                    json(item.path("evidenceGroups")),
                    json(item.path("expectedGraph").isMissingNode()
                            ? mapper.createObjectNode()
                            : item.path("expectedGraph")));
        }
        return revision(revisionId);
    }

    private List<JsonNode> parseAndValidate(String payload) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String[] lines = payload.replace("\r\n", "\n").split("\n");
        for (int line = 0; line < lines.length; line++) {
            if (lines[line].isBlank()) {
                continue;
            }
            JsonNode item;
            try {
                item = mapper.readTree(lines[line]);
            } catch (Exception error) {
                throw new IllegalArgumentException("第 " + (line + 1) + " 行不是合法 JSON: " + error.getMessage());
            }
            String prefix = "第 " + (line + 1) + " 行";
            requireText(item, "id", prefix);
            requireText(item, "question", prefix);
            requireText(item, "documentId", prefix);
            requireText(item, "sourceContentHash", prefix);
            String contentHash = item.path("sourceContentHash").asText()
                    .replaceFirst("(?i)^sha256:", "");
            if (!contentHash.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException(prefix + " sourceContentHash 必须是 SHA-256 十六进制值");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("sourceContentHash", contentHash.toLowerCase());
            if (!ids.add(item.path("id").asText())) {
                throw new IllegalArgumentException(prefix + " case id 重复: " + item.path("id").asText());
            }
            JsonNode groups = item.path("evidenceGroups");
            if (!groups.isArray() || groups.isEmpty()) {
                throw new IllegalArgumentException(prefix + " evidenceGroups 至少包含一组证据");
            }
            for (JsonNode group : groups) {
                JsonNode alternatives = group.path("alternatives");
                if (!alternatives.isArray() || alternatives.isEmpty()) {
                    throw new IllegalArgumentException(prefix + " 每个证据组至少包含一个 alternative");
                }
                for (JsonNode alternative : alternatives) {
                    boolean blockLocator = alternative.path("sourceBlockIds").isArray()
                            && !alternative.path("sourceBlockIds").isEmpty();
                    boolean quoteLocator = alternative.hasNonNull("quoteText")
                            && !alternative.path("quoteText").asText().isBlank()
                            && (alternative.has("pageStart")
                                    || alternative.has("pageEnd")
                                    || alternative.hasNonNull("sectionPath"));
                    if (!blockLocator && !quoteLocator) {
                        throw new IllegalArgumentException(
                                prefix + " alternative 必须提供 sourceBlockIds，或 quoteText 加页码/章节");
                    }
                }
            }
            result.add(item);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("JSONL 中没有评测案例");
        }
        return result;
    }

    private void requireText(JsonNode item, String field, String prefix) {
        if (!item.hasNonNull(field) || item.path(field).asText().isBlank()) {
            throw new IllegalArgumentException(prefix + " 缺少 " + field);
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

    private String textOrNull(JsonNode item, String field) {
        return item.hasNonNull(field) && !item.path(field).asText().isBlank()
                ? item.path(field).asText()
                : null;
    }

    private String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }
}
