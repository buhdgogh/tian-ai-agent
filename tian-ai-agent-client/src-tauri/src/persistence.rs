use crate::{
    java_client::{Connection, JavaClient},
    sandbox::SandboxManager,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use sqlx::{
    MySqlPool, SqlitePool,
    mysql::{MySqlConnectOptions, MySqlPoolOptions},
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions},
};
use std::{
    path::PathBuf,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{State, async_runtime::Mutex};

const CREATE_SESSIONS: &str = "CREATE TABLE IF NOT EXISTS desktop_workbench_sessions (
    scope CHAR(64) NOT NULL, session_id CHAR(36) NOT NULL, created_at BIGINT NOT NULL,
    payload LONGBLOB NOT NULL, PRIMARY KEY (scope, session_id)
)";

#[derive(Clone)]
enum History {
    Sqlite(SqlitePool),
    MySql(MySqlPool),
}

macro_rules! on_history {
    ($history:expr, |$pool:ident| $query:expr) => {
        match $history {
            History::Sqlite($pool) => $query,
            History::MySql($pool) => $query,
        }
    };
}

pub struct Persistence {
    directory: Option<PathBuf>,
    local: Mutex<Option<SqlitePool>>,
    history: Mutex<Option<History>>,
}

#[derive(Debug, Serialize)]
pub struct StorageError {
    pub code: &'static str,
    pub message: String,
}

impl StorageError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    fn local() -> Self {
        Self::new(
            "storage_local",
            "无法读写本机桌面数据库，请检查应用数据目录的权限和磁盘空间。",
        )
    }

    fn query(history: &History) -> Self {
        match history {
            History::Sqlite(_) => Self::local(),
            History::MySql(_) => Self::new(
                "storage_mysql",
                "无法读写桌面 MySQL 数据库，请检查连接、数据库名称及建表和读写权限。",
            ),
        }
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    id: String,
    title: String,
    space_id: String,
    workspace_path: Option<String>,
    knowledge_only: Option<bool>,
    model_provider_id: Option<String>,
    shared_chat_id: Option<String>,
}

#[derive(Serialize, Deserialize)]
pub struct Snapshot {
    session: Session,
    messages: Vec<Value>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Workbench {
    scope: String,
    backend: &'static str,
    sessions: Vec<Snapshot>,
    standalone_path: Option<String>,
}

impl Persistence {
    pub fn new(directory: Option<PathBuf>) -> Self {
        Self {
            directory,
            local: Mutex::new(None),
            history: Mutex::new(None),
        }
    }

    async fn local(&self) -> Result<SqlitePool, StorageError> {
        let mut slot = self.local.lock().await;
        if let Some(pool) = slot.as_ref() {
            return Ok(pool.clone());
        }
        let directory = self.directory.as_ref().ok_or_else(StorageError::local)?;
        create_private_dir(directory).map_err(|_| StorageError::local())?;
        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .connect_with(
                SqliteConnectOptions::new()
                    .filename(directory.join("workbench.sqlite3"))
                    .create_if_missing(true)
                    .journal_mode(SqliteJournalMode::Wal)
                    .busy_timeout(Duration::from_secs(3)),
            )
            .await
            .map_err(|_| StorageError::local())?;
        for statement in [
            "CREATE TABLE IF NOT EXISTS desktop_device (name TEXT PRIMARY KEY, value BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS desktop_workspace_grants (
             scope TEXT NOT NULL, workspace_id TEXT NOT NULL, path TEXT NOT NULL,
             PRIMARY KEY (scope, workspace_id))",
        ] {
            sqlx::query(statement)
                .execute(&pool)
                .await
                .map_err(|_| StorageError::local())?;
        }
        *slot = Some(pool.clone());
        Ok(pool)
    }

