use crate::{
    audit::Audit,
    java_client::JavaClient,
    persistence::{Persistence, StorageError},
    sandbox::{
        self, DockerStatus, ExecResult, FileEntry, SandboxError, SandboxInfo, SandboxManager,
    },
};
use std::{future::Future, time::Instant};
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;

#[tauri::command]
pub async fn check_docker(audit: State<'_, Audit>) -> Result<DockerStatus, SandboxError> {
    let start = Instant::now();
    let status = sandbox::check_docker().await;
    audit.record(
        "sandbox",
        "docker_health",
        if status.available {
            "ok"
        } else {
            "docker_unavailable"
        },
        start,
        None,
    );
    Ok(status)
}

#[tauri::command]
pub async fn select_workspace(
    app: AppHandle,
    workspace_id: String,
    from_workspace_id: Option<String>,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
    storage: State<'_, Persistence>,
    client: State<'_, JavaClient>,
) -> Result<Option<String>, SandboxError> {
    logged(&audit, "select_workspace", async {
        let scope = storage.scope(&client).await.map_err(storage_failure)?;
        let workspace = manager.workspace(&workspace_id).await?;
        let _lease = workspace.lease()?;
        if workspace_id != "standalone"
            && storage
                .workspace_grant(&scope, &workspace_id)
                .await
                .map_err(storage_failure)?
                .is_some()
        {
            return Err(SandboxError::invalid_workspace(
                "会话已绑定工作区，请新建会话来选择其他目录。",
            ));
        }
        let selected = if let Some(source) = from_workspace_id {
            let path = storage
                .workspace_grant(&scope, &source)
                .await
                .map_err(storage_failure)?
                .ok_or_else(|| SandboxError::invalid_workspace("此工作区没有可沿用的本机授权。"))?;
            if sandbox::workspace_path(std::path::PathBuf::from(&path))
                .ok()
                .as_ref()
                != Some(&path)
            {
                return Err(SandboxError::invalid_workspace(
                    "已绑定的工作区目录暂不可用。",
                ));
            }
            path
        } else {
            let Some(folder) = app
                .dialog()
                .file()
                .set_title("选择工作区目录")
                .blocking_pick_folder()
            else {
                return Ok(None);
            };
            sandbox::workspace_path(
                folder
                    .into_path()
                    .map_err(|_| SandboxError::invalid_workspace("请选择本地文件夹。"))?,
            )?
        };
        if storage.scope(&client).await.map_err(storage_failure)? != scope {
            return Err(SandboxError::invalid_workspace(
                "访问配置已变更，请重新选择工作区。",
            ));
        }
        if workspace.current().await.is_some() {
            return Err(SandboxError::invalid_workspace("请先停止当前会话的沙箱。"));
        }
        storage
            .grant(&scope, &workspace_id, &selected)
            .await
            .map_err(storage_failure)?;
        workspace.bind_directory(selected).await.map(Some)
    })
    .await
}

#[tauri::command]
pub async fn get_active_sandbox(
    workspace_id: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<Option<SandboxInfo>, SandboxError> {
    logged(&audit, "get_sandbox", async {
        Ok(manager.workspace(&workspace_id).await?.current().await)
    })
    .await
}

#[tauri::command]
pub async fn create_sandbox(
    workspace_id: String,
    workspace_path: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<SandboxInfo, SandboxError> {
    logged(&audit, "create_sandbox", async {
        manager
            .workspace(&workspace_id)
            .await?
            .create(workspace_path)
            .await
    })
    .await
}

#[tauri::command]
pub async fn stop_sandbox(
    workspace_id: String,
    sandbox_id: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<(), SandboxError> {
    logged(&audit, "stop_sandbox", async {
        manager
            .workspace(&workspace_id)
            .await?
            .stop(&sandbox_id)
            .await
    })
    .await
}

#[tauri::command]
pub async fn release_workspace(
    workspace_id: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<(), SandboxError> {
    logged(&audit, "release_workspace", manager.release(&workspace_id)).await
}

#[tauri::command]
pub async fn exec_in_sandbox(
    workspace_id: String,
    sandbox_id: String,
    cmd: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<ExecResult, SandboxError> {
    let start = Instant::now();
    let result = async {
        let workspace = manager.workspace(&workspace_id).await?;
        let _lease = workspace.lease()?;
        workspace.exec(&sandbox_id, cmd).await
    }
    .await;
    let outcome = match &result {
        Ok(output) if output.exit_code != 0 => "nonzero_exit",
        Ok(_) => "ok",
        Err(error) => error.code(),
    };
    audit.record("sandbox", "exec", outcome, start, None);
    result
}

#[tauri::command]
pub async fn list_sandbox_files(
    workspace_id: String,
    sandbox_id: String,
    path: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<Vec<FileEntry>, SandboxError> {
    logged(&audit, "list", async {
        manager
            .workspace(&workspace_id)
            .await?
            .list(&sandbox_id, &path)
            .await
    })
    .await
}

fn storage_failure(error: StorageError) -> SandboxError {
    SandboxError::invalid_workspace(error.message)
}

async fn logged<T>(
    audit: &Audit,
    operation: &str,
    run: impl Future<Output = Result<T, SandboxError>>,
) -> Result<T, SandboxError> {
    let start = Instant::now();
    let result = run.await;
    audit.record(
        "sandbox",
        operation,
        result.as_ref().map_or_else(|error| error.code(), |_| "ok"),
        start,
        None,
    );
    result
}
