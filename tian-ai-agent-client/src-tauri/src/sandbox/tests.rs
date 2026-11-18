use super::*;
use std::{
    error::Error,
    fs, io,
    time::{SystemTime, UNIX_EPOCH},
};

#[test]
fn container_paths_stay_under_workspace() -> Result<(), SandboxError> {
    assert_eq!(container_path("/workspace")?, ROOT);
    assert_eq!(container_path("./nested/file")?, "/workspace/nested/file");
    assert!(container_path("/etc").is_err());
    assert!(container_path("/workspace/nested/../../etc").is_err());
    Ok(())
}

#[test]
#[ignore = "requires a running local Docker Linux engine and may download the sandbox image"]
fn docker_workspace_lifecycle() -> Result<(), Box<dyn Error>> {
    let unique = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
    let parent = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../.cache");
    fs::create_dir_all(&parent)?;
    let fixture = Fixture(parent.join(format!("sandbox-test-{unique}")));
    fs::create_dir(&fixture.0)?;
    fs::create_dir(fixture.0.join("nested"))?;
    fs::write(fixture.0.join("nested/leaf.txt"), "fixture")?;
    tauri::async_runtime::block_on(async {
        let manager = Workspace::default();
        let chosen = manager
            .bind_directory(workspace_path(fixture.0.clone())?)
            .await?;
        let info = match manager.create(chosen).await {
            Ok(info) => info,
            Err(error) => {
                let _ = manager.shutdown().await;
                return Err(error.into());
            }
        };
        // Keep cleanup outside verification so it still runs on an assertion failure.
        let verification = verify_sandbox(&manager, &info.id, &fixture.0).await;
        let cleanup = manager.stop(&info.id).await;
        cleanup?;
        verification?;
        assert!(manager.current().await.is_none());
        let docker = connect().await?;
        assert!(matches!(
            docker.inspect_container(&info.id, None).await,
            Err(DockerError::DockerResponseServerError {
                status_code: 404,
                ..
            })
        ));
        Ok(())
    })
}

#[test]
#[ignore = "requires a running local Docker Linux engine"]
fn concurrent_sessions_own_separate_sandboxes() -> Result<(), Box<dyn Error>> {
    let unique = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
    let root = Fixture(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join(format!("../.cache/workspace-pair-{unique}")),
    );
    let first_path = root.0.join("first");
    let second_path = root.0.join("second");
    fs::create_dir_all(&first_path)?;
    fs::create_dir_all(&second_path)?;
    fs::write(first_path.join("project.txt"), "first")?;
    fs::write(second_path.join("project.txt"), "second")?;
    tauri::async_runtime::block_on(async {
        let registry = SandboxManager::default();
        let result = async {
            let first = registry.workspace("chat-first").await?;
            let second = registry.workspace("chat-second").await?;
            let first_dir = first
                .bind_directory(workspace_path(first_path.clone())?)
                .await?;
            let second_dir = second
                .bind_directory(workspace_path(second_path.clone())?)
                .await?;
            check(
                second.create(first_dir.clone()).await.is_err(),
                "Cross-session path accepted",
            )?;
            let (first_info, second_info) =
                futures_util::join!(first.create(first_dir), second.create(second_dir));
            let first_info = first_info?;
            let second_info = second_info?;
            let first_id = &first_info.id;
            let second_id = &second_info.id;
            check(first_id != second_id, "Sessions shared a container")?;
            check(
                first.read_file(second_id, "project.txt").await.is_err(),
                "Foreign container accepted",
            )?;
            {
                let _first_job = first.lease()?;
                let _second_job = second.lease()?;
                check(
                    first.lease().is_err(),
                    "Same workspace accepted a duplicate task",
                )?;
                let (first_output, second_output) = futures_util::join!(
                    first.exec(
                        first_id,
                        "printf first-modified > project.txt; cat project.txt".into()
                    ),
                    second.exec(second_id, "cat project.txt".into())
                );
                check(
                    first_output?.stdout == "first-modified",
                    "First project edit failed",
                )?;
                check(
                    second_output?.stdout == "second",
                    "Second project was changed",
                )?;
            }
            registry.release("chat-first").await?;
            check(
                first.current().await.is_none(),
                "Deleted session retained its container",
            )?;
            check(
                second.owns_running(second_id).await,
                "Deleting one session stopped the other",
            )?;
            check(
                fs::read_to_string(first_path.join("project.txt"))? == "first-modified",
                "Cleanup removed the edit",
            )?;
            Ok::<_, Box<dyn Error>>(())
        }
        .await;
        let cleanup = registry.shutdown().await;
        result?;
        cleanup?;
        Ok(())
    })
}

