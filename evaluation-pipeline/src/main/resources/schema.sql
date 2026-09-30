CREATE TABLE IF NOT EXISTS eval_dataset (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_eval_dataset_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS eval_dataset_revision (
    id VARCHAR(36) PRIMARY KEY,
    dataset_id VARCHAR(36) NOT NULL,
    revision_hash CHAR(64) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    case_count INT NOT NULL,
    source_payload MEDIUMTEXT NOT NULL,
    imported_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_eval_revision (dataset_id, revision_hash),
    CONSTRAINT fk_eval_revision_dataset FOREIGN KEY (dataset_id) REFERENCES eval_dataset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS eval_case (
    id VARCHAR(36) PRIMARY KEY,
    revision_id VARCHAR(36) NOT NULL,
    external_case_id VARCHAR(255) NOT NULL,
    ordinal_no INT NOT NULL,
    question TEXT NOT NULL,
    reference_answer TEXT,
    document_id VARCHAR(36) NOT NULL,
    source_content_hash VARCHAR(128) NOT NULL,
    category VARCHAR(128),
    tags_json JSON,
    evidence_groups_json JSON NOT NULL,
    expected_graph_json JSON,
    UNIQUE KEY uk_eval_case_external (revision_id, external_case_id),
    KEY idx_eval_case_revision (revision_id, ordinal_no),
    CONSTRAINT fk_eval_case_revision FOREIGN KEY (revision_id) REFERENCES eval_dataset_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS eval_run (
    id VARCHAR(36) PRIMARY KEY,
    revision_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_base_url VARCHAR(1024) NOT NULL,
    generate_answer BOOLEAN NOT NULL,
    request_options_json JSON,
    runtime_snapshot_json JSON,
    summary_metrics_json JSON,
    code_revision VARCHAR(255),
    total_cases INT NOT NULL DEFAULT 0,
    completed_cases INT NOT NULL DEFAULT 0,
    failed_cases INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    error_message TEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_eval_run_revision (revision_id),
    KEY idx_eval_run_status (status),
    CONSTRAINT fk_eval_run_revision FOREIGN KEY (revision_id) REFERENCES eval_dataset_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS eval_case_result (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    case_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    latency_ms BIGINT,
    raw_response_json JSON,
    retrieval_metrics_json JSON,
    citation_metrics_json JSON,
    graph_metrics_json JSON,
    failure_categories_json JSON,
    error_message TEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_eval_result_case (run_id, case_id),
    KEY idx_eval_result_status (run_id, status),
    CONSTRAINT fk_eval_result_run FOREIGN KEY (run_id) REFERENCES eval_run(id),
    CONSTRAINT fk_eval_result_case FOREIGN KEY (case_id) REFERENCES eval_case(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