    async fn history(&self) -> Result<History, StorageError> {
        let mut slot = self.history.lock().await;
        if let Some(history) = slot.as_ref() {
            return Ok(history.clone());
        }
        let url = match std::env::var("TIAN_DESKTOP_MYSQL_URL") {
            Ok(url) => url,
            Err(std::env::VarError::NotPresent) => String::new(),
            Err(_) => {
                return Err(StorageError::new(
                    "storage_mysql_config",
                    "TIAN_DESKTOP_MYSQL_URL 不是有效的连接地址。",
                ));
            }
        };
        let history = if url.trim().is_empty() {
            History::Sqlite(self.local().await?)
        } else {
            let options = url.trim().parse::<MySqlConnectOptions>().map_err(|_| {
                StorageError::new(
                    "storage_mysql_config",
                    "TIAN_DESKTOP_MYSQL_URL 需要有效的 MySQL 连接地址及数据库名。",
                )
            })?;
            let pool = MySqlPoolOptions::new()
                .max_connections(4)
                .acquire_timeout(Duration::from_secs(4))
                .connect_with(options)
                .await
                .map_err(|_| {
                    StorageError::new(
                        "storage_mysql",
                        "无法连接桌面 MySQL 数据库，请检查连接配置和数据库是否已创建。",
                    )
                })?;
            History::MySql(pool)
        };
        on_history!(&history, |pool| sqlx::query(CREATE_SESSIONS)
            .execute(pool)
            .await
            .map(|_| ()))
        .map_err(|_| StorageError::query(&history))?;
        *slot = Some(history.clone());
        Ok(history)
    }

    pub async fn scope(&self, client: &JavaClient) -> Result<String, StorageError> {
        let local = self.local().await?;
        sqlx::query("INSERT OR IGNORE INTO desktop_device (name, value) VALUES ('install', ?)")
            .bind(uuid::Uuid::new_v4().as_bytes().to_vec())
            .execute(&local)
            .await
            .map_err(|_| StorageError::local())?;
        let salt: Vec<u8> =
            sqlx::query_scalar("SELECT value FROM desktop_device WHERE name = 'install'")
                .fetch_one(&local)
                .await
                .map_err(|_| StorageError::local())?;
        let (port, token) = client.persistence_identity().await;
        let mut hash = Sha256::new();
        hash.update(salt);
        hash.update(port.to_be_bytes());
        hash.update(token.as_bytes());
        Ok(format!("{:x}", hash.finalize()))
    }

    pub async fn workspace_grant(
        &self,
        scope: &str,
        id: &str,
    ) -> Result<Option<String>, StorageError> {
        sqlx::query_scalar(
            "SELECT path FROM desktop_workspace_grants WHERE scope = ? AND workspace_id = ?",
        )
        .bind(scope)
        .bind(id)
        .fetch_optional(&self.local().await?)
        .await
        .map_err(|_| StorageError::local())
    }

    pub async fn grant(&self, scope: &str, id: &str, path: &str) -> Result<(), StorageError> {
        let result = sqlx::query(
            "INSERT INTO desktop_workspace_grants (scope, workspace_id, path) VALUES (?, ?, ?)
            ON CONFLICT(scope, workspace_id) DO UPDATE SET path = excluded.path
            WHERE excluded.workspace_id = 'standalone' OR desktop_workspace_grants.path = excluded.path",
        )
        .bind(scope)
        .bind(id)
        .bind(path)
        .execute(&self.local().await?)
        .await
        .map_err(|_| StorageError::local())?;
        if result.rows_affected() == 0 {
            return Err(StorageError::new(
                "workspace_locked",
                "会话已绑定工作区，请新建会话来选择其他目录。",
            ));
        }
        Ok(())
    }

    pub async fn save_access(&self, connection: &Connection) -> Result<(), StorageError> {
        let body = serde_json::to_vec(connection).map_err(|_| StorageError::local())?;
        let sealed = protect(body, true)?;
        sqlx::query(
            "INSERT INTO desktop_device (name, value) VALUES ('java_access', ?)
            ON CONFLICT(name) DO UPDATE SET value = excluded.value",
        )
        .bind(sealed)
        .execute(&self.local().await?)
        .await
        .map_err(|_| StorageError::local())?;
        Ok(())
    }

