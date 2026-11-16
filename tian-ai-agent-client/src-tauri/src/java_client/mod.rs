mod operations;

use crate::{audit::Audit, config::JavaConfig};
use reqwest::{Client, Method, RequestBuilder, Url, redirect::Policy};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    fmt,
    time::{Duration, Instant},
};
use tauri::async_runtime::Mutex;

pub use operations::JavaCall;

const MAX_BODY: usize = 12 * 1024 * 1024;
const MAX_SSE_FRAME: usize = 1024 * 1024;

#[derive(Default)]
pub struct JavaClient {
    override_config: Mutex<Option<Connection>>,
}

#[derive(Clone)]
struct Connection {
    port: u16,
    token: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Access {
    port: u16,
    has_token: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccessUpdate {
    port: u16,
    #[serde(default)]
    token: String,
    #[serde(default)]
    clear: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "snake_case")]
pub enum HealthStatus {
    Available,
    Unavailable,
    Timeout,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthReport {
    status: HealthStatus,
    endpoint: Option<String>,
    detail: String,
    http_status: Option<u16>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaError {
    pub code: &'static str,
    pub message: String,
    pub http_status: Option<u16>,
}

impl JavaError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            http_status: None,
        }
    }

    fn transport(error: reqwest::Error) -> Self {
        if error.is_timeout() {
            Self::new("java_timeout", "Java 服务请求超时，请稍后重试。")
        } else {
            Self::new(
                "java_unavailable",
                "无法连接 Java 服务。可以继续使用 Docker 工作区。",
            )
        }
    }

    fn http(status: u16, details: &[u8]) -> Self {
        let (code, help) = match status {
            401 => (
                "java_unauthorized",
                "Java 服务拒绝访问。请在配置中心填写有效的访问令牌。",
            ),
            403 => ("java_forbidden", "当前账号没有此操作的权限。"),
            404 => ("java_not_found", "服务端找不到该资源或未启用对应功能。"),
            409 => ("java_conflict", "服务端数据已变更，请刷新或检查是否重复。"),
            429 => ("java_rate_limit", "Java 服务或模型提供商限制了请求频率。"),
            _ => ("java_http_error", "Java 服务请求失败。"),
        };
        let detail = serde_json::from_slice::<Value>(details)
            .ok()
            .and_then(|value| {
                value
                    .get("message")
                    .or_else(|| value.get("detail"))
                    .and_then(Value::as_str)
                    .filter(|text| !text.is_empty())
                    .map(|text| text.chars().take(400).collect::<String>())
            });
        let message = if matches!(status, 401 | 403 | 404) {
            help.into()
        } else {
            detail.unwrap_or_else(|| format!("{help} HTTP {status}"))
        };
        Self {
            code,
            message,
            http_status: Some(status),
        }
    }
}

impl fmt::Display for JavaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for JavaError {}

impl Connection {
    fn access(&self) -> Access {
        Access {
            port: self.port,
            has_token: !self.token.is_empty(),
        }
    }

    fn url(&self, segments: &[&str]) -> Result<Url, JavaError> {
        let mut url = Url::parse(&format!("http://127.0.0.1:{}/api/", self.port))
            .map_err(|_| JavaError::new("java_config", "Java 服务端口无效。"))?;
        url.path_segments_mut()
            .map_err(|_| JavaError::new("java_config", "Java 服务地址无效。"))?
            .pop_if_empty()
            .extend(segments.iter().copied());
        Ok(url)
    }

    fn request(&self, method: Method, segments: &[&str]) -> Result<RequestBuilder, JavaError> {
        let builder = http_client(Duration::from_secs(125))?.request(method, self.url(segments)?);
        if self.token.is_empty() {
            Ok(builder)
        } else {
            Ok(builder.bearer_auth(&self.token))
        }
    }
}

