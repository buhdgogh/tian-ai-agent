use super::{Connection, JavaError};
use reqwest::{
    Method, RequestBuilder,
    multipart::{Form, Part},
};
use serde::Deserialize;
use serde_json::Value;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaCall {
    pub(super) operation: Operation,
    pub(super) id: Option<String>,
    pub(super) space_id: Option<String>,
    pub(super) body: Option<Value>,
    pub(super) upload: Option<Upload>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Upload {
    filename: String,
    bytes: Vec<u8>,
    metadata: Option<DocumentMetadata>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DocumentMetadata {
    issuer: String,
    version: String,
    published_at: String,
    audience: String,
    document_type: String,
}

macro_rules! operations {
    ($($variant:ident => $name:literal),+ $(,)?) => {
        #[derive(Deserialize, Copy, Clone)]
        #[serde(rename_all = "camelCase")]
        pub(super) enum Operation { $($variant),+ }
        impl Operation {
            pub(super) fn name(self) -> &'static str { match self { $( Self::$variant => $name ),+ } }
        }
    };
}

operations! {
    Spaces => "spaces", CreateSpace => "create_space", DeleteSpace => "delete_space",
    Files => "files", DeleteFile => "delete_file", UploadFile => "upload_file",
    Query => "knowledge_query", Retrieve => "knowledge_retrieve", WikiGraph => "wiki_graph",
    WikiPage => "wiki_page", CompileWiki => "wiki_compile", RagEvaluation => "rag_evaluation",
    Tasks => "tasks", Task => "task", CancelTask => "cancel_task", Workflow => "workflow",
    Runs => "runs", Run => "run", ToolAudits => "tool_audits", Models => "models", SaveModels => "save_models",
    Skills => "skills", Prompts => "prompts", SaveSkill => "save_skill", SavePrompt => "save_prompt",
    DeleteSkill => "delete_skill", DeletePrompt => "delete_prompt", McpServers => "mcp_servers",
    DiscoverMcp => "discover_mcp", SaveMcp => "save_mcp", DeleteMcp => "delete_mcp",
    ToolCredentials => "tool_credentials", SaveMineru => "save_mineru", SaveSearch => "save_search",
    ClearMineru => "clear_mineru", ClearSearch => "clear_search",
}

impl JavaCall {
    pub(super) fn build(self, connection: &Connection) -> Result<RequestBuilder, JavaError> {
        use Operation::*;
        let id = self.id.as_deref();
        if id.is_some_and(|id| {
            id.is_empty()
                || id == "."
                || id == ".."
                || id.len() > 160
                || !id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
        }) {
            return Err(invalid());
        }
        let required_id = || id.ok_or_else(invalid);
        let (method, segments): (Method, Vec<&str>) = match self.operation {
            Spaces => (Method::GET, vec!["knowledge-base", "spaces"]),
            CreateSpace => (Method::POST, vec!["knowledge-base", "spaces"]),
            DeleteSpace => (
                Method::DELETE,
                vec!["knowledge-base", "spaces", required_id()?],
            ),
            Files => (Method::GET, vec!["knowledge-base", "files"]),
            DeleteFile => (
                Method::DELETE,
                vec!["knowledge-base", "files", required_id()?],
            ),
            UploadFile => (Method::POST, vec!["knowledge-base", "upload"]),
            Query => (Method::POST, vec!["knowledge-base", "query-with-sources"]),
            Retrieve => (Method::POST, vec!["knowledge-base", "retrieve"]),
            WikiGraph => (Method::GET, vec!["knowledge-base", "wiki-graph"]),
            WikiPage => (
                Method::GET,
                vec!["knowledge-base", "wiki-pages", required_id()?],
            ),
            CompileWiki => (Method::POST, vec!["knowledge-base", "wiki-compile"]),
            RagEvaluation => (Method::POST, vec!["rag-evaluations"]),
            Tasks => (Method::GET, vec!["tasks"]),
            Task => (Method::GET, vec!["tasks", required_id()?]),
            CancelTask => (Method::DELETE, vec!["tasks", required_id()?]),
            Workflow => (Method::POST, vec!["ai", "workflows"]),
            Runs => (Method::GET, vec!["agent-runs"]),
            Run => (Method::GET, vec!["agent-runs", required_id()?]),
            ToolAudits => (Method::GET, vec!["tool-gateway", "audits"]),
            Models => (Method::GET, vec!["model-providers"]),
            SaveModels => (Method::PUT, vec!["model-providers"]),
            Skills => (Method::GET, vec!["skills"]),
            Prompts => (Method::GET, vec!["prompt-templates"]),
            SaveSkill => upsert("skills", id),
            SavePrompt => upsert("prompt-templates", id),
            DeleteSkill => (Method::DELETE, vec!["skills", required_id()?]),
            DeletePrompt => (Method::DELETE, vec!["prompt-templates", required_id()?]),
            McpServers => (Method::GET, vec!["mcp-servers"]),
            DiscoverMcp => (
                Method::POST,
                match id {
                    Some(id) => vec!["mcp-servers", id, "discover"],
                    None => vec!["mcp-servers", "discover"],
                },
            ),
            SaveMcp => upsert("mcp-servers", id),
            DeleteMcp => (Method::DELETE, vec!["mcp-servers", required_id()?]),
            ToolCredentials => (Method::GET, vec!["tool-settings"]),
            SaveMineru => (Method::PUT, vec!["tool-settings", "mineru"]),
            SaveSearch => (Method::PUT, vec!["tool-settings", "search"]),
            ClearMineru => (Method::DELETE, vec!["tool-settings", "mineru"]),
            ClearSearch => (Method::DELETE, vec!["tool-settings", "search"]),
        };
        let mut request = connection.request(method, &segments)?;
        if matches!(self.operation, UploadFile) {
            let Upload {
                filename,
                bytes,
                metadata,
            } = self.upload.ok_or_else(invalid)?;
            if bytes.len() > 12 * 1024 * 1024
                || filename.trim().is_empty()
                || filename.contains(['/', '\\', '\0'])
            {
                return Err(JavaError::new(
                    "java_request",
                    "请选择不超过 12 MiB 的本地文件。",
                ));
            }
            let mut form = Form::new()
                .text("spaceId", self.space_id.ok_or_else(invalid)?)
                .part("file", Part::bytes(bytes).file_name(filename));
            if let Some(meta) = metadata {
                form = form
                    .text("issuer", meta.issuer)
                    .text("version", meta.version)
                    .text("publishedAt", meta.published_at)
                    .text("audience", meta.audience)
                    .text("documentType", meta.document_type);
            }
            return Ok(request.multipart(form));
        }
        if let Some(space) = self.space_id {
            request = request.query(&[("spaceId", space)]);
        }
        if let Some(payload) = self.body {
            request = request.json(&payload);
        }
        Ok(request)
    }
}

fn invalid() -> JavaError {
    JavaError::new("java_request", "Java 接口参数不完整或无效。")
}
fn upsert<'a>(collection: &'a str, id: Option<&'a str>) -> (Method, Vec<&'a str>) {
    match id {
        Some(value) => (Method::PUT, vec![collection, value]),
        None => (Method::POST, vec![collection]),
    }
}
