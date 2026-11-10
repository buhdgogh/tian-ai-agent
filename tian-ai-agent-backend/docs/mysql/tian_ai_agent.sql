-- Tian 知识工作台 MySQL 初始化脚本
-- 适用于 MySQL 8.0.16+ 和 8.4。请先选中应用连接的目标库。
SET NAMES utf8mb4;

-- 会话与消息历史
CREATE TABLE IF NOT EXISTS agent_conversation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memory_space VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_id VARCHAR(220) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_storage (memory_space, storage_id),
    KEY idx_conversation_listing (owner_id, memory_space, scope_id, updated_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE IF NOT EXISTS agent_conversation_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content LONGTEXT NOT NULL,
    tool_calls JSON NULL,
    tool_responses JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_conversation_message_order (conversation_id, id),
    CONSTRAINT fk_conversation_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES agent_conversation (id) ON DELETE CASCADE,
    CONSTRAINT chk_conversation_message_role
        CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM'))
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- 模型设置按访问令牌解析出的账号归属保存。空配置也保留账号记录。
CREATE TABLE IF NOT EXISTS agent_model_settings (
    owner_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (owner_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE IF NOT EXISTS agent_model_provider (
    owner_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sort_order INT UNSIGNED NOT NULL,
    protocol VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    api_key_ciphertext VARBINARY(4200) NOT NULL,
    priority INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    workspace_id VARCHAR(160) NULL,
    stream_usage BOOLEAN NOT NULL,
    reasoning_effort VARCHAR(30) NULL,
    PRIMARY KEY (owner_id, provider_id),
    UNIQUE KEY uk_model_provider_position (owner_id, sort_order),
    CONSTRAINT fk_model_provider_settings
        FOREIGN KEY (owner_id) REFERENCES agent_model_settings (owner_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- 按账号保存远程 MCP 连接和用户授权的工具定义。
CREATE TABLE IF NOT EXISTS agent_mcp_server (
    owner_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    server_id VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    endpoint_url VARCHAR(500) NOT NULL,
    token_ciphertext VARBINARY(4200) NULL,
    enabled BOOLEAN NOT NULL,
    tools_json JSON NOT NULL,
    PRIMARY KEY (owner_id, server_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- 按账号保存 Skill 和 Prompt 模板。
CREATE TABLE IF NOT EXISTS agent_tool_credential (
    owner_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ciphertext BLOB NOT NULL,
    PRIMARY KEY (owner_id, tool)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE IF NOT EXISTS agent_preset (
    owner_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preset_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preset_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (owner_id, preset_kind, preset_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- Agent 运行链路与模型调用审计
CREATE TABLE IF NOT EXISTS agent_run (
    id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    parent_run_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    started_at DATETIME(6) NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    event_count INT NOT NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    total_tokens BIGINT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_run_owner_time (owner_id, started_at, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- 旧库原地改名，保留已保存的事件和约束。
SET @agent_request_log_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'agent_run_event' AND table_type = 'BASE TABLE')
    AND NOT EXISTS (SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'agent_request_log'),
    'RENAME TABLE agent_run_event TO agent_request_log', 'DO 0'
);
PREPARE agent_request_log_migration FROM @agent_request_log_ddl;
EXECUTE agent_request_log_migration;
DEALLOCATE PREPARE agent_request_log_migration;

CREATE TABLE IF NOT EXISTS agent_request_log (
    run_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_no INT NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name TEXT NOT NULL,
    provider_id TEXT NULL,
    model_name TEXT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    started_at DATETIME(6) NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    total_tokens INT NULL,
    reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (run_id, event_no),
    KEY idx_agent_request_log_kind_time (kind, started_at),
    CONSTRAINT fk_agent_request_log_run
        FOREIGN KEY (run_id) REFERENCES agent_run (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- 用户画像按账号保存，独立于单条会话。
CREATE TABLE IF NOT EXISTS agent_account_profile (
    owner_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    facts_json JSON NOT NULL,
    preferences_json JSON NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (owner_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- 兼容旧版 MySQL 会话摘要。新压缩记忆保存在 Milvus。
CREATE TABLE IF NOT EXISTS agent_long_term_memory (
    owner_id VARCHAR(300) NOT NULL,
    summary_text LONGTEXT NOT NULL,
    facts_json LONGTEXT NOT NULL,
    source_message_count INT NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (owner_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
