use crate::{
    audit::{Audit, WorkspaceAudit},
    java_client::{JavaClient, JavaError},
    persistence::Persistence,
    sandbox::{SandboxError, SandboxManager, Workspace, WorkspaceAccess, workspace_path},
};
use futures_util::future::{AbortHandle, Abortable};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{collections::HashMap, time::Instant};
use tauri::{AppHandle, Manager, State, async_runtime::Mutex, ipc::Channel};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons};

#[derive(Default)]
pub struct PendingAgent(Mutex<HashMap<String, AbortHandle>>);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentRequest {
    request_id: String,
    chat_id: String,
    shared_chat_id: Option<String>,
    message: String,
    space_id: Option<String>,
    model_provider_id: Option<String>,
    #[serde(default)]
    skill_ids: Vec<String>,
    #[serde(default)]
    knowledge_only: bool,
    target_workspace: Option<TargetWorkspace>,
    #[serde(default)]
    recent_requests: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TargetWorkspace {
    workspace_id: Option<String>,
    access: WorkspaceAccess,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentEvent {
    event: String,
    data: Value,
}

#[derive(Serialize)]
#[serde(untagged)]
pub enum AgentError {
    Sandbox(SandboxError),
    Java(JavaError),
}

pub(crate) enum Local<'a> {
    Exec(&'a str),
    List(&'a str),
    Read(&'a str),
    Write(&'a str, &'a str),
}

impl Local<'_> {
    fn name(&self) -> &'static str {
        match self {
            Self::Exec(_) => "exec",
            Self::List(_) => "list",
            Self::Read(_) => "read",
            Self::Write(_, _) => "write",
        }
    }
}

#[tauri::command]
pub async fn run_agent(
    app: AppHandle,
    request: AgentRequest,
    events: Channel<AgentEvent>,
    manager: State<'_, SandboxManager>,
    storage: State<'_, Persistence>,
    client: State<'_, JavaClient>,
    pending: State<'_, PendingAgent>,
    audit: State<'_, Audit>,
) -> Result<(), AgentError> {
    let start = Instant::now();
    let emit = |event: &str, data: Value| {
        events
            .send(AgentEvent {
                event: event.into(),
                data,
            })
            .map_err(|_| JavaError::new("agent_disconnected", "会话窗口已关闭。"))
    };

    let local = local_request(&request.message).map_err(AgentError::Java)?;
    if let Some(target) = &request.target_workspace {
        validate_request(&request).map_err(AgentError::Java)?;
        let (abort, registration) = pending
            .register(&request.request_id)
            .await
            .map_err(AgentError::Java)?;
        let mut scope = WorkspaceAudit {
            request_id: request.request_id.clone(),
            chat_id: request.chat_id.clone(),
            path: None,
            write: target.access.write,
            commands: target.access.commands,
            container_id: None,
        };
        let mut trace = None;
        let mut done = None;
        let result = async {
            let directory =
                approve_target(&app, &manager, &request.chat_id, target, &mut scope).await;
            let outcome = directory
                .as_ref()
                .map_or_else(|error| error.code, |_| "approved");
            audit.record_workspace("run_workspace_authorize", outcome, start, None, &scope);
            let directory = directory.map_err(AgentError::Java)?;
            if abort.is_aborted() {
                return Err(AgentError::Java(cancelled()));
            }
            let workspace = manager
                .open_run_workspace(&request.request_id, directory, target.access)
                .await
                .map_err(AgentError::Sandbox)?;
            emit("thinking", json!({ "name": "启动本轮目标工作区沙箱" }))
                .map_err(AgentError::Java)?;
            // Let Docker finish startup before cleanup, even if the user cancels while it starts.
            let sandbox = workspace
                .start_for_agent()
                .await
                .map_err(AgentError::Sandbox)?;
            scope.container_id = Some(sandbox.id.clone());
            audit.record_workspace("run_workspace_start", "ok", start, None, &scope);
            emit("target_workspace_ready", json!(scope)).map_err(AgentError::Java)?;
            if abort.is_aborted() {
                return Err(AgentError::Java(cancelled()));
            }
            if let Some(operation) = local {
                let _lease = workspace.lease().map_err(AgentError::Sandbox)?;
                let (text, outcome) = execute_local(&workspace, &sandbox.id, operation)
                    .await
                    .map_err(AgentError::Sandbox)?;
                emit("route", json!({ "strategy": "SANDBOX" })).map_err(AgentError::Java)?;
                emit("delta", json!({ "text": text })).map_err(AgentError::Java)?;
                done =
                    Some(json!({ "status": if outcome == "ok" { "COMPLETED" } else { "FAILED" } }));
                return Ok(());
            }
            let payload = agent_payload(&request, true);
            match Abortable::new(
                client.stream_agent(&audit, &payload, Some(workspace), |event, data| {
                    match event {
                        "run" => {
                            trace = data.get("runId").and_then(Value::as_str).map(str::to_owned);
                            audit.record_workspace(
                                "run_workspace_bind_trace",
                                "ok",
                                start,
                                trace.as_deref(),
                                &scope,
                            );
                        }
                        "done" => {
                            done = Some(data);
                            return Ok(());
                        }
                        "workspace_ready" => return Ok(()),
                        _ => {}
                    }
                    forward_agent_event(event, data, &emit)
                }),
                registration,
            )
            .await
            {
                Ok(result) => result.map_err(AgentError::Java),
                Err(_) => Err(AgentError::Java(cancelled())),
            }
        }
        .await;
        let cleanup = manager.close_run_workspace(&request.request_id).await;
        pending.0.lock().await.remove(&request.request_id);
        let outcome = result.as_ref().map_or_else(
            |error| error.code(),
            |_| {
                if done
                    .as_ref()
                    .and_then(|data| data.get("status"))
                    .and_then(Value::as_str)
                    == Some("FAILED")
                {
                    "failed"
                } else {
                    "ok"
                }
            },
        );
        audit.record_workspace(
            "run_workspace_finish",
            outcome,
            start,
            trace.as_deref(),
            &scope,
        );
        if let Err(error) = cleanup {
            audit.record_workspace(
                "run_workspace_cleanup",
                error.code(),
                start,
                trace.as_deref(),
                &scope,
            );
            let message = "本轮沙箱自动清理失败，关闭应用时会再次尝试清理。";
            let _ = emit("workspace_cleanup_failed", json!({ "message": message }));
        } else if scope.container_id.is_some() {
            audit.record_workspace(
                "run_workspace_cleanup",
                "ok",
                start,
                trace.as_deref(),
                &scope,
            );
        }
        result?;
        if let Some(data) = done {
            emit("done", data).map_err(AgentError::Java)?;
        }
        return Ok(());
    }
    if let Some(operation) = local {
        let name = operation.name();
        let mut status = "ok";
        let result = async {
            if request.knowledge_only {
                return Err(AgentError::Java(JavaError::new(
                    "workspace_required",
                    "请在 Agent 问答中执行文件操作。",
                )));
            }
            emit("route", json!({ "strategy": "SANDBOX" })).map_err(AgentError::Java)?;
            emit(
                "thinking",
                json!({ "step": 1, "name": "在 Docker 工作区执行" }),
            )
            .map_err(AgentError::Java)?;
            let workspace =
                workspace_for_chat(&app, &manager, &storage, &client, &request.chat_id).await?;
            let sandbox = workspace
                .start_for_agent()
                .await
                .map_err(AgentError::Sandbox)?;
            emit("workspace_ready", json!(sandbox)).map_err(AgentError::Java)?;
            let _lease = workspace.lease().map_err(AgentError::Sandbox)?;
            let (text, outcome) = execute_local(&workspace, &sandbox.id, operation)
                .await
                .map_err(AgentError::Sandbox)?;
            status = outcome;
            emit("delta", json!({ "text": text })).map_err(AgentError::Java)?;
            let status = if outcome == "ok" {
                "COMPLETED"
            } else {
                "FAILED"
            };
            emit("done", json!({ "status": status })).map_err(AgentError::Java)
        }
        .await;
        let outcome = match &result {
            Ok(()) => status,
            Err(AgentError::Sandbox(error)) => error.code(),
            Err(AgentError::Java(error)) => error.code,
        };
        audit.record("sandbox", name, outcome, start, None);
        return result;
    }

    validate_request(&request).map_err(AgentError::Java)?;
    let workspace = if request.knowledge_only {
        None
    } else {
        Some(workspace_for_chat(&app, &manager, &storage, &client, &request.chat_id).await?)
    };
    let mut trace = None::<String>;
    let (_, registration) = pending
        .register(&request.request_id)
        .await
        .map_err(AgentError::Java)?;
    let payload = agent_payload(&request, false);

    let response = Abortable::new(
        client.stream_agent(&audit, &payload, workspace.clone(), |event, data| {
            if event == "run" {
                trace = data.get("runId").and_then(Value::as_str).map(str::to_owned);
            }
            forward_agent_event(event, data, &emit)
        }),
        registration,
    )
    .await;
    pending.0.lock().await.remove(&request.request_id);
    let result = match response {
        Ok(result) => result,
        Err(_) => Err(cancelled()),
    };
    audit.record_traced(
        "java",
        if workspace.is_some() {
            "desktop_workspace"
        } else {
            "agent_dispatch"
        },
        result.as_ref().map_or_else(|error| error.code, |_| "ok"),
        start,
        result
            .as_ref()
            .ok()
            .map(|_| 200)
            .or_else(|| result.as_ref().err().and_then(|error| error.http_status)),
        trace.as_deref(),
    );
    result.map_err(AgentError::Java)
}

async fn workspace_for_chat(
    app: &AppHandle,
    manager: &SandboxManager,
    storage: &Persistence,
    client: &JavaClient,
    id: &str,
) -> Result<std::sync::Arc<Workspace>, AgentError> {
    if let Some(workspace) = manager.selected_workspace(id).await {
        return Ok(workspace);
    }
    let directory = app.path().app_data_dir().map_err(|_| {
        AgentError::Sandbox(SandboxError::invalid_workspace(
            "无法定位应用默认文件目录。",
        ))
    })?;
    let account = storage
        .scope(client)
        .await
        .map_err(|error| AgentError::Java(JavaError::new(error.code, error.message)))?;
    manager
        .agent_workspace(id, directory, account)
        .await
        .map_err(AgentError::Sandbox)
}

impl AgentError {
    fn code(&self) -> &'static str {
        match self {
            Self::Sandbox(error) => error.code(),
            Self::Java(error) => error.code,
        }
    }
}

impl PendingAgent {
    async fn register(
        &self,
        request_id: &str,
    ) -> Result<(AbortHandle, futures_util::future::AbortRegistration), JavaError> {
        let mut requests = self.0.lock().await;
        if requests.contains_key(request_id) {
            return Err(JavaError::new("agent_request", "该会话请求已在运行。"));
        }
        let (abort, registration) = AbortHandle::new_pair();
        requests.insert(request_id.into(), abort.clone());
        Ok((abort, registration))
    }
}

fn validate_request(request: &AgentRequest) -> Result<(), JavaError> {
    if request.request_id.is_empty()
        || request.request_id.len() > 128
        || request.message.trim().is_empty()
    {
        return Err(JavaError::new("agent_request", "请输入有效的会话消息。"));
    }
    Ok(())
}

fn cancelled() -> JavaError {
    JavaError::new("agent_cancelled", "已停止生成。")
}

fn agent_payload(request: &AgentRequest, target: bool) -> Value {
    let message = if target {
        format!(
            "本轮使用单独授权的目标工作区。容器 /workspace 指向目标目录，请使用相对路径完成本轮文件操作或测试。\n{}",
            request.message
        )
    } else {
        request.message.clone()
    };
    let recent = if target {
        Vec::new()
    } else {
        request
            .recent_requests
            .iter()
            .take(3)
            .map(|text| text.chars().take(700).collect::<String>())
            .collect::<Vec<_>>()
    };
    json!({
        "chatId": if target { &request.request_id } else { request.shared_chat_id.as_ref().unwrap_or(&request.chat_id) },
        "message": message,
        "spaceId": request.space_id.as_deref().filter(|space| !space.is_empty()),
        "modelProviderId": request.model_provider_id.as_deref().filter(|model| !model.is_empty()),
        "skillIds": request.skill_ids,
        "recentRequests": recent,
    })
}

fn forward_agent_event(
    event: &str,
    data: Value,
    emit: &impl Fn(&str, Value) -> Result<(), JavaError>,
) -> Result<(), JavaError> {
    match event {
        "error" => Err(JavaError::new(
            "java_agent_failed",
            data.get("message")
                .and_then(Value::as_str)
                .unwrap_or("Java Agent 执行失败。"),
        )),
        "run" | "route" | "task" | "thinking" | "tools" | "delta" | "done" | "workspace_ready"
        | "workspace_receipt" => emit(event, data),
        _ => Ok(()),
    }
}

async fn approve_target(
    app: &AppHandle,
    manager: &SandboxManager,
    chat_id: &str,
    target: &TargetWorkspace,
    scope: &mut WorkspaceAudit,
) -> Result<String, JavaError> {
    let denied = || {
        JavaError::new(
            "target_workspace_declined",
            "未授权目标工作区，本轮任务未执行。",
        )
    };
    let Some(default) = manager.workspace_directory(chat_id).await else {
        return Err(JavaError::new(
            "workspace_required",
            "请先为当前会话选择默认工作区。",
        ));
    };
    let directory = if let Some(id) = &target.workspace_id {
        manager
            .workspace_directory(id)
            .await
            .map(std::path::PathBuf::from)
            .ok_or_else(|| {
                JavaError::new(
                    "target_workspace_missing",
                    "所选目标已不可用，请重新选择目录。",
                )
            })?
    } else {
        app.dialog()
            .file()
            .set_title("选择本轮执行的目标工作区")
            .blocking_pick_folder()
            .ok_or_else(denied)?
            .into_path()
            .map_err(|_| JavaError::new("target_workspace_missing", "请选择本机目录。"))?
    };
    let directory = workspace_path(directory)
        .map_err(|error| JavaError::new(error.code(), error.to_string()))?;
    if directory == default || (cfg!(windows) && directory.eq_ignore_ascii_case(&default)) {
        return Err(JavaError::new(
            "target_workspace_same",
            "目标与默认工作区相同，请另选目录。",
        ));
    }
    scope.path = Some(directory.clone());
    let mode = if target.access.write {
        "读写，修改会同步到本机"
    } else {
        "只读"
    };
    let commands = if target.access.commands {
        "允许"
    } else {
        "禁止"
    };
    let approved = app.dialog().message(format!(
        "目标目录\n{directory}\n\n文件权限：{mode}\n运行命令：{commands}\n\n此授权仅用于本轮请求。"
    )).title("授权 Agent 使用目标工作区").buttons(MessageDialogButtons::OkCancelCustom("授权本轮".into(), "取消".into())).blocking_show();
    if !approved {
        return Err(denied());
    }
    Ok(directory)
}

#[tauri::command]
pub async fn cancel_agent(
    request_id: String,
    pending: State<'_, PendingAgent>,
) -> Result<(), JavaError> {
    if let Some(handle) = pending.0.lock().await.get(&request_id) {
        handle.abort();
    }
    Ok(())
}

pub(crate) fn local_request(message: &str) -> Result<Option<Local<'_>>, JavaError> {
    let text = message.trim_start();
    for (name, prefix) in [
        ("exec", "/exec"),
        ("read", "/read"),
        ("list", "/ls"),
        ("write", "/write"),
        ("exec", "执行命令："),
        ("read", "读取文件："),
        ("list", "列出目录："),
        ("write", "写入文件："),
    ] {
        let Some(rest) = text.strip_prefix(prefix) else {
            continue;
        };
        if prefix.starts_with('/') && !rest.is_empty() && !rest.starts_with(char::is_whitespace) {
            continue;
        }
        let value = rest.trim_start_matches([' ', '\t', '\r', '\n']);
        let operation = match name {
            "exec" if !value.trim().is_empty() => Local::Exec(value),
            "read" if !value.trim().is_empty() => Local::Read(value.trim()),
            "list" => Local::List(value.trim()),
            "write" => match value.split_once('\n') {
                Some((path, content)) if !path.trim().is_empty() => {
                    Local::Write(path.trim(), content)
                }
                _ => {
                    return Err(JavaError::new(
                        "agent_request",
                        "用法：/write 文件路径，换行后输入要写入的正文。",
                    ));
                }
            },
            _ => {
                return Err(JavaError::new(
                    "agent_request",
                    "本地操作缺少命令或文件路径。",
                ));
            }
        };
        return Ok(Some(operation));
    }
    Ok(None)
}