async fn verify_sandbox(
    manager: &Workspace,
    id: &str,
    fixture: &std::path::Path,
) -> Result<(), Box<dyn Error>> {
    let root = manager.list(id, ROOT).await?;
    check(
        root.iter()
            .any(|entry| entry.name == "nested" && matches!(entry.kind, EntryKind::Directory)),
        "Nested directory missing",
    )?;
    let nested = manager.list(id, "/workspace/nested").await?;
    check(
        nested.iter().any(|entry| entry.name == "leaf.txt"),
        "Nested file missing",
    )?;
    check(
        manager.list(id, "/workspace/../etc").await.is_err(),
        "Traversal was accepted",
    )?;
    check(
        manager.list("a-different-container", ROOT).await.is_err(),
        "Foreign container ID was accepted",
    )?;

    let output = manager
        .exec(
            id,
            "printf stdout; printf stderr >&2; printf synced > written.txt; exit 7".into(),
        )
        .await?;
    check(
        output.stdout == "stdout" && output.stderr == "stderr" && output.exit_code == 7,
        "Shell output or exit code was lost",
    )?;
    check(
        fs::read_to_string(fixture.join("written.txt"))? == "synced",
        "Host file did not reflect container write",
    )?;
    manager
        .write_file(id, "/workspace/nested/from-chat.txt", "一行\n  第二行\n")
        .await?;
    let file = manager.read_file(id, "nested/from-chat.txt").await?;
    check(
        file.stdout == "一行\n  第二行\n",
        "Container text read/write lost data",
    )?;
    check(
        fs::read_to_string(fixture.join("nested/from-chat.txt"))? == file.stdout,
        "Container text write did not reach the host",
    )?;
    check(
        manager
            .write_file(id, "../escape.txt", "blocked")
            .await
            .is_err(),
        "Write outside the workspace was accepted",
    )?;
    check(
        manager.read_file(id, "/etc/hosts").await.is_err(),
        "Read outside the workspace was accepted",
    )?;
    manager.create_file(id, "nested/team.txt", "old\n").await?;
    check(
        manager
            .create_file(id, "nested/team.txt", "replacement")
            .await
            .is_err(),
        "Team create allowed overwriting an existing file",
    )?;
    check(
        manager
            .replace_file(id, "nested/team.txt", "wrong", "new")
            .await
            .is_err(),
        "Team edit ignored the expected source text",
    )?;
    manager
        .replace_file(id, "nested/team.txt", "old", "new")
        .await?;
    check(
        fs::read_to_string(fixture.join("nested/team.txt"))? == "new\n",
        "Team edit was not applied to the workspace",
    )?;
    check(
        manager
            .exec_test(id, "test \"$(cat nested/team.txt)\" = new; exit $?")
            .await?
            .exit_code
            == 0,
        "Sandbox test command failed",
    )?;
    check(
        manager
            .exec(id, "touch /outside-workspace".into())
            .await?
            .exit_code
            != 0,
        "Container root filesystem was writable",
    )?;
    if manager
        .current()
        .await
        .is_some_and(|sandbox| sandbox.image == IMAGE)
    {
        verify_python_workspace(manager, id, fixture).await?;
    }
    use crate::commands::agent::{execute_local, local_request};
    let operation = local_request("/write nested/from-agent.txt\nagent route\n")?
        .ok_or_else(|| io::Error::other("The agent did not recognize /write"))?;
    execute_local(manager, id, operation).await?;
    let operation = local_request("/read nested/from-agent.txt")?
        .ok_or_else(|| io::Error::other("The agent did not recognize /read"))?;
    check(
        execute_local(manager, id, operation).await?.0 == "agent route\n",
        "File was not read via the Agent route",
    )?;
    let operation = local_request("/exec pwd")?
        .ok_or_else(|| io::Error::other("The agent did not recognize /exec"))?;
    check(
        execute_local(manager, id, operation)
            .await?
            .0
            .contains("/workspace"),
        "Agent shell did not run inside the workspace",
    )?;

    let denied = manager
        .exec(id, "echo no > /etc/tian-stage3-test".into())
        .await?;
    check(denied.exit_code != 0, "Container root was writable")?;
    Ok(())
}

