fn main() {
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&[
            "check_health",
            "java_access",
            "configure_java",
            "java_call",
            "desktop_diagnostics",
            "load_workbench",
            "save_workbench_session",
            "delete_workbench_session",
            "run_agent",
            "cancel_agent",
            "check_docker",
            "select_workspace",
            "get_active_sandbox",
            "create_sandbox",
            "stop_sandbox",
            "release_workspace",
            "exec_in_sandbox",
            "list_sandbox_files",
        ]),
    ))
    .unwrap_or_else(|error| {
        eprintln!("Tauri build setup failed: {error:#}");
        std::process::exit(1);
    });
}