impl JavaClient {
    async fn connection(&self) -> Result<Connection, JavaError> {
        if let Some(connection) = self.override_config.lock().await.clone() {
            return Ok(connection);
        }
        let config = JavaConfig::from_env()
            .map_err(|error| JavaError::new("java_config", error.to_string()))?;
        Ok(Connection {
            port: config.port(),
            token: std::env::var("TIAN_JAVA_TOKEN")
                .unwrap_or_default()
                .trim()
                .into(),
        })
    }

    pub async fn access(&self) -> Access {
        match self.connection().await {
            Ok(config) => config.access(),
            Err(_) => Access {
                port: 8080,
                has_token: false,
            },
        }
    }

    pub async fn configure(&self, input: AccessUpdate) -> Result<Access, JavaError> {
        if input.port == 0
            || input.token.len() > 4096
            || !input
                .token
                .trim()
                .bytes()
                .all(|value| value.is_ascii_graphic())
        {
            return Err(JavaError::new("java_config", "请输入有效端口和访问令牌。"));
        }
        let previous = self.connection().await.ok();
        let token = if input.clear {
            String::new()
        } else if !input.token.trim().is_empty() {
            input.token.trim().into()
        } else {
            previous
                .filter(|value| value.port == input.port)
                .map_or_else(String::new, |value| value.token)
        };
        let connection = Connection {
            port: input.port,
            token,
        };
        let access = connection.access();
        *self.override_config.lock().await = Some(connection);
        Ok(access)
    }

    pub async fn check(&self, audit: &Audit) -> HealthReport {
        match self.connection().await {
            Ok(connection) => Self::probe(&connection, audit).await,
            Err(error) => {
                audit.record("java", "health", error.code, Instant::now(), None);
                HealthReport {
                    status: HealthStatus::Unavailable,
                    endpoint: None,
                    detail: error.message,
                    http_status: None,
                }
            }
        }
    }

    async fn probe(connection: &Connection, audit: &Audit) -> HealthReport {
        let start = Instant::now();
        let endpoint = format!("http://127.0.0.1:{}/api/health", connection.port);
        let check = async {
            let response = http_client(JavaConfig::HEALTH_TIMEOUT)?
                .get(&endpoint)
                .send()
                .await
                .map_err(JavaError::transport)?;
            let status = response.status().as_u16();
            if status != 200 {
                return Err(JavaError::http(status, &[]));
            }
            let bytes = body(response, 4096).await?;
            if String::from_utf8_lossy(&bytes).trim() == "ok" {
                Ok(status)
            } else {
                Err(JavaError::new(
                    "java_response",
                    "健康检查没有返回预期的 ok。请核对 Java 端口。",
                ))
            }
        }
        .await;
        log(audit, "health", start, &check);
        match check {
            Ok(status) => HealthReport {
                status: HealthStatus::Available,
                endpoint: Some(endpoint),
                detail: "Java 服务已响应。".into(),
                http_status: Some(status),
            },
            Err(error) => HealthReport {
                status: if error.code == "java_timeout" {
                    HealthStatus::Timeout
                } else {
                    HealthStatus::Unavailable
                },
                endpoint: Some(endpoint),
                detail: error.message,
                http_status: error.http_status,
            },
        }
    }

    async fn ready(&self, audit: &Audit) -> Result<Connection, JavaError> {
        let connection = self.connection().await?;
        let state = Self::probe(&connection, audit).await;
        if !matches!(state.status, HealthStatus::Available) {
            let code = if matches!(state.status, HealthStatus::Timeout) {
                "java_timeout"
            } else {
                "java_unavailable"
            };
            return Err(JavaError {
                code,
                message: format!("{} 沙箱操作仍可使用。", state.detail),
                http_status: state.http_status,
            });
        }
        Ok(connection)
    }

