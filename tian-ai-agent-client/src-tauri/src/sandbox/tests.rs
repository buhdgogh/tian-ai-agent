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
        let manager = SandboxManager::default();
        let chosen = manager.authorize(fixture.0.clone()).await?;
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

async fn verify_sandbox(
    manager: &SandboxManager,
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
