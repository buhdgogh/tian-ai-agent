use super::{ErrorCode, SandboxError, Workspace};
use serde::Deserialize;
use serde_json::{Value, json};
use std::time::Instant;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct WorkspaceCall {
    pub run_id: String,
    pub request: Request,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Request {
    pub request_id: String,
    action: Action,
    role: Role,
    path: String,
    text: Option<String>,
    replacement: Option<String>,
}

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum Role {
    Inspect,
    Edit,
    Test,
    Review,
}

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum Action {
    Prepare,
    List,
    Read,
    Write,
    Replace,
    Test,
}

impl WorkspaceCall {
    pub fn valid_ids(&self) -> bool {
        self.run_id.len() == 32
            && self.run_id.bytes().all(|byte| byte.is_ascii_hexdigit())
            && self.request.request_id.len() == 36
            && self
                .request
                .request_id
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() || byte == b'-')
    }

    pub fn is_prepare(&self) -> bool {
        matches!(
            (self.request.role, self.request.action),
            (Role::Inspect, Action::Prepare)
        ) && self.request.path.is_empty()
    }

    pub fn operation(&self) -> &'static str {
        match self.request.action {
            Action::Prepare => "workspace_prepare",
            Action::List => "workspace_list",
            Action::Read => "workspace_read",
            Action::Write => "workspace_write",
            Action::Replace => "workspace_replace",
            Action::Test => "workspace_test",
        }
    }

    pub fn receipt(&self, reply: &Value) -> Value {
        let excerpt = |field: &str| {
            reply[field]
                .as_str()
                .filter(|text| !text.is_empty())
                .map(|text| bounded(text, 500).0)
        };
        let test = matches!(self.request.action, Action::Test);
        json!({
            "operation": self.operation(),
            "path": self.request.path,
            "ok": reply["ok"].as_bool().unwrap_or(false),
            "exitCode": reply["exitCode"].as_i64(),
            "stdout": if test { excerpt("stdout") } else { None },
            "stderr": if test { excerpt("stderr") } else { None },
            "error": excerpt("message"),
        })
    }

    pub async fn execute(
        &self,
        manager: &Workspace,
        id: &str,
        audit: &crate::audit::Audit,
    ) -> Value {
        let start = Instant::now();
        audit.record_traced(
            "sandbox",
            self.operation(),
            "started",
            start,
            None,
            Some(&self.run_id),
        );
        let result = self.request.execute(manager, id).await;
        let outcome = match &result {
            Ok(payload) if payload["ok"] == true => "ok",
            Ok(_) => "nonzero_exit",
            Err(error) => error.code(),
        };
        audit.record_traced(
            "sandbox",
            self.operation(),
            outcome,
            start,
            None,
            Some(&self.run_id),
        );
        result.unwrap_or_else(|error| {
            json!({
                "ok": false,
                "errorCode": error.code().to_ascii_uppercase(),
                "message": error.to_string(),
            })
        })
    }
}

impl Request {
    fn allowed(&self) -> bool {
        matches!(
            (self.role, self.action),
            (_, Action::List | Action::Read)
                | (Role::Edit, Action::Write | Action::Replace)
                | (Role::Test, Action::Test)
        )
    }

    async fn execute(&self, manager: &Workspace, id: &str) -> Result<Value, SandboxError> {
        if !self.allowed() {
            return Err(SandboxError::new(
                ErrorCode::InvalidCommand,
                "当前角色无权执行此操作。",
            ));
        }
        let text = self.text.as_deref().unwrap_or_default();
        match self.action {
            Action::Prepare => Err(SandboxError::new(
                ErrorCode::InvalidCommand,
                "无效的工作区工具。",
            )),
            Action::List => {
                let entries = manager.list(id, &self.path).await?;
                let clipped = entries.len() > 30;
                Ok(
                    json!({"ok": true, "entries": entries.into_iter().take(30).collect::<Vec<_>>(), "truncated": clipped}),
                )
            }
            Action::Read => {
                let result = manager.read_file(id, &self.path).await?;
                let (text, clipped) = bounded(&result.stdout, 12_000);
                Ok(json!({"ok": true, "text": text, "truncated": clipped || result.truncated}))
            }
            Action::Write => {
                manager.create_file(id, &self.path, text).await?;
                Ok(json!({"ok": true}))
            }
            Action::Replace => {
                let replacement = self.replacement.as_deref().ok_or_else(|| {
                    SandboxError::new(ErrorCode::InvalidCommand, "缺少替换文本。")
                })?;
                manager
                    .replace_file(id, &self.path, text, replacement)
                    .await?;
                Ok(json!({"ok": true}))
            }
            Action::Test => {
                let result = manager.exec_test(id, text).await?;
                let (stdout, stdout_clipped) = bounded(&result.stdout, 6_000);
                let (stderr, stderr_clipped) = bounded(&result.stderr, 6_000);
                Ok(json!({
                    "ok": result.exit_code == 0, "exitCode": result.exit_code,
                    "stdout": stdout, "stderr": stderr,
                    "truncated": result.truncated || stdout_clipped || stderr_clipped
                }))
            }
        }
    }
}

fn bounded(value: &str, limit: usize) -> (String, bool) {
    let clipped = value.len() > limit;
    let mut end = value.len().min(limit);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    let text = value[..end]
        .chars()
        .filter(|character| !character.is_control() || matches!(character, '\r' | '\n' | '\t'))
        .collect();
    (text, clipped)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn review_and_web_equivalent_roles_are_read_only() {
        for role in [Role::Review, Role::Inspect] {
            let request = Request {
                request_id: String::new(),
                action: Action::Write,
                role,
                path: "main.py".into(),
                text: None,
                replacement: None,
            };
            assert!(!request.allowed());
            let test = Request {
                action: Action::Test,
                ..request
            };
            assert!(!test.allowed());
        }
    }

    #[test]
    fn clipping_preserves_utf8() {
        assert_eq!(bounded("文档\0", 5), ("文".into(), true));
    }
}