    pub async fn restore_access(&self) -> Result<Option<Connection>, StorageError> {
        let bytes: Option<Vec<u8>> =
            sqlx::query_scalar("SELECT value FROM desktop_device WHERE name = 'java_access'")
                .fetch_optional(&self.local().await?)
                .await
                .map_err(|_| StorageError::local())?;
        bytes
            .map(|bytes| {
                let restored = protect(bytes, false)?;
                serde_json::from_slice(&restored).map_err(|_| StorageError::local())
            })
            .transpose()
    }

    async fn save(
        &self,
        history: &History,
        scope: &str,
        snapshot: Snapshot,
    ) -> Result<(), StorageError> {
        let id = &snapshot.session.id;
        let payload = serde_json::to_vec(&snapshot).map_err(|_| StorageError::local())?;
        let created = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |time| time.as_millis() as i64);
        let suffix = match history {
            History::Sqlite(_) => {
                "ON CONFLICT(scope, session_id) DO UPDATE SET payload = excluded.payload"
            }
            History::MySql(_) => "ON DUPLICATE KEY UPDATE payload = VALUES(payload)",
        };
        let query = format!(
            "INSERT INTO desktop_workbench_sessions (scope, session_id, created_at, payload) VALUES (?, ?, ?, ?) {suffix}"
        );
        on_history!(history, |pool| sqlx::query(&query)
            .bind(scope)
            .bind(id)
            .bind(created)
            .bind(&payload)
            .execute(pool)
            .await
            .map(|_| ()))
        .map_err(|_| StorageError::query(history))?;
        Ok(())
    }

    async fn read(&self, history: &History, scope: &str) -> Result<Vec<Snapshot>, StorageError> {
        let rows: Vec<Vec<u8>> = on_history!(history, |pool| sqlx::query_scalar(
            "SELECT payload FROM desktop_workbench_sessions WHERE scope = ? ORDER BY created_at DESC, session_id DESC")
            .bind(scope).fetch_all(pool).await)
            .map_err(|_| StorageError::query(history))?;
        rows.into_iter()
            .map(|data| {
                serde_json::from_slice(&data)
                    .map_err(|_| StorageError::new("storage_corrupt", "历史会话记录无法读取。"))
            })
            .collect()
    }

    async fn delete(&self, history: &History, scope: &str, id: &str) -> Result<(), StorageError> {
        on_history!(history, |pool| sqlx::query(
            "DELETE FROM desktop_workbench_sessions WHERE scope = ? AND session_id = ?"
        )
        .bind(scope)
        .bind(id)
        .execute(pool)
        .await
        .map(|_| ()))
        .map_err(|_| StorageError::query(history))?;
        sqlx::query("DELETE FROM desktop_workspace_grants WHERE scope = ? AND workspace_id = ?")
            .bind(scope)
            .bind(id)
            .execute(&self.local().await?)
            .await
            .map_err(|_| StorageError::local())?;
        Ok(())
    }
}

#[tauri::command]
pub async fn load_workbench(
    storage: State<'_, Persistence>,
    client: State<'_, JavaClient>,
    manager: State<'_, SandboxManager>,
) -> Result<Workbench, StorageError> {
    let scope = storage.scope(&client).await?;
    let history = storage.history().await?;
    let mut sessions = storage.read(&history, &scope).await?;
    let grants: std::collections::HashMap<String, String> =
        sqlx::query_as("SELECT workspace_id, path FROM desktop_workspace_grants WHERE scope = ?")
            .bind(&scope)
            .fetch_all(&storage.local().await?)
            .await
            .map_err(|_| StorageError::local())?
            .into_iter()
            .collect();
    let standalone_path = restore_workspace(&manager, "standalone", grants.get("standalone")).await;
    for snapshot in &mut sessions {
        let id = &snapshot.session.id;
        snapshot.session.workspace_path = restore_workspace(&manager, id, grants.get(id)).await;
    }
    let backend = match history {
        History::Sqlite(_) => "sqlite",
        History::MySql(_) => "mysql",
    };
    Ok(Workbench {
        scope,
        backend,
        sessions,
        standalone_path,
    })
}

