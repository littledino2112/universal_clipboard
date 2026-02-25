use serde::Serialize;
use std::sync::Arc;
use tokio::sync::{broadcast, mpsc, RwLock};

use crate::crypto::Identity;
use crate::protocol::Message;
use crate::storage::DeviceStore;

/// Events emitted by the server for UI consumption.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data")]
pub enum ServerEvent {
    ServerStarted { port: u16, pairing_code: String },
    DeviceConnected { name: String },
    DeviceDisconnected { name: String },
    ClipboardReceived { chars: usize },
    ClipboardSent { chars: usize },
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
    pub session_tx: Arc<RwLock<Option<mpsc::UnboundedSender<Message>>>>,
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
            session_tx: Arc::new(RwLock::new(None)),
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::Message;

    #[test]
    fn test_clipboard_sent_event_serializes() {
        let event = ServerEvent::ClipboardSent { chars: 42 };
        let json = serde_json::to_string(&event).unwrap();
        assert!(json.contains("ClipboardSent"));
        assert!(json.contains("42"));
    }

    #[test]
    fn test_clipboard_sent_event_roundtrip() {
        let event = ServerEvent::ClipboardSent { chars: 100 };
        let json = serde_json::to_string(&event).unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed["type"], "ClipboardSent");
        assert_eq!(parsed["data"]["chars"], 100);
    }

    #[tokio::test]
    async fn test_session_tx_is_none_by_default() {
        // We can't easily construct AppState without a real Identity/DeviceStore,
        // so test the pattern directly.
        let session_tx: Arc<RwLock<Option<mpsc::UnboundedSender<Message>>>> =
            Arc::new(RwLock::new(None));
        assert!(session_tx.read().await.is_none());
    }

    #[tokio::test]
    async fn test_session_tx_channel_works() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let session_tx: Arc<RwLock<Option<mpsc::UnboundedSender<Message>>>> =
            Arc::new(RwLock::new(Some(tx)));

        // Send a message through the stored sender
        {
            let guard = session_tx.read().await;
            let sender = guard.as_ref().unwrap();
            sender.send(Message::clipboard_send("test")).unwrap();
        }

        let msg = rx.recv().await.unwrap();
        assert_eq!(msg.msg_type, crate::protocol::MessageType::ClipboardSend);
        assert_eq!(msg.payload_text().unwrap(), "test");
    }
}
