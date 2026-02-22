use anyhow::Result;
use std::time::Duration;
use tokio::net::TcpListener;
use tokio::time;
use tracing::{error, info, warn};

use crate::clipboard;
use crate::crypto::{self, NoiseTransport};
use crate::protocol::{Message, MessageType};
use crate::storage::DeviceStore;

/// Run the receiver server, accepting and handling one connection at a time.
pub async fn run_server(
    listener: TcpListener,
    identity: &crypto::Identity,
    pairing_code: &str,
    store: &DeviceStore,
    device_name: &str,
) -> Result<()> {
    info!("server listening on {}", listener.local_addr()?);
    info!("pairing code: {}", pairing_code);

    loop {
        let (stream, addr) = listener.accept().await?;
        info!("connection from {}", addr);

        match crypto::accept_connection(stream, identity, pairing_code, store).await {
            Ok((transport, remote_name)) => {
                info!("authenticated: {}", remote_name);
                if let Err(e) = handle_session(transport, device_name).await {
                    warn!("session with {} ended: {}", remote_name, e);
                }
            }
            Err(e) => {
                warn!("handshake failed from {}: {}", addr, e);
            }
        }
    }
}

/// Handle an authenticated session with a connected device.
async fn handle_session(mut transport: NoiseTransport, our_name: &str) -> Result<()> {
    // Exchange device info
    let info_msg = Message::device_info(our_name);
    transport.send_message(&info_msg).await?;

    let remote_info = transport.recv_message().await?;
    if remote_info.msg_type == MessageType::DeviceInfo {
        let text = remote_info.payload_text()?;
        info!("remote device info: {}", text);
    }

    // Main message loop
    let keepalive = Duration::from_secs(30);
    loop {
        tokio::select! {
            result = transport.recv_message() => {
                let msg = result?;
                match msg.msg_type {
                    MessageType::ClipboardSend => {
                        let text = msg.payload_text()?;
                        info!("received clipboard content ({} chars)", text.len());
                        // Write to system clipboard
                        if let Err(e) = clipboard::set_clipboard_text(&text) {
                            error!("failed to set clipboard: {}", e);
                            let err_msg = Message::error(&format!("clipboard error: {}", e));
                            transport.send_message(&err_msg).await?;
                        } else {
                            transport.send_message(&Message::clipboard_ack()).await?;
                        }
                    }
                    MessageType::Ping => {
                        transport.send_message(&Message::pong()).await?;
                    }
                    MessageType::Pong => {
                        // keepalive response, ignore
                    }
                    MessageType::Error => {
                        let text = msg.payload_text().unwrap_or_default();
                        warn!("remote error: {}", text);
                    }
                    _ => {
                        warn!("unexpected message type: {:?}", msg.msg_type);
                    }
                }
            }
            _ = time::sleep(keepalive) => {
                transport.send_message(&Message::ping()).await?;
            }
        }
    }
}
