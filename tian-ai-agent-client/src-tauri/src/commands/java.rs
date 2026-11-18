use crate::{
    audit::{Audit, Entry},
    java_client::{Access, AccessUpdate, HealthReport, JavaCall, JavaClient, JavaError},
    persistence::Persistence,
};
use serde_json::Value;
use tauri::State;

#[tauri::command]
pub async fn check_health(
    client: State<'_, JavaClient>,
    audit: State<'_, Audit>,
) -> Result<HealthReport, JavaError> {
    Ok(client.check(&audit).await)
}

#[tauri::command]
pub async fn java_access(client: State<'_, JavaClient>) -> Result<Access, JavaError> {
    Ok(client.access().await)
}

#[tauri::command]
pub async fn configure_java(
    input: AccessUpdate,
    client: State<'_, JavaClient>,
    storage: State<'_, Persistence>,
) -> Result<Access, JavaError> {
    let connection = client.prepare_access(input).await?;
    storage
        .save_access(&connection)
        .await
        .map_err(|error| JavaError::new(error.code, error.message))?;
    Ok(client.set_connection(connection).await)
}

#[tauri::command]
pub async fn java_call(
    request: JavaCall,
    client: State<'_, JavaClient>,
    audit: State<'_, Audit>,
) -> Result<Value, JavaError> {
    client.call(&audit, request).await
}

#[tauri::command]
pub async fn desktop_diagnostics(audit: State<'_, Audit>) -> Result<Vec<Entry>, JavaError> {
    Ok(audit.recent())
}
