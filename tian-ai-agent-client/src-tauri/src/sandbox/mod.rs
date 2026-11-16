use bollard::{
    API_DEFAULT_VERSION, Docker,
    container::LogOutput,
    errors::Error as DockerError,
    exec::StartExecResults,
    models::{ContainerCreateBody, ExecConfig, HostConfig, Mount, MountType},
    query_parameters::{
        CreateImageOptionsBuilder, RemoveContainerOptionsBuilder, StopContainerOptionsBuilder,
    },
};
use futures_util::StreamExt;
use serde::Serialize;
use std::{collections::HashMap, fmt, path::PathBuf};
use tauri::async_runtime::Mutex;

const IMAGE: &str = "alpine:3.22";
const ROOT: &str = "/workspace";
const OUTPUT_LIMIT: usize = 64 * 1024;
const LIST_LIMIT: usize = 200;

#[derive(Default)]
pub struct SandboxManager {
    selected: Mutex<Option<String>>,
    active: Mutex<Option<ActiveSandbox>>,
}

struct ActiveSandbox {
    docker: Docker,
    info: SandboxInfo,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct SandboxInfo {
    id: String,
    workspace_path: String,
    image: &'static str,
    status: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DockerStatus {
    pub available: bool,
    detail: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecResult {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i64,
    pub truncated: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    name: String,
    path: String,
    kind: EntryKind,
}

#[derive(Serialize)]
#[serde(rename_all = "snake_case")]
enum EntryKind {
    Directory,
    File,
    Symlink,
    Other,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "snake_case")]
enum ErrorCode {
    DockerUnavailable,
    UnsupportedEngine,
    InvalidWorkspace,
    AlreadyRunning,
    InvalidSandbox,
    InvalidCommand,
    InvalidPath,
    Image,
    Container,
    Execution,
}

#[derive(Debug, Serialize)]
pub struct SandboxError {
    code: ErrorCode,
    message: String,
}

impl SandboxError {
    fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    fn docker(code: ErrorCode, action: &str, error: DockerError) -> Self {
        Self::new(code, format!("{action}：{error}"))
    }

    pub fn code(&self) -> &'static str {
        match self.code {
            ErrorCode::DockerUnavailable => "docker_unavailable",
            ErrorCode::UnsupportedEngine => "unsupported_engine",
            ErrorCode::InvalidWorkspace => "invalid_workspace",
            ErrorCode::AlreadyRunning => "already_running",
            ErrorCode::InvalidSandbox => "invalid_sandbox",
            ErrorCode::InvalidCommand => "invalid_command",
            ErrorCode::InvalidPath => "invalid_path",
            ErrorCode::Image => "image_error",
            ErrorCode::Container => "container_error",
            ErrorCode::Execution => "execution_error",
        }
    }

    pub fn invalid_workspace(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::InvalidWorkspace, message)
    }
}

impl fmt::Display for SandboxError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for SandboxError {}

pub async fn check_docker() -> DockerStatus {
    match connect().await {
        Ok(_) => DockerStatus {
            available: true,
            detail: "Docker Linux 引擎可用。".into(),
        },
        Err(error) => DockerStatus {
            available: false,
            detail: error.message,
        },
    }
}

async fn connect() -> Result<Docker, SandboxError> {
    #[cfg(windows)]
    let candidates = [
        Docker::connect_with_named_pipe(
            "npipe:////./pipe/dockerDesktopLinuxEngine",
            20,
            API_DEFAULT_VERSION,
        ),
        Docker::connect_with_named_pipe("npipe:////./pipe/docker_engine", 20, API_DEFAULT_VERSION),
    ];
    #[cfg(unix)]
    let candidates = [Docker::connect_with_socket(
        "/var/run/docker.sock",
        20,
        API_DEFAULT_VERSION,
    )];

    for candidate in candidates.into_iter().flatten() {
        if let Ok(version) = candidate.version().await {
            if version.os.as_deref() == Some("linux") {
                return Ok(candidate);
            }
            return Err(SandboxError::new(
                ErrorCode::UnsupportedEngine,
                "请将 Docker Desktop 切换为 Linux 容器模式。",
            ));
        }
    }
    Err(SandboxError::new(
        ErrorCode::DockerUnavailable,
        "无法连接 Docker。请启动 Docker Desktop（Linux 容器）或本机 Docker Engine，确认当前账号有权限后重试。",
    ))
}