async fn verify_python_workspace(
    manager: &Workspace,
    id: &str,
    fixture: &std::path::Path,
) -> Result<(), Box<dyn Error>> {
    use crate::audit::Audit;
    use serde_json::{Value, json};
    let audit = Audit::default();
    let source = "print(\"Hello, World!\")\n";
    let operations = [
        json!({"role": "EDIT", "action": "WRITE", "path": "hello.py", "text": source}),
        json!({"role": "TEST", "action": "TEST", "path": "", "text": "python -B hello.py"}),
        json!({"role": "REVIEW", "action": "READ", "path": "hello.py"}),
    ];
    let mut replies = Vec::new();
    for (index, mut operation) in operations.into_iter().enumerate() {
        operation["requestId"] = json!(format!("00000000-0000-0000-0000-{:012}", index));
        let call: workspace::WorkspaceCall = serde_json::from_value(json!({
            "runId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "request": operation
        }))?;
        let reply: Value = call.execute(manager, id, &audit).await;
        let receipt = call.receipt(&reply);
        if index == 0 {
            assert_eq!(receipt["path"], "hello.py");
        }
        if index == 1 {
            assert_eq!(receipt["operation"], "workspace_test");
            assert_eq!(receipt["exitCode"], 0);
            assert_eq!(receipt["stdout"], "Hello, World!\n");
        }
        if reply["ok"] != true {
            return Err(io::Error::other(reply.to_string()).into());
        }
        replies.push(reply);
    }
    assert_eq!(replies[1]["exitCode"], 0);
    assert_eq!(replies[1]["stdout"], "Hello, World!\n");
    assert_eq!(replies[2]["text"], source);
    assert_eq!(fs::read_to_string(fixture.join("hello.py"))?, source);
    Ok(())
}

fn check(condition: bool, message: &'static str) -> io::Result<()> {
    if condition {
        Ok(())
    } else {
        Err(io::Error::other(message))
    }
}

