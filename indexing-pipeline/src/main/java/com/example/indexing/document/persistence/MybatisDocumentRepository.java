package com.example.indexing.document.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.indexing.document.DocumentRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

@Repository
public class MybatisDocumentRepository implements DocumentRepository {
    private final DocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final PipelineStepMapper steps;

    public MybatisDocumentRepository(
            DocumentMapper documents, DocumentVersionMapper versions, PipelineStepMapper steps) {
        this.documents = documents;
        this.versions = versions;
        this.steps = steps;
    }

    @Override
    public Optional<DocumentEntity> findById(String id) {
        return Optional.ofNullable(documents.selectById(id));
    }

    @Override
    public Optional<DocumentEntity> findByHash(String hash) {
        return Optional.ofNullable(
                documents.selectOne(new QueryWrapper<DocumentEntity>().eq("content_hash", hash)));
    }

    @Override
    public Optional<DocumentEntity> findByFileName(String name) {
        return Optional.ofNullable(
                documents.selectOne(
                        new QueryWrapper<DocumentEntity>().eq("file_name", name).last("LIMIT 1")));
    }

    @Override
    public List<DocumentEntity> findAll() {
        return documents.selectList(new QueryWrapper<DocumentEntity>().orderByDesc("created_at"));
    }

    @Override
    public void insert(DocumentEntity d) {
        documents.insert(d);
    }

    @Override
    public void update(DocumentEntity d) {
        documents.updateById(d);
    }

    @Override
    public void delete(String id) {
        documents.deleteById(id);
    }

    @Override
    public void insertVersion(DocumentVersionEntity v) {
        versions.insert(v);
    }

    @Override
    public void updateVersion(DocumentVersionEntity v) {
        versions.updateById(v);
    }

    @Override
    public void deleteVersion(String id) {
        versions.deleteById(id);
    }

    @Override
    public List<DocumentVersionEntity> findVersions(String documentId) {
        return versions.selectList(
                new QueryWrapper<DocumentVersionEntity>().eq("document_id", documentId));
    }

    @Override
    public Optional<DocumentVersionEntity> findVersion(String versionId) {
        return Optional.ofNullable(versions.selectById(versionId));
    }

    @Override
    public List<PipelineStepEntity> findSteps(String versionId) {
        return steps.selectList(
                new QueryWrapper<PipelineStepEntity>()
                        .eq("version_id", versionId)
                        .last("ORDER BY FIELD(step_code,'upload','docling','chunking','embedding','elasticsearch','graph','activate')"));
    }

    @Override
    public Optional<PipelineStepEntity> findStep(String versionId, String stepCode) {
        return Optional.ofNullable(
                steps.selectOne(
                        new QueryWrapper<PipelineStepEntity>()
                                .eq("version_id", versionId)
                                .eq("step_code", stepCode)));
    }

    @Override
    public void insertStep(PipelineStepEntity step) {
        steps.insert(step);
    }

    @Override
    public void updateStep(PipelineStepEntity step) {
        steps.update(
                step,
                new QueryWrapper<PipelineStepEntity>()
                        .eq("version_id", step.versionId)
                        .eq("step_code", step.stepCode));
    }

    public Map<String, String> activeVersions(List<String> ids) {
        QueryWrapper<DocumentEntity> q =
                new QueryWrapper<DocumentEntity>().isNotNull("active_version_id");
        if (ids != null && !ids.isEmpty()) {
            q.in("id", ids);
        }
        Map<String, String> result = new LinkedHashMap<>();
        documents.selectList(q).forEach(d -> result.put(d.id, d.activeVersionId));
        return result;
    }
}