    pub async fn call(&self, audit: &Audit, request: JavaCall) -> Result<Value, JavaError> {
        let start = Instant::now();
        let name = request.operation.name();
        let result = async {
            let connection = self.ready(audit).await?;
            let response = success(
                request
                    .build(&connection)?
                    .send()
                    .await
                    .map_err(JavaError::transport)?,
            )
            .await?;
            let status = response.status().as_u16();
            let bytes = body(response, MAX_BODY).await?;
            let json = if bytes.is_empty() {
                Value::Null
            } else {
                serde_json::from_slice(&bytes).map_err(|_| {
                    JavaError::new("java_response", "Java 服务返回了无法读取的数据。")
                })?
            };
            Ok((json, status))
        }
        .await;
        log(audit, name, start, &result);
        result.map(|(value, _)| value)
    }

    pub async fn stream_agent(
        &self,
        audit: &Audit,
        payload: &impl Serialize,
        mut emit: impl FnMut(&str, Value) -> Result<(), JavaError>,
    ) -> Result<(), JavaError> {
        let connection = self.ready(audit).await?;
        let response = connection
            .request(Method::POST, &["ai", "dispatch", "stream"])?
            .header("Accept", "text/event-stream")
            .json(payload)
            .send()
            .await
            .map_err(JavaError::transport)?;
        let mut response = success(response).await?;
        let mut data = Vec::new();
        let mut offset = 0;
        let mut event = String::new();
        let mut lines = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(JavaError::transport)? {
            data.extend_from_slice(&chunk);
            while let Some(newline) = data[offset..].iter().position(|byte| *byte == b'\n') {
                let line = &data[offset..offset + newline];
                let line = line.strip_suffix(b"\r").unwrap_or(line);
                offset += newline + 1;
                if line.is_empty() {
                    if !lines.is_empty() {
                        let value = serde_json::from_str(&lines.join("\n")).map_err(|_| {
                            JavaError::new("java_response", "Java 流事件格式无效。")
                        })?;
                        emit(&event, value)?;
                        if event == "done" {
                            return Ok(());
                        }
                    }
                    event.clear();
                    lines.clear();
                } else if let Some(value) = line.strip_prefix(b"event:") {
                    event = String::from_utf8_lossy(value).trim().into();
                } else if let Some(value) = line.strip_prefix(b"data:") {
                    lines.push(
                        String::from_utf8_lossy(value.strip_prefix(b" ").unwrap_or(value))
                            .into_owned(),
                    );
                }
            }
            data.drain(..offset);
            offset = 0;
            if data.len() + lines.iter().map(String::len).sum::<usize>() > MAX_SSE_FRAME {
                return Err(JavaError::new("java_response", "Java 流事件超过大小限制。"));
            }
        }
        Err(JavaError::new("java_response", "Java 会话连接提前结束。"))
    }
}

fn http_client(timeout: Duration) -> Result<Client, JavaError> {
    Client::builder()
        .timeout(timeout)
        .connect_timeout(JavaConfig::HEALTH_TIMEOUT)
        .no_proxy()
        .redirect(Policy::none())
        .build()
        .map_err(|_| JavaError::new("java_config", "无法初始化本地 Java HTTP 客户端。"))
}

async fn success(response: reqwest::Response) -> Result<reqwest::Response, JavaError> {
    let status = response.status();
    if status.is_success() {
        return Ok(response);
    }
    let details = body(response, 32 * 1024).await.unwrap_or_default();
    Err(JavaError::http(status.as_u16(), &details))
}

