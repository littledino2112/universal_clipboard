use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;
use tauri::State;
use tokio::sync::{Mutex, RwLock};

use uclip_core::clipboard;
use uclip_core::events::AppState;
use uclip_core::protocol::Message;
use uclip_core::server;

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

const MAX_CLIPBOARD_ITEMS: usize = 5;
const PREVIEW_MAX_CHARS: usize = 80;
/// Max payload size for Noise transport: 65535 - 16 (AEAD tag) - 5 (message header)
const MAX_SEND_BYTES: usize = 65514;

#[derive(Debug, Clone, Serialize)]
pub struct ClipboardItem {
    pub id: u64,
    pub item_type: String,
    pub text: String,
    pub preview: String,
    pub timestamp: u64,
    pub sent: bool,
    pub size_bytes: Option<u64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
}

pub type ImageStore = Arc<Mutex<HashMap<u64, Vec<u8>>>>;
pub type TransferLock = Arc<AtomicBool>;

pub type ClipboardItems = Arc<RwLock<Vec<ClipboardItem>>>;

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

fn make_preview(text: &str) -> String {
    let single_line: String = text
        .chars()
        .map(|c| if c == '\n' || c == '\r' { ' ' } else { c })
        .collect();
    let trimmed = single_line.trim();
    if trimmed.chars().count() > PREVIEW_MAX_CHARS {
        trimmed.chars().take(PREVIEW_MAX_CHARS).collect::<String>() + "…"
    } else {
        trimmed.to_string()
    }
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
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

#[tauri::command]
pub async fn paste_clipboard(
    items: State<'_, ClipboardItems>,
) -> Result<Vec<ClipboardItem>, String> {
    let text = clipboard::get_clipboard_text()
        .map_err(|e| e.to_string())?
        .unwrap_or_default();

    if text.is_empty() {
        let items = items.read().await;
        return Ok(items.clone());
    }

    // Dedup: skip if the most recent item has the same text
    {
        let items = items.read().await;
        if items.first().map(|i| i.text.as_str()) == Some(text.as_str()) {
            return Ok(items.clone());
        }
    }

    let item = ClipboardItem {
        id: NEXT_ID.fetch_add(1, Ordering::Relaxed),
        item_type: "text".to_string(),
        preview: make_preview(&text),
        text,
        timestamp: now_millis(),
        sent: false,
        size_bytes: None,
        width: None,
        height: None,
    };

    let mut items = items.write().await;
    items.insert(0, item);
    if items.len() > MAX_CLIPBOARD_ITEMS {
        items.truncate(MAX_CLIPBOARD_ITEMS);
    }
    Ok(items.clone())
}

#[tauri::command]
pub async fn get_clipboard_items(
    items: State<'_, ClipboardItems>,
) -> Result<Vec<ClipboardItem>, String> {
    let items = items.read().await;
    Ok(items.clone())
}

#[tauri::command]
pub async fn send_clipboard_item(
    id: u64,
    items: State<'_, ClipboardItems>,
    state: State<'_, Arc<AppState>>,
) -> Result<bool, String> {
    let mut items = items.write().await;
    let item = items.iter_mut().find(|i| i.id == id);
    let item = match item {
        Some(i) => i,
        None => return Err("item not found".to_string()),
    };

    let session_tx = state.session_tx.read().await;
    let tx = match session_tx.as_ref() {
        Some(tx) => tx,
        None => return Err("no active session".to_string()),
    };

    if item.text.len() > MAX_SEND_BYTES {
        return Err(format!(
            "text too large to send ({} bytes, max {})",
            item.text.len(),
            MAX_SEND_BYTES
        ));
    }

    let msg = Message::clipboard_send(&item.text);
    tx.send(msg).map_err(|e| e.to_string())?;

    // Option A: mark as sent optimistically
    item.sent = true;
    Ok(true)
}

#[tauri::command]
pub async fn remove_clipboard_item(
    id: u64,
    items: State<'_, ClipboardItems>,
    image_store: State<'_, ImageStore>,
) -> Result<Vec<ClipboardItem>, String> {
    let mut items = items.write().await;
    items.retain(|i| i.id != id);
    // Clean up image data
    let mut store = image_store.lock().await;
    store.remove(&id);
    Ok(items.clone())
}

#[tauri::command]
pub async fn paste_image_from_clipboard(
    items: State<'_, ClipboardItems>,
    image_store: State<'_, ImageStore>,
) -> Result<Vec<ClipboardItem>, String> {
    let png_bytes = clipboard::get_clipboard_image()
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "No image on clipboard".to_string())?;

    let (width, height) = clipboard::png_dimensions(&png_bytes).map_err(|e| e.to_string())?;

    let size_bytes = png_bytes.len() as u64;
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    let kb = size_bytes / 1024;
    let preview = format!("Image ({}x{}, {} KB)", width, height, kb);

    let item = ClipboardItem {
        id,
        item_type: "image".to_string(),
        text: String::new(),
        preview,
        timestamp: now_millis(),
        sent: false,
        size_bytes: Some(size_bytes),
        width: Some(width),
        height: Some(height),
    };

    // Store PNG bytes
    {
        let mut store = image_store.lock().await;
        store.insert(id, png_bytes);
    }

    let mut items = items.write().await;
    items.insert(0, item);

    // Evict old items and their image data
    if items.len() > MAX_CLIPBOARD_ITEMS {
        let evicted: Vec<u64> = items[MAX_CLIPBOARD_ITEMS..]
            .iter()
            .filter(|i| i.item_type == "image")
            .map(|i| i.id)
            .collect();
        items.truncate(MAX_CLIPBOARD_ITEMS);
        let mut store = image_store.lock().await;
        for evicted_id in evicted {
            store.remove(&evicted_id);
        }
    }

    Ok(items.clone())
}

