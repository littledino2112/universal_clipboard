use std::sync::Arc;

use serde::Serialize;
use tauri::State;

use uclip_core::events::AppState;

#[derive(Serialize)]
pub struct StatusInfo {
    pub pairing_code: String,
    pub port: u16,
    pub device_name: String,
    pub connected_device: Option<String>,
}

#[derive(Serialize)]
pub struct DeviceInfo {
    pub name: String,
    pub key_prefix: String,
}

#[tauri::command]
pub async fn get_status(state: State<'_, Arc<AppState>>) -> Result<StatusInfo, String> {
    let connected = state.connected_device.read().await.clone();
    Ok(StatusInfo {
        pairing_code: state.pairing_code.clone(),
        port: state.port,
        device_name: state.device_name.clone(),
        connected_device: connected,
    })
}

#[tauri::command]
pub async fn get_devices(state: State<'_, Arc<AppState>>) -> Result<Vec<DeviceInfo>, String> {
    let devices = state
        .store
        .list_paired_devices()
        .map_err(|e| e.to_string())?;
    Ok(devices
        .into_iter()
        .map(|(name, key)| DeviceInfo {
            name,
            key_prefix: key.chars().take(16).collect(),
        })
        .collect())
}

#[tauri::command]
pub async fn unpair_device(state: State<'_, Arc<AppState>>, name: String) -> Result<bool, String> {
    state
        .store
        .remove_paired_device(&name)
        .map_err(|e| e.to_string())
}