async fn body(mut response: reqwest::Response, max: usize) -> Result<Vec<u8>, JavaError> {
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(JavaError::transport)? {
        if bytes.len() + chunk.len() > max {
            return Err(JavaError::new("java_response", "Java 响应超过允许的大小。"));
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

trait HttpOutcome {
    fn status(&self) -> u16;
}
impl HttpOutcome for u16 {
    fn status(&self) -> u16 {
        *self
    }
}
impl HttpOutcome for (Value, u16) {
    fn status(&self) -> u16 {
        self.1
    }
}
fn log<T: HttpOutcome>(audit: &Audit, name: &str, start: Instant, result: &Result<T, JavaError>) {
    match result {
        Ok(value) => audit.record("java", name, "ok", start, Some(value.status())),
        Err(error) => audit.record("java", name, error.code, start, error.http_status),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use operations::Operation;
    use std::{
        io::{self, BufRead, BufReader, Read, Write},
        net::TcpListener,
        thread,
    };

    #[test]
    fn java_recovers_and_streams_using_the_same_configuration()
    -> Result<(), Box<dyn std::error::Error>> {
        let reserved = TcpListener::bind(("127.0.0.1", 0))?;
        let port = reserved.local_addr()?.port();
        drop(reserved);
        let client = JavaClient::default();
        let audit = Audit::default();

        tauri::async_runtime::block_on(async {
            client
                .configure(AccessUpdate {
                    port,
                    token: "desktop-fixture-token".into(),
                    clear: false,
                })
                .await?;
            let offline = client.check(&audit).await;
            assert!(matches!(offline.status, HealthStatus::Unavailable));
            Ok::<(), JavaError>(())
        })?;

        let listener = TcpListener::bind(("127.0.0.1", port))?;
        let server = thread::spawn(move || -> io::Result<()> {
            for target in [
                "/api/health",
                "/api/knowledge-base/spaces",
                "/api/health",
                "/api/ai/dispatch/stream",
            ] {
                let (mut stream, _) = listener.accept()?;
                stream.set_read_timeout(Some(Duration::from_secs(3)))?;
                let mut reader = BufReader::new(stream.try_clone()?);
                let mut first = String::new();
                reader.read_line(&mut first)?;
                assert!(
                    first.split_whitespace().nth(1) == Some(target),
                    "unexpected route: {first}"
                );
                let mut authenticated = false;
                let mut length = 0;
                loop {
                    let mut line = String::new();
                    if reader.read_line(&mut line)? == 0 || line == "\r\n" {
                        break;
                    }
                    let line = line.to_ascii_lowercase();
                    authenticated |=
                        line.trim_end() == "authorization: bearer desktop-fixture-token";
                    if let Some(value) = line.strip_prefix("content-length:") {
                        length = value.trim().parse().unwrap_or_default();
                    }
                }
                assert_eq!(authenticated, target != "/api/health");
                let mut payload = vec![0; length];
                reader.read_exact(&mut payload)?;
                let (content_type, response) = match target {
                    "/api/health" => ("text/plain", "ok"),
                    "/api/knowledge-base/spaces" => (
                        "application/json",
                        "[{\"id\":\"medical\",\"name\":\"测试空间\"}]",
                    ),
                    _ => {
                        let body: Value = serde_json::from_slice(&payload)?;
                        assert_eq!(body["localWorkspace"], false);
                        assert!(body.get("sandboxId").is_none());
                        (
                            "text/event-stream",
                            "event:delta\r\ndata:{\"text\":\"回复\"}\r\n\r\nevent:done\r\ndata:{}\r\n\r\n",
                        )
                    }
                };
                write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    response.len()
                )?;
                let middle = response.len() / 2;
                stream.write_all(&response.as_bytes()[..middle])?;
                stream.flush()?;
                stream.write_all(&response.as_bytes()[middle..])?;
            }
            Ok(())
        });
        let result = tauri::async_runtime::block_on(async {
            let request = JavaCall {
                operation: Operation::Spaces,
                id: None,
                space_id: None,
                body: None,
                upload: None,
            };
            let spaces = client.call(&audit, request).await?;
            assert_eq!(spaces[0]["id"], "medical");
            let mut events = Vec::new();
            client
                .stream_agent(
                    &audit,
                    &serde_json::json!({ "localWorkspace": false }),
                    |kind, payload| {
                        events.push((kind.to_string(), payload));
                        Ok(())
                    },
                )
                .await?;
            assert_eq!(events[0].1["text"], "回复");
            assert_eq!(events.last().map(|event| event.0.as_str()), Some("done"));
            Ok::<(), JavaError>(())
        });
        result?;
        server
            .join()
            .map_err(|_| io::Error::other("fixture server panicked"))??;
        Ok(())
    }
}