struct Fixture(PathBuf);

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[test]
#[ignore = "requires Docker Linux engine and the sandbox image"]
fn per_run_workspace_permissions_and_cleanup() -> Result<(), SandboxError> {
    tauri::async_runtime::block_on(async {
        let root = std::env::temp_dir().join(format!("tian-target-test-{}", std::process::id()));
        let first_dir = root.join("workspace1");
        let second_dir = root.join("workspace2");
        for directory in [&first_dir, &second_dir] {
            std::fs::create_dir_all(directory)
                .map_err(|error| SandboxError::invalid_workspace(error.to_string()))?;
        }
        let manager = SandboxManager::default();
        let result = async {
            let first = manager.workspace("chat1").await?;
            let first_path = first.bind_directory(workspace_path(first_dir.clone())?).await?;
            let default_sandbox = first.start_for_agent().await?;
            let target_path = workspace_path(second_dir.clone())?;
            let target = manager.open_run_workspace("request1", target_path.clone(), WorkspaceAccess { write: true, commands: false }).await?;
            let isolated = target.start_for_agent().await?;
            assert_ne!(isolated.id, default_sandbox.id);
            assert!(manager.selected_workspace("request1").await.is_none());
            target.write_file(&isolated.id, "changed.txt", "from target").await?;
            assert!(second_dir.join("changed.txt").exists());
            assert!(!first_dir.join("changed.txt").exists());
            assert!(matches!(target.exec(&isolated.id, "pwd".into()).await, Err(error) if error.code() == "workspace_access_denied"));
            manager.close_run_workspace("request1").await?;
            let docker = connect().await?;
            assert!(matches!(docker.inspect_container(&isolated.id, None).await, Err(DockerError::DockerResponseServerError { status_code: 404, .. })));
            assert!(first.owns_running(&default_sandbox.id).await);
            assert_eq!(manager.workspace_directory("chat1").await.as_deref(), Some(first_path.as_str()));

            let target = manager.open_run_workspace("request2", target_path, WorkspaceAccess { write: false, commands: true }).await?;
            let read_only = target.start_for_agent().await?;
            assert!(matches!(target.write_file(&read_only.id, "changed.txt", "blocked").await, Err(error) if error.code() == "workspace_access_denied"));
            assert_ne!(target.exec(&read_only.id, "echo blocked > /workspace/new.txt".into()).await?.exit_code, 0);
            assert!(!second_dir.join("new.txt").exists());
            Ok(())
        }.await;
        let cleanup = manager.shutdown().await;
        let _ = std::fs::remove_file(second_dir.join("changed.txt"));
        let _ = std::fs::remove_dir(&first_dir);
        let _ = std::fs::remove_dir(&second_dir);
        let _ = std::fs::remove_dir(&root);
        result.and(cleanup.map(|_| ()))
    })
}

#[test]
#[ignore = "requires a running local Docker Linux engine"]
fn default_chat_directory_is_lazy_and_survives_stopping_sandbox() -> Result<(), Box<dyn Error>> {
    let unique = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
    let root = Fixture(
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(format!("../.cache/default-chat-{unique}")),
    );
    fs::create_dir_all(&root.0)?;
    tauri::async_runtime::block_on(async {
        let registry = SandboxManager::default();
        let result = async {
            let scope = "account-example";
            let key = "chat-without-selection";
            let default = root.0.join("agent-files").join(scope).join(key);
            let workspace = registry
                .agent_workspace(key, root.0.clone(), scope.into())
                .await?;
            check(!default.exists(), "Preparing chat alone created files")?;
            check(
                registry.workspace_directory(key).await.is_none(),
                "Implicit directory became an explicit binding",
            )?;
            let sandbox = workspace.start_for_agent().await?;
            check(sandbox.default_workspace, "Default sandbox flag missing")?;
            workspace
                .write_file(&sandbox.id, "hello.txt", "saved in default workspace")
                .await?;
            workspace.stop(&sandbox.id).await?;
            check(
                fs::read_to_string(default.join("hello.txt"))? == "saved in default workspace",
                "Default file was lost",
            )?;
            let reopened = workspace.start_for_agent().await?;
            let output = workspace.read_file(&reopened.id, "hello.txt").await?;
            check(
                output.stdout == "saved in default workspace",
                "Default file could not be reopened",
            )?;
            let second_key = "another-chat";
            let second = registry
                .agent_workspace(second_key, root.0.clone(), scope.into())
                .await?;
            let second = second.start_for_agent().await?;
            check(
                second.workspace_path != reopened.workspace_path,
                "Unbound chats shared a directory",
            )?;
            Ok::<_, Box<dyn Error>>(())
        }
        .await;
        let cleanup = registry.shutdown().await;
        result?;
        cleanup?;
        Ok(())
    })
}
