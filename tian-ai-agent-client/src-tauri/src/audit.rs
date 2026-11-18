use serde::Serialize;
use std::{
    fs::{self, OpenOptions},
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Instant, SystemTime, UNIX_EPOCH},
};

const MAX_LOG_BYTES: u64 = 1024 * 1024;
const MAX_RECENT: usize = 100;

#[derive(Default)]
pub struct Audit {
    path: Option<PathBuf>,
    gate: Mutex<()>,
}

#[derive(Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    timestamp_ms: u64,
    layer: String,
    operation: String,
    outcome: String,
    elapsed_ms: u64,
    http_status: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    trace_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    workspace: Option<WorkspaceAudit>,
}

#[derive(Clone, Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct WorkspaceAudit {
    pub request_id: String,
    pub chat_id: String,
    pub path: Option<String>,
    pub write: bool,
    pub commands: bool,
    pub container_id: Option<String>,
}

impl Audit {
    pub fn new(directory: PathBuf) -> Self {
        Self {
            path: Some(directory.join("desktop-operations.jsonl")),
            gate: Mutex::new(()),
        }
    }

    pub fn record(
        &self,
        layer: &str,
        operation: &str,
        outcome: &str,
        start: Instant,
        http_status: Option<u16>,
    ) {
        self.record_traced(layer, operation, outcome, start, http_status, None);
    }

    pub fn record_traced(
        &self,
        layer: &str,
        operation: &str,
        outcome: &str,
        start: Instant,
        http_status: Option<u16>,
        trace_id: Option<&str>,
    ) {
        self.save(
            layer,
            operation,
            outcome,
            start,
            http_status,
            trace_id,
            None,
        );
    }

    pub(crate) fn record_workspace(
        &self,
        operation: &str,
        outcome: &str,
        start: Instant,
        trace_id: Option<&str>,
        workspace: &WorkspaceAudit,
    ) {
        self.save(
            "sandbox",
            operation,
            outcome,
            start,
            None,
            trace_id,
            Some(workspace.clone()),
        );
    }

    #[allow(clippy::too_many_arguments)]
    fn save(
        &self,
        layer: &str,
        operation: &str,
        outcome: &str,
        start: Instant,
        http_status: Option<u16>,
        trace_id: Option<&str>,
        workspace: Option<WorkspaceAudit>,
    ) {
        let entry = Entry {
            timestamp_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |time| millis(time.as_millis())),
            layer: layer.into(),
            operation: operation.into(),
            outcome: outcome.into(),
            elapsed_ms: millis(start.elapsed().as_millis()),
            http_status,
            trace_id: trace_id.map(str::to_owned),
            workspace,
        };
        if let Ok(line) = serde_json::to_string(&entry) {
            let Some(path) = self.path.as_deref() else {
                eprintln!("{line}");
                return;
            };
            if let Ok(_guard) = self.gate.lock() {
                if let Err(error) = write(path, &line) {
                    eprintln!("Desktop audit could not be saved: {error}");
                    eprintln!("{line}");
                }
            }
        }
    }

    pub fn recent(&self) -> Vec<Entry> {
        let Ok(_guard) = self.gate.lock() else {
            return Vec::new();
        };
        let Some(path) = self.path.as_deref() else {
            return Vec::new();
        };
        let mut recent = std::collections::VecDeque::new();
        for candidate in [path.with_extension("jsonl.1"), path.to_path_buf()] {
            let Ok(file) = fs::File::open(candidate) else {
                continue;
            };
            for line in BufReader::new(file).lines().map_while(Result::ok) {
                if let Ok(entry) = serde_json::from_str(&line) {
                    if recent.len() == MAX_RECENT {
                        recent.pop_front();
                    }
                    recent.push_back(entry);
                }
            }
        }
        recent.into_iter().rev().collect()
    }
}

fn write(path: &Path, line: &str) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    if fs::metadata(path).is_ok_and(|meta| meta.len() >= MAX_LOG_BYTES) {
        let previous = path.with_extension("jsonl.1");
        match fs::remove_file(&previous) {
            Ok(()) => (),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
            Err(error) => return Err(error),
        }
        fs::rename(path, previous)?;
    }
    let mut file = OpenOptions::new().create(true).append(true).open(path)?;
    writeln!(file, "{line}")
}

fn millis(value: u128) -> u64 {
    u64::try_from(value).unwrap_or(u64::MAX)
}