impl SandboxManager {
    pub async fn authorize(&self, path: PathBuf) -> Result<String, SandboxError> {
        let selected = workspace_path(path)?;
        *self.selected.lock().await = Some(selected.clone());
        Ok(selected)
    }

    pub async fn create(&self, path: String) -> Result<SandboxInfo, SandboxError> {
        let mut slot = self.active.lock().await;
        if slot.is_some() {
            return Err(SandboxError::new(
                ErrorCode::AlreadyRunning,
                "已有沙箱，请先停止。",
            ));
        }
        if self.selected.lock().await.as_deref() != Some(path.as_str())
            || workspace_path(PathBuf::from(&path))? != path
        {
            return Err(SandboxError::invalid_workspace(
                "请通过目录选择器重新选择工作区。",
            ));
        }

        let user = sandbox_user()?;
        let docker = connect().await?;
        ensure_image(&docker).await?;
        let body = ContainerCreateBody {
            image: Some(IMAGE.into()),
            user,
            cmd: Some(vec!["tail".into(), "-f".into(), "/dev/null".into()]),
            working_dir: Some(ROOT.into()),
            labels: Some(HashMap::from([(
                "com.tianaiagent.sandbox".into(),
                "true".into(),
            )])),
            host_config: Some(HostConfig {
                mounts: Some(vec![Mount {
                    typ: Some(MountType::BIND),
                    source: Some(path.clone()),
                    target: Some(ROOT.into()),
                    read_only: Some(false),
                    ..Default::default()
                }]),
                network_mode: Some("none".into()),
                cap_drop: Some(vec!["ALL".into()]),
                security_opt: Some(vec!["no-new-privileges:true".into()]),
                readonly_rootfs: Some(true),
                tmpfs: Some(HashMap::from([(
                    "/tmp".into(),
                    "rw,noexec,nosuid,nodev,size=16m".into(),
                )])),
                pids_limit: Some(64),
                memory: Some(256 * 1024 * 1024),
                nano_cpus: Some(1_000_000_000),
                init: Some(true),
                ..Default::default()
            }),
            ..Default::default()
        };
        let created = docker
            .create_container(None, body)
            .await
            .map_err(|error| SandboxError::docker(ErrorCode::Container, "创建容器失败", error))?;
        let id = created.id;
        if let Err(error) = docker.start_container(&id, None).await {
            let failure = SandboxError::docker(ErrorCode::Container, "启动容器失败", error);
            if let Err(cleanup) = remove(&docker, &id).await {
                *slot = Some(ActiveSandbox {
                    docker,
                    info: SandboxInfo {
                        id,
                        workspace_path: path,
                        image: IMAGE,
                        status: "cleanup_required",
                    },
                });
                return Err(SandboxError::new(
                    ErrorCode::Container,
                    format!("{failure}。清理失败：{cleanup}"),
                ));
            }
            return Err(failure);
        }
        let info = SandboxInfo {
            id,
            workspace_path: path,
            image: IMAGE,
            status: "running",
        };
        *slot = Some(ActiveSandbox {
            docker,
            info: info.clone(),
        });
        Ok(info)
    }

    pub async fn current(&self) -> Option<SandboxInfo> {
        self.active
            .lock()
            .await
            .as_ref()
            .map(|sandbox| sandbox.info.clone())
    }

    pub async fn stop(&self, id: &str) -> Result<(), SandboxError> {
        let mut slot = self.active.lock().await;
        let sandbox = active(&slot, id)?;
        remove(&sandbox.docker, id).await?;
        *slot = None;
        Ok(())
    }