async fn restore_workspace(
    manager: &SandboxManager,
    id: &str,
    path: Option<&String>,
) -> Option<String> {
    let path = path?;
    let workspace = manager.workspace(id).await.ok()?;
    if let Some(existing) = manager.workspace_directory(id).await {
        return Some(existing);
    }
    let _lease = workspace.lease().ok()?;
    // Keep the locally granted binding even if the directory is temporarily unavailable.
    // Starting a container revalidates the canonical path before mounting.
    workspace.bind_directory(path.clone()).await.ok()
}

#[tauri::command]
pub async fn save_workbench_session(
    scope: String,
    mut snapshot: Snapshot,
    storage: State<'_, Persistence>,
    client: State<'_, JavaClient>,
) -> Result<(), StorageError> {
    check_scope(&storage, &client, &scope, &snapshot.session.id).await?;
    snapshot.session.workspace_path = storage
        .workspace_grant(&scope, &snapshot.session.id)
        .await?;
    storage
        .save(&storage.history().await?, &scope, snapshot)
        .await
}

#[tauri::command]
pub async fn delete_workbench_session(
    scope: String,
    session_id: String,
    storage: State<'_, Persistence>,
    client: State<'_, JavaClient>,
) -> Result<(), StorageError> {
    check_scope(&storage, &client, &scope, &session_id).await?;
    storage
        .delete(&storage.history().await?, &scope, &session_id)
        .await
}

async fn check_scope(
    storage: &Persistence,
    client: &JavaClient,
    scope: &str,
    id: &str,
) -> Result<(), StorageError> {
    if uuid::Uuid::parse_str(id).is_err() || storage.scope(client).await? != scope {
        return Err(StorageError::new(
            "storage_scope",
            "当前访问配置已变更，请重新加载会话。",
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn create_private_dir(path: &std::path::Path) -> std::io::Result<()> {
    use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
    std::fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(path)?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))
}

#[cfg(not(unix))]
fn create_private_dir(path: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(path)
}

#[cfg(windows)]
fn protect(mut bytes: Vec<u8>, seal: bool) -> Result<Vec<u8>, StorageError> {
    use std::ptr::{null, null_mut};
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
        },
    };
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_mut_ptr(),
    };
    let mut out = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: null_mut(),
    };
    // DPAPI copies the input and allocates the output for the current Windows user.
    let copy = unsafe {
        let ok = if seal {
            CryptProtectData(
                &input,
                null(),
                null(),
                null(),
                null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out,
            )
        } else {
            CryptUnprotectData(
                &input,
                null_mut(),
                null(),
                null(),
                null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out,
            )
        };
        if ok == 0 || out.pbData.is_null() {
            return Err(StorageError::new(
                "storage_credentials",
                "Windows 无法保护或读取本机访问凭据，请在配置中心重新保存。",
            ));
        }
        let copy = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        LocalFree(out.pbData.cast());
        copy
    };
    Ok(copy)
}

