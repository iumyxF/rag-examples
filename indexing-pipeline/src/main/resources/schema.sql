CREATE TABLE IF NOT EXISTS rag_document
(
    id                VARCHAR(36) PRIMARY KEY,
    file_name         VARCHAR(512)  NOT NULL,
    content_hash      CHAR(64)      NOT NULL,
    media_type        VARCHAR(128),
    storage_path      VARCHAR(1024) NOT NULL,
    status            VARCHAR(32)   NOT NULL,
    active_version_id VARCHAR(36),
    working_version_id VARCHAR(36),
    created_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_rag_document_hash (content_hash),
    KEY idx_rag_document_file_name (file_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS rag_document_version
(
    id             VARCHAR(36) PRIMARY KEY,
    document_id    VARCHAR(36)  NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    source_version_id VARCHAR(36),
    chunk_count    INT          NOT NULL DEFAULT 0,
    entity_count   INT          NOT NULL DEFAULT 0,
    relation_count INT          NOT NULL DEFAULT 0,
    error_message  TEXT,
    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    activated_at   TIMESTAMP(6),
    KEY idx_version_document (document_id),
    CONSTRAINT fk_version_document
        FOREIGN KEY (document_id) REFERENCES rag_document (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS rag_pipeline_step
(
    version_id     VARCHAR(36)  NOT NULL,
    step_code      VARCHAR(32)  NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    started_at     TIMESTAMP(6),
    finished_at    TIMESTAMP(6),
    duration_ms    BIGINT,
    item_count     INT          NOT NULL DEFAULT 0,
    output_summary TEXT,
    error_message  TEXT,
    PRIMARY KEY (version_id, step_code),
    CONSTRAINT fk_step_version
        FOREIGN KEY (version_id) REFERENCES rag_document_version (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kg_entity
(
    id             VARCHAR(36) PRIMARY KEY,
    canonical_name VARCHAR(512) NOT NULL,
    normalized_key VARCHAR(512) NOT NULL,
    entity_type    VARCHAR(128),
    description    TEXT,
    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_entity_normalized (normalized_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kg_entity_mention
(
    id            VARCHAR(36) PRIMARY KEY,
    entity_id     VARCHAR(36)  NOT NULL,
    document_id   VARCHAR(36)  NOT NULL,
    version_id    VARCHAR(36)  NOT NULL,
    chunk_id      VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(512) NOT NULL,
    page_number   INT,
    sheet_name    VARCHAR(255),
    section_path  VARCHAR(1024),
    evidence_text TEXT         NOT NULL,
    KEY idx_mention_entity (entity_id),
    KEY idx_mention_version (version_id),
    KEY idx_mention_chunk (chunk_id),
    CONSTRAINT fk_mention_entity
        FOREIGN KEY (entity_id) REFERENCES kg_entity (id),
    CONSTRAINT fk_mention_document
        FOREIGN KEY (document_id) REFERENCES rag_document (id) ON DELETE CASCADE,
    CONSTRAINT fk_mention_version
        FOREIGN KEY (version_id) REFERENCES rag_document_version (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kg_relation
(
    id               VARCHAR(36) PRIMARY KEY,
    source_entity_id VARCHAR(36)   NOT NULL,
    target_entity_id VARCHAR(36)   NOT NULL,
    relation_type    VARCHAR(255)  NOT NULL,
    raw_relation     VARCHAR(512),
    confidence       DECIMAL(5, 4) NOT NULL,
    document_id      VARCHAR(36)   NOT NULL,
    version_id       VARCHAR(36)   NOT NULL,
    chunk_id         VARCHAR(64)   NOT NULL,
    evidence_text    TEXT          NOT NULL,
    KEY idx_relation_source (source_entity_id),
    KEY idx_relation_target (target_entity_id),
    KEY idx_relation_version (version_id),
    CONSTRAINT fk_relation_source
        FOREIGN KEY (source_entity_id) REFERENCES kg_entity (id),
    CONSTRAINT fk_relation_target
        FOREIGN KEY (target_entity_id) REFERENCES kg_entity (id),
    CONSTRAINT fk_relation_document
        FOREIGN KEY (document_id) REFERENCES rag_document (id) ON DELETE CASCADE,
    CONSTRAINT fk_relation_version
        FOREIGN KEY (version_id) REFERENCES rag_document_version (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