    pub async fn shutdown(&self) -> Result<bool, SandboxError> {
        if let Some(info) = self.current().await {
            self.stop(&info.id).await?;
            return Ok(true);
        }
        Ok(false)
    }
    pub async fn exec(&self, id: &str, command: String) -> Result<ExecResult, SandboxError> {
        if command.trim().is_empty() || command.len() > 16_384 || command.contains('\0') {
            return Err(SandboxError::new(
                ErrorCode::InvalidCommand,
                "请输入有效命令，最多 16 KiB。",
            ));
        }
        let slot = self.active.lock().await;
        let sandbox = active(&slot, id)?;
        run(sandbox, vec!["/bin/sh", "-c", &command]).await
    }

    pub async fn read_file(&self, id: &str, requested: &str) -> Result<ExecResult, SandboxError> {
        let (directory, name) = file_parts(requested)?;
        let slot = self.active.lock().await;
        let sandbox = active(&slot, id)?;
        let result = run(
            sandbox,
            vec!["/bin/sh", "-c", FILE_GUARD, "read", &directory, &name],
        )
        .await?;
        if result.exit_code != 0 {
            return Err(file_error());
        }
        Ok(result)
    }

    pub async fn write_file(
        &self,
        id: &str,
        requested: &str,
        text: &str,
    ) -> Result<(), SandboxError> {
        if text.len() > 32 * 1024 || text.contains('\0') {
            return Err(SandboxError::new(
                ErrorCode::InvalidCommand,
                "单次写入最多 32 KiB 的纯文本。",
            ));
        }
        let (directory, name) = file_parts(requested)?;
        let slot = self.active.lock().await;
        let sandbox = active(&slot, id)?;
        let result = run(
            sandbox,
            vec![
                "/bin/sh", "-c", FILE_GUARD, "write", &directory, &name, text,
            ],
        )
        .await?;
        if result.exit_code != 0 {
            return Err(file_error());
        }
        Ok(())
    }

    pub async fn list(&self, id: &str, requested: &str) -> Result<Vec<FileEntry>, SandboxError> {
        let path = container_path(requested)?;
        let slot = self.active.lock().await;
        let sandbox = active(&slot, id)?;
        let limit = LIST_LIMIT.to_string();
        let result = run(
            sandbox,
            vec!["/bin/sh", "-c", LIST_SCRIPT, "list", &path, &limit],
        )
        .await?;
        if result.exit_code != 0 || result.truncated {
            return Err(SandboxError::new(
                ErrorCode::InvalidPath,
                "无法读取该工作区目录，请刷新并检查目录权限。",
            ));
        }
        let fields: Vec<_> = result.stdout.split_terminator('\0').collect();
        let mut entries = fields
            .chunks_exact(2)
            .map(|item| {
                let (kind, name) = (item[0], item[1]);
                FileEntry {
                    name: name.into(),
                    path: format!("{path}/{name}"),
                    kind: match kind {
                        "d" => EntryKind::Directory,
                        "f" => EntryKind::File,
                        "l" => EntryKind::Symlink,
                        _ => EntryKind::Other,
                    },
                }
            })
            .collect::<Vec<_>>();
        entries.sort_by(|left, right| {
            (!matches!(left.kind, EntryKind::Directory), &left.name)
                .cmp(&(!matches!(right.kind, EntryKind::Directory), &right.name))
        });
        Ok(entries)
    }
}

#[cfg(unix)]
fn sandbox_user() -> Result<Option<String>, SandboxError> {
    // These POSIX calls only read the identity of this process.
    let (uid, gid) = unsafe { (libc::geteuid(), libc::getegid()) };
    if uid == 0 {
        return Err(SandboxError::invalid_workspace(
            "在 Unix 上请用有 Docker 权限的普通账号运行桌面端。",
        ));
    }
    Ok(Some(format!("{uid}:{gid}")))
}

#[cfg(not(unix))]
fn sandbox_user() -> Result<Option<String>, SandboxError> {
    Ok(None)
}

