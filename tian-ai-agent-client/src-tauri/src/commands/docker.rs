use crate::{
    audit::Audit,
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
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<Option<String>, SandboxError> {
    logged(&audit, "select_workspace", async {
        let Some(selected) = app
            .dialog()
            .file()
            .set_title("选择工作区目录")
            .blocking_pick_folder()
        else {
            return Ok(None);
        };
        let path = selected
            .into_path()
            .map_err(|_| SandboxError::invalid_workspace("请选择本地文件夹。"))?;
        manager.authorize(path).await.map(Some)
    })
    .await
}

#[tauri::command]
pub async fn get_active_sandbox(
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<Option<SandboxInfo>, SandboxError> {
    logged(&audit, "get_sandbox", async { Ok(manager.current().await) }).await
}

#[tauri::command]
pub async fn create_sandbox(
    workspace_path: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<SandboxInfo, SandboxError> {
    logged(&audit, "create_sandbox", manager.create(workspace_path)).await
}

#[tauri::command]
pub async fn stop_sandbox(
    sandbox_id: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<(), SandboxError> {
    logged(&audit, "stop_sandbox", manager.stop(&sandbox_id)).await
}

#[tauri::command]
pub async fn exec_in_sandbox(
    sandbox_id: String,
    cmd: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<ExecResult, SandboxError> {
    let start = Instant::now();
    let result = manager.exec(&sandbox_id, cmd).await;
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
    sandbox_id: String,
    path: String,
    manager: State<'_, SandboxManager>,
    audit: State<'_, Audit>,
) -> Result<Vec<FileEntry>, SandboxError> {
    logged(&audit, "list", manager.list(&sandbox_id, &path)).await
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
