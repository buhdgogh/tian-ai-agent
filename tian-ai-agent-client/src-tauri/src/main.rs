#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audit;
mod commands;
mod config;
mod java_client;
mod persistence;
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
            let storage = persistence::Persistence::new(app.path().app_data_dir().ok());
            match tauri::async_runtime::block_on(storage.restore_access()) {
                Ok(Some(connection)) => {
                    let client = app.state::<java_client::JavaClient>();
                    tauri::async_runtime::block_on(client.set_connection(connection));
                }
                Ok(None) => {}
                Err(error) => audit.record(
                    "storage",
                    "restore_access",
                    error.code,
                    std::time::Instant::now(),
                    None,
                ),
            }
            app.manage(storage);
            app.manage(audit);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::java::check_health,
            commands::java::java_access,
            commands::java::configure_java,
            commands::java::java_call,
            commands::java::desktop_diagnostics,
            persistence::load_workbench,
            persistence::save_workbench_session,
            persistence::delete_workbench_session,
            commands::agent::run_agent,
            commands::agent::cancel_agent,
            commands::docker::check_docker,
            commands::docker::select_workspace,
            commands::docker::get_active_sandbox,
            commands::docker::create_sandbox,
            commands::docker::stop_sandbox,
            commands::docker::release_workspace,
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