fn workspace_path(path: PathBuf) -> Result<String, SandboxError> {
    let resolved = path
        .canonicalize()
        .map_err(|_| SandboxError::invalid_workspace("无法访问选中的工作区目录。"))?;
    if !resolved.is_dir() || resolved.parent().is_none() {
        return Err(SandboxError::invalid_workspace(
            "请选择一个本地子目录作为工作区。",
        ));
    }
    let path = resolved
        .to_str()
        .ok_or_else(|| SandboxError::invalid_workspace("工作区路径不能转换为 UTF-8。"))?;
    #[cfg(windows)]
    let path = {
        let path = path.strip_prefix(r"\\?\").unwrap_or(path);
        if path.starts_with("UNC\\") || path.starts_with('\\') {
            return Err(SandboxError::invalid_workspace("请选择本地磁盘上的目录。"));
        }
        path
    };
    Ok(path.into())
}

fn container_path(requested: &str) -> Result<String, SandboxError> {
    if requested == ROOT || requested.is_empty() {
        return Ok(ROOT.into());
    }
    let relative = if requested.starts_with('/') {
        requested.strip_prefix("/workspace/").ok_or_else(|| {
            SandboxError::new(ErrorCode::InvalidPath, "路径必须位于 /workspace 内。")
        })?
    } else {
        requested
    };
    let mut result = ROOT.to_string();
    for part in relative.split('/') {
        match part {
            "" | "." => (),
            ".." => {
                return Err(SandboxError::new(
                    ErrorCode::InvalidPath,
                    "不允许访问上级目录。",
                ));
            }
            part if part.contains('\0') => {
                return Err(SandboxError::new(ErrorCode::InvalidPath, "路径无效。"));
            }
            part => {
                result.push('/');
                result.push_str(part);
            }
        }
    }
    Ok(result)
}

fn active<'a>(
    slot: &'a Option<ActiveSandbox>,
    id: &str,
) -> Result<&'a ActiveSandbox, SandboxError> {
    slot.as_ref()
        .filter(|item| item.info.id == id)
        .ok_or_else(|| {
            SandboxError::new(
                ErrorCode::InvalidSandbox,
                "指定的沙箱不属于当前应用或已停止。",
            )
        })
}

async fn ensure_image(docker: &Docker) -> Result<(), SandboxError> {
    match docker.inspect_image(IMAGE).await {
        Ok(_) => return Ok(()),
        Err(DockerError::DockerResponseServerError {
            status_code: 404, ..
        }) => (),
        Err(error) => {
            return Err(SandboxError::docker(
                ErrorCode::Image,
                "检查镜像失败",
                error,
            ));
        }
    }
    let mut download = docker.create_image(
        Some(
            CreateImageOptionsBuilder::default()
                .from_image(IMAGE)
                .build(),
        ),
        None,
        None,
    );
    while let Some(progress) = download.next().await {
        progress.map_err(|error| {
            SandboxError::docker(ErrorCode::Image, "下载 Alpine 镜像失败", error)
        })?;
    }
    docker
        .inspect_image(IMAGE)
        .await
        .map_err(|error| SandboxError::docker(ErrorCode::Image, "镜像不可用", error))?;
    Ok(())
}

async fn remove(docker: &Docker, id: &str) -> Result<(), SandboxError> {
    // Force removal is also the fallback when an in-container process does not exit on SIGTERM.
    let _ = docker
        .stop_container(
            id,
            Some(StopContainerOptionsBuilder::default().t(2).build()),
        )
        .await;
    match docker
        .remove_container(
            id,
            Some(RemoveContainerOptionsBuilder::default().force(true).build()),
        )
        .await
    {
        Ok(())
        | Err(DockerError::DockerResponseServerError {
            status_code: 404, ..
        }) => Ok(()),
        Err(error) => Err(SandboxError::docker(
            ErrorCode::Container,
            "清理容器失败",
            error,
        )),
    }
}

