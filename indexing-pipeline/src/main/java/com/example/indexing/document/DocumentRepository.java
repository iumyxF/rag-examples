package com.example.indexing.document;

import com.example.indexing.document.persistence.DocumentEntity;
import com.example.indexing.document.persistence.DocumentVersionEntity;
import com.example.indexing.document.persistence.PipelineStepEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DocumentRepository {
    Optional<DocumentEntity> findById(String id);

    Optional<DocumentEntity> findByHash(String hash);

    Optional<DocumentEntity> findByFileName(String fileName);

    List<DocumentEntity> findAll();

    void insert(DocumentEntity document);

    void update(DocumentEntity document);

    void delete(String id);

    void insertVersion(DocumentVersionEntity version);

    void updateVersion(DocumentVersionEntity version);

    void deleteVersion(String id);

    List<DocumentVersionEntity> findVersions(String documentId);

    Optional<DocumentVersionEntity> findVersion(String versionId);

    List<PipelineStepEntity> findSteps(String versionId);

    Optional<PipelineStepEntity> findStep(String versionId, String stepCode);

    void insertStep(PipelineStepEntity step);

    void updateStep(PipelineStepEntity step);

    Map<String, String> activeVersions(List<String> documentIds);
}