pub(crate) async fn execute_local(
    manager: &Workspace,
    id: &str,
    operation: Local<'_>,
) -> Result<(String, &'static str), SandboxError> {
    match operation {
        Local::Exec(command) => {
            let result = manager.exec(id, command.to_string()).await?;
            let text = format!(
                "退出码 {}\n\n标准输出\n{}\n\n标准错误\n{}{}",
                result.exit_code,
                if result.stdout.is_empty() {
                    "（无）"
                } else {
                    &result.stdout
                },
                if result.stderr.is_empty() {
                    "（无）"
                } else {
                    &result.stderr
                },
                if result.truncated {
                    "\n\n部分输出已截断。"
                } else {
                    ""
                }
            );
            Ok((
                text,
                if result.exit_code == 0 {
                    "ok"
                } else {
                    "nonzero_exit"
                },
            ))
        }
        Local::List(path) => {
            let entries = manager.list(id, path).await?;
            let text = if entries.is_empty() {
                "目录为空。".into()
            } else {
                entries
                    .iter()
                    .map(|entry| entry.display())
                    .collect::<Vec<_>>()
                    .join("\n")
            };
            Ok((text, "ok"))
        }
        Local::Read(path) => {
            let result = manager.read_file(id, path).await?;
            let text = if result.truncated {
                format!("{}\n\n文件只显示前 64 KiB。", result.stdout)
            } else if result.stdout.is_empty() {
                "文件为空。".into()
            } else {
                result.stdout
            };
            Ok((text, "ok"))
        }
        Local::Write(path, content) => {
            manager.write_file(id, path, content).await?;
            Ok((format!("已写入：{path}"), "ok"))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_legacy_chat_id_is_used_only_for_java_conversation() -> Result<(), serde_json::Error> {
        let request: AgentRequest = serde_json::from_value(json!({
            "requestId": "run-id", "chatId": "local-sandbox-id", "sharedChatId": "agent_web-legacy",
            "spaceId": "northstar", "message": "继续原来的任务"
        }))?;
        let shared = agent_payload(&request, false);
        assert_eq!(shared["chatId"], "agent_web-legacy");
        assert_eq!(shared["spaceId"], "northstar");
        assert_eq!(agent_payload(&request, true)["chatId"], "run-id");
        Ok(())
    }

    #[test]
    fn only_explicit_actions_run_locally() -> Result<(), JavaError> {
        assert!(matches!(local_request("执行下列两个任务")?, None));
        assert!(matches!(local_request("/exec-summary")?, None));
        assert!(matches!(
            local_request("执行命令：pwd")?,
            Some(Local::Exec("pwd"))
        ));
        assert!(matches!(local_request("/ls")?, Some(Local::List(""))));
        assert!(matches!(
            local_request("/write nested/a.txt\n  内容\n")?,
            Some(Local::Write("nested/a.txt", "  内容\n"))
        ));
        assert!(local_request("/write a.txt").is_err());
        Ok(())
    }
}