async fn run(sandbox: &ActiveSandbox, args: Vec<&str>) -> Result<ExecResult, SandboxError> {
    let command: Vec<String> = ["timeout", "-k", "2", "20"]
        .into_iter()
        .chain(args)
        .map(String::from)
        .collect();
    let docker = &sandbox.docker;
    let id = docker
        .create_exec(
            &sandbox.info.id,
            ExecConfig {
                attach_stdout: Some(true),
                attach_stderr: Some(true),
                tty: Some(false),
                working_dir: Some(ROOT.into()),
                cmd: Some(command),
                ..Default::default()
            },
        )
        .await
        .map_err(|error| SandboxError::docker(ErrorCode::Execution, "创建容器命令失败", error))?
        .id;
    let started = docker
        .start_exec(&id, None)
        .await
        .map_err(|error| SandboxError::docker(ErrorCode::Execution, "启动容器命令失败", error))?;
    let StartExecResults::Attached { mut output, .. } = started else {
        return Err(SandboxError::new(
            ErrorCode::Execution,
            "Docker 未提供命令输出。",
        ));
    };
    let mut stdout = Vec::new();
    let mut stderr = Vec::new();
    let mut truncated = false;
    while let Some(frame) = output.next().await {
        match frame.map_err(|error| {
            SandboxError::docker(ErrorCode::Execution, "读取容器输出失败", error)
        })? {
            LogOutput::StdOut { message } | LogOutput::Console { message } => {
                truncated |= append(&mut stdout, &message)
            }
            LogOutput::StdErr { message } => truncated |= append(&mut stderr, &message),
            LogOutput::StdIn { .. } => (),
        }
    }
    let execution = docker
        .inspect_exec(&id)
        .await
        .map_err(|error| SandboxError::docker(ErrorCode::Execution, "读取退出状态失败", error))?;
    let exit_code = execution
        .exit_code
        .ok_or_else(|| SandboxError::new(ErrorCode::Execution, "Docker 未报告退出码。"))?;
    Ok(ExecResult {
        stdout: String::from_utf8_lossy(&stdout).into_owned(),
        stderr: String::from_utf8_lossy(&stderr).into_owned(),
        exit_code,
        truncated,
    })
}

fn append(target: &mut Vec<u8>, data: &[u8]) -> bool {
    let keep = data.len().min(OUTPUT_LIMIT.saturating_sub(target.len()));
    target.extend_from_slice(&data[..keep]);
    keep < data.len()
}

impl FileEntry {
    pub fn display(&self) -> String {
        match self.kind {
            EntryKind::Directory => format!("{}/", self.name),
            EntryKind::Symlink => format!("{}（链接）", self.name),
            _ => self.name.clone(),
        }
    }
}

fn file_parts(requested: &str) -> Result<(String, String), SandboxError> {
    let path = container_path(requested)?;
    let (directory, name) = path.rsplit_once('/').ok_or_else(file_error)?;
    if directory.is_empty() || name.is_empty() {
        return Err(file_error());
    }
    Ok((directory.into(), name.into()))
}

fn file_error() -> SandboxError {
    SandboxError::new(
        ErrorCode::InvalidPath,
        "只能读写 /workspace 内的普通文件。请检查路径、上级目录和文件权限。",
    )
}

const FILE_GUARD: &str = r#"
cd -P "$1" || exit 44
case "$PWD" in /workspace|/workspace/*) ;; *) exit 44;; esac
name="./$2"
[ ! -L "$name" ] || exit 44
case "$0" in
  read)
    [ -f "$name" ] || exit 44
    head -c 65537 "$name"
    ;;
  write)
    [ ! -e "$name" ] || [ -f "$name" ] || exit 44
    tmp="$(mktemp .tian-write.XXXXXX)" || exit 44
    trap 'rm -f "$tmp"' EXIT
    printf '%s' "$3" > "$tmp" && mv -fT -- "$tmp" "$name"
    ;;
  *) exit 44 ;;
esac
"#;

const LIST_SCRIPT: &str = r#"
cd -P "$1" || exit 44
dir="$PWD"
case "$dir" in /workspace|/workspace/*) ;; *) exit 44;; esac
[ -d "$dir" ] && [ -r "$dir" ] && [ -x "$dir" ] || exit 44
count=0
for entry in "$dir"/* "$dir"/.[!.]* "$dir"/..?*; do
  if [ -L "$entry" ]; then kind=l
  elif [ -d "$entry" ]; then kind=d
  elif [ -f "$entry" ]; then kind=f
  elif [ -e "$entry" ]; then kind=o
  else continue; fi
  printf '%s\000%s\000' "$kind" "${entry##*/}"
  count=$((count + 1))
  [ "$count" -lt "$2" ] || break
done
"#;

#[cfg(test)]
mod tests;
