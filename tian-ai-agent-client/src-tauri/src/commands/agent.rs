use crate::{
    audit::Audit,
    java_client::{JavaClient, JavaError},
    sandbox::{SandboxError, SandboxManager},
};
use futures_util::future::{AbortHandle, Abortable};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{collections::HashMap, time::Instant};
use tauri::{State, async_runtime::Mutex, ipc::Channel};

#[derive(Default)]
pub struct PendingAgent(Mutex<HashMap<String, AbortHandle>>);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentRequest {
    request_id: String,
    chat_id: String,
    message: String,
    space_id: Option<String>,
    model_provider_id: Option<String>,
    #[serde(default)]
    skill_ids: Vec<String>,
    sandbox_id: Option<String>,
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
    request: AgentRequest,
    events: Channel<AgentEvent>,
    manager: State<'_, SandboxManager>,
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
    if let Some(operation) = local {
        let name = operation.name();
        let mut status = "ok";
        let result = async {
            let id = request.sandbox_id.as_deref().ok_or_else(|| {
                AgentError::Java(JavaError::new(
                    "workspace_required",
                    "请先在工作区页面选择目录并启动沙箱。",
                ))
            })?;
            emit("route", json!({ "strategy": "SANDBOX" })).map_err(AgentError::Java)?;
            emit(
                "thinking",
                json!({ "step": 1, "name": "在 Docker 工作区执行" }),
            )
            .map_err(AgentError::Java)?;
            let (text, outcome) = execute_local(&manager, id, operation)
                .await
                .map_err(AgentError::Sandbox)?;
            status = outcome;
            emit("delta", json!({ "text": text })).map_err(AgentError::Java)?;
            emit("done", json!({})).map_err(AgentError::Java)
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

    if request.request_id.is_empty()
        || request.request_id.len() > 128
        || request.message.trim().is_empty()
    {
        return Err(AgentError::Java(JavaError::new(
            "agent_request",
            "请输入有效的会话消息。",
        )));
    }
    let (abort, registration) = AbortHandle::new_pair();
    {
        let mut requests = pending.0.lock().await;
        if requests.contains_key(&request.request_id) {
            return Err(AgentError::Java(JavaError::new(
                "agent_request",
                "该会话请求已在运行。",
            )));
        }
        requests.insert(request.request_id.clone(), abort);
    }

    let payload = json!({
        "chatId": request.chat_id,
        "message": request.message,
        "spaceId": request.space_id.filter(|space| !space.is_empty()),
        "modelProviderId": request.model_provider_id.filter(|model| !model.is_empty()),
        "skillIds": request.skill_ids

    });

    let response = Abortable::new(
        client.stream_agent(&audit, &payload, |event, data| match event {
            "error" => Err(JavaError::new(
                "java_agent_failed",
                data.get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("Java Agent 执行失败。"),
            )),
            "run" | "route" | "task" | "thinking" | "tools" | "delta" | "done" => emit(event, data),
            _ => Ok(()),
        }),
        registration,
    )
    .await;
    pending.0.lock().await.remove(&request.request_id);
    let result = match response {
        Ok(result) => result,
        Err(_) => Err(JavaError::new("agent_cancelled", "已停止生成。")),
    };
    audit.record(
        "java",
        "agent_dispatch",
        result.as_ref().map_or_else(|error| error.code, |_| "ok"),
        start,
        result
            .as_ref()
            .ok()
            .map(|_| 200)
            .or_else(|| result.as_ref().err().and_then(|error| error.http_status)),
    );
    result.map_err(AgentError::Java)
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
    manager: &SandboxManager,
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