#[cfg(not(windows))]
fn protect(bytes: Vec<u8>, _seal: bool) -> Result<Vec<u8>, StorageError> {
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn exercise_persistence(backend: &str) -> Result<(), StorageError> {
        let directory =
            std::env::temp_dir().join(format!("tian-desktop-db-{}", uuid::Uuid::new_v4()));
        let storage = Persistence::new(Some(directory.clone()));
        let client = JavaClient::default();
        let connection = client
            .prepare_access(
                serde_json::from_value(serde_json::json!({
                    "port": 5532, "token": "fixture-persistence-token"
                }))
                .map_err(|_| StorageError::local())?,
            )
            .await
            .map_err(|_| StorageError::local())?;
        storage.save_access(&connection).await?;
        client.set_connection(connection).await;
        let scope = storage.scope(&client).await?;
        let history = storage.history().await?;
        assert!(matches!(
            (&history, backend),
            (History::Sqlite(_), "sqlite") | (History::MySql(_), "mysql")
        ));
        let id = uuid::Uuid::new_v4().to_string();
        let folder =
            crate::sandbox::workspace_path(directory.clone()).map_err(|_| StorageError::local())?;
        storage.grant(&scope, &id, &folder).await?;
        storage.grant(&scope, &id, &folder).await?;
        assert_eq!(
            storage
                .grant(&scope, &id, "different-directory")
                .await
                .err()
                .map(|err| err.code),
            Some("workspace_locked")
        );
        assert_eq!(
            storage.workspace_grant(&scope, &id).await?.as_deref(),
            Some(folder.as_str())
        );
        let peer = uuid::Uuid::new_v4().to_string();
        storage.grant(&scope, &peer, &folder).await?;
        assert_eq!(
            storage.workspace_grant(&scope, &peer).await?.as_deref(),
            Some(folder.as_str())
        );
        assert!(
            storage
                .workspace_grant("other-account", &id)
                .await?
                .is_none()
        );
        assert_eq!(
            restore_workspace(
                &SandboxManager::default(),
                &peer,
                Some(&"unavailable-directory".to_string())
            )
            .await
            .as_deref(),
            Some("unavailable-directory")
        );
        let snapshot = |text: &str| Snapshot {
            session: Session {
                id: id.clone(),
                title: "会话重启测试".into(),
                space_id: "workspace-knowledge".into(),
                workspace_path: Some(folder.clone()),
                knowledge_only: Some(false),
                model_provider_id: Some("fixture-model".into()),
                shared_chat_id: Some("agent_fixture".into()),
            },
            messages: vec![
                serde_json::json!({ "role": "assistant", "content": text, "outcome": "completed" }),
            ],
        };
        storage.save(&history, &scope, snapshot("首次保存")).await?;
        storage.save(&history, &scope, snapshot("二次保存")).await?;
        match history {
            History::Sqlite(pool) => pool.close().await,
            History::MySql(pool) => pool.close().await,
        }
        storage.local().await?.close().await;
        drop(storage);

        let reopened = Persistence::new(Some(directory.clone()));
        let client = JavaClient::default();
        let restored = reopened
            .restore_access()
            .await?
            .ok_or_else(StorageError::local)?;
        client.set_connection(restored).await;
        assert_eq!(reopened.scope(&client).await?, scope);
        let history = reopened.history().await?;
        let snapshots = reopened.read(&history, &scope).await?;
        assert_eq!(snapshots.len(), 1);
        assert_eq!(snapshots[0].messages[0]["content"], "二次保存");
        let path: Option<String> = sqlx::query_scalar(
            "SELECT path FROM desktop_workspace_grants WHERE scope = ? AND workspace_id = ?",
        )
        .bind(&scope)
        .bind(&id)
        .fetch_optional(&reopened.local().await?)
        .await
        .map_err(|_| StorageError::local())?;
        let manager = SandboxManager::default();
        assert_eq!(
            restore_workspace(&manager, &id, path.as_ref())
                .await
                .as_deref(),
            Some(folder.as_str())
        );
        assert!(reopened.read(&history, &"f".repeat(64)).await?.is_empty());
        reopened.delete(&history, &scope, &id).await?;
        assert!(reopened.read(&history, &scope).await?.is_empty());
        match history {
            History::Sqlite(pool) => pool.close().await,
            History::MySql(pool) => pool.close().await,
        }
        reopened.local().await?.close().await;
        for name in [
            "workbench.sqlite3-wal",
            "workbench.sqlite3-shm",
            "workbench.sqlite3",
        ] {
            let _ = std::fs::remove_file(directory.join(name));
        }
        let _ = std::fs::remove_dir(directory);
        Ok(())
    }

    #[test]
    #[ignore = "run against the selected database using TIAN_DESKTOP_MYSQL_URL when needed"]
    fn configured_persistence_reopens_and_restores_data() -> Result<(), StorageError> {
        let backend =
            if std::env::var("TIAN_DESKTOP_MYSQL_URL").is_ok_and(|url| !url.trim().is_empty()) {
                "mysql"
            } else {
                "sqlite"
            };
        tauri::async_runtime::block_on(exercise_persistence(backend))
    }
}
