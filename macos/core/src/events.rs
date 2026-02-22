use serde::Serialize;
use std::sync::Arc;
use tokio::sync::{broadcast, RwLock};

use crate::crypto::Identity;
use crate::storage::DeviceStore;

/// Events emitted by the server for UI consumption.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data")]
pub enum ServerEvent {
    ServerStarted { port: u16, pairing_code: String },
    DeviceConnected { name: String },
    DeviceDisconnected { name: String },
    ClipboardReceived { chars: usize },
    DevicePaired { name: String },
    HandshakeFailed { addr: String, reason: String },
}

/// Shared application state accessible from server, CLI, and Tauri.
pub struct AppState {
    pub identity: Identity,
    pub pairing_code: String,
    pub device_name: String,
    pub store: DeviceStore,
    pub port: u16,
    pub connected_device: Arc<RwLock<Option<String>>>,
    pub event_tx: broadcast::Sender<ServerEvent>,
}

impl AppState {
    pub fn new(
        identity: Identity,
        pairing_code: String,
        device_name: String,
        store: DeviceStore,
        port: u16,
    ) -> Self {
        let (event_tx, _) = broadcast::channel(64);
        Self {
            identity,
            pairing_code,
            device_name,
            store,
            port,
            connected_device: Arc::new(RwLock::new(None)),
            event_tx,
        }
    }

    pub fn emit(&self, event: ServerEvent) {
        // Ignore send errors (no active receivers)
        let _ = self.event_tx.send(event);
    }

    pub fn subscribe(&self) -> broadcast::Receiver<ServerEvent> {
        self.event_tx.subscribe()
    }
}