#[tauri::command]
pub async fn send_image_item(
    id: u64,
    items: State<'_, ClipboardItems>,
    image_store: State<'_, ImageStore>,
    state: State<'_, Arc<AppState>>,
    transfer_lock: State<'_, TransferLock>,
) -> Result<bool, String> {
    // Check transfer lock
    if transfer_lock.swap(true, Ordering::SeqCst) {
        return Err("transfer already in progress".to_string());
    }

    let item = {
        let items = items.read().await;
        items
            .iter()
            .find(|i| i.id == id)
            .cloned()
            .ok_or_else(|| "item not found".to_string())?
    };

    if item.item_type != "image" {
        transfer_lock.store(false, Ordering::SeqCst);
        return Err("item is not an image".to_string());
    }

    let png_bytes = {
        let store = image_store.lock().await;
        store
            .get(&id)
            .cloned()
            .ok_or_else(|| "image data not found".to_string())?
    };

    let width = item.width.unwrap_or(0);
    let height = item.height.unwrap_or(0);

    let session_tx = state.session_tx.read().await;
    let tx = match session_tx.as_ref() {
        Some(tx) => tx.clone(),
        None => {
            transfer_lock.store(false, Ordering::SeqCst);
            return Err("no active session".to_string());
        }
    };

    let state_inner = state.inner().clone();
    let transfer_lock_inner = transfer_lock.inner().clone();
    let items_inner = items.inner().clone();

    tauri::async_runtime::spawn(async move {
        let result = server::send_image_chunks(&tx, &png_bytes, width, height, &state_inner).await;
        transfer_lock_inner.store(false, Ordering::SeqCst);
        match result {
            Ok(()) => {
                // Mark item as sent
                let mut items = items_inner.write().await;
                if let Some(item) = items.iter_mut().find(|i| i.id == id) {
                    item.sent = true;
                }
            }
            Err(e) => {
                tracing::error!("image send failed: {}", e);
                state_inner.emit(uclip_core::events::ServerEvent::ImageTransferFailed {
                    reason: e.to_string(),
                });
            }
        }
    });

    Ok(true)
}
