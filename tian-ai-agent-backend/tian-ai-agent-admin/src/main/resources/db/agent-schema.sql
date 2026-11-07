CREATE TABLE IF NOT EXISTS agent_task (
  id VARCHAR(32) PRIMARY KEY,
  type VARCHAR(40) NOT NULL,
  status VARCHAR(24) NOT NULL,
  owner_id VARCHAR(128) NOT NULL,
  progress INT NOT NULL,
  stage VARCHAR(255),
  input_json LONGTEXT NOT NULL,
  result_json LONGTEXT,
  error_text LONGTEXT,
  events_json LONGTEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  started_at VARCHAR(40),
  finished_at VARCHAR(40),
  INDEX idx_task_owner_created (owner_id, created_at),
  INDEX idx_task_type_status (type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS agent_tool_audit (
  id VARCHAR(32) PRIMARY KEY,
  task_id VARCHAR(32),
  owner_id VARCHAR(128),
  chat_id VARCHAR(128),
  step_id VARCHAR(80),
  iteration INT,
  tool_call_id VARCHAR(255),
  tool_name VARCHAR(255) NOT NULL,
  status VARCHAR(24) NOT NULL,
  duration_ms BIGINT NOT NULL,
  argument_digest VARCHAR(64),
  error_text LONGTEXT,
  created_at VARCHAR(40) NOT NULL,
  INDEX idx_audit_task_time (task_id, created_at),
  INDEX idx_audit_owner_time (owner_id, created_at),
  INDEX idx_audit_chat_time (chat_id, created_at),
  INDEX idx_audit_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS agent_user_profile (
  user_id VARCHAR(128) PRIMARY KEY,
  display_name VARCHAR(100),
  preferred_language VARCHAR(40),
  preferences_json LONGTEXT NOT NULL,
  confirmed_facts_json LONGTEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS agent_user (
  id VARCHAR(128) PRIMARY KEY,
  username VARCHAR(128) NOT NULL UNIQUE,
  password_hash VARCHAR(512) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  last_login_at VARCHAR(40)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS agent_chat_session (
  id VARCHAR(32) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  chat_id VARCHAR(128) NOT NULL,
  type VARCHAR(24) NOT NULL,
  title VARCHAR(255) NOT NULL,
  message_count INT NOT NULL DEFAULT 0,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  UNIQUE KEY uk_chat_user_type (user_id, chat_id, type),
  INDEX idx_chat_user_time (user_id, type, updated_at),
  FOREIGN KEY (user_id) REFERENCES agent_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS agent_chat_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(32) NOT NULL,
  turn_id VARCHAR(32) NOT NULL,
  role VARCHAR(24) NOT NULL,
  content LONGTEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  metadata_json LONGTEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  INDEX idx_message_session (session_id, id),
  FOREIGN KEY (session_id) REFERENCES agent_chat_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
