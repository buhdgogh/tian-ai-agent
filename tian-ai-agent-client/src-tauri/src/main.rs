#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audit;
mod commands;
mod config;
mod java_client;
mod sandbox;

use tauri::Manager;

fn main() {
    let result = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(sandbox::SandboxManager::default())
        .manage(java_client::JavaClient::default())
        .manage(commands::agent::PendingAgent::default())
        .setup(|app| {
            let audit = app
                .path()
                .app_log_dir()
                .map(audit::Audit::new)
                .unwrap_or_default();
            app.manage(audit);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::java::check_health,
            commands::java::java_access,
            commands::java::configure_java,
            commands::java::java_call,
            commands::java::desktop_diagnostics,
            commands::agent::run_agent,
            commands::agent::cancel_agent,
            commands::docker::check_docker,
            commands::docker::select_workspace,
            commands::docker::get_active_sandbox,
            commands::docker::create_sandbox,
            commands::docker::stop_sandbox,
            commands::docker::exec_in_sandbox,
            commands::docker::list_sandbox_files,
        ])
        .build(tauri::generate_context!());

    match result {
        Ok(app) => app.run(|handle, event| {
            if let tauri::RunEvent::Exit = event {
                let start = std::time::Instant::now();
                let cleanup = tauri::async_runtime::block_on(
                    handle.state::<sandbox::SandboxManager>().shutdown(),
                );
                if !matches!(cleanup, Ok(false)) {
                    let outcome = cleanup.as_ref().map_or_else(|error| error.code(), |_| "ok");
                    handle
                        .state::<audit::Audit>()
                        .record("sandbox", "shutdown", outcome, start, None);
                }
            }
        }),
        Err(error) => {
            eprintln!("Failed to start the desktop app: {error}");
            std::process::exit(1);
        }
    }
}
