use anyhow::Result;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::TcpListener;
use tokio::time;
use tokio_util::sync::CancellationToken;
use tracing::{error, info, warn};

use crate::clipboard;
use crate::crypto::{self, NoiseTransport};
use crate::events::{AppState, ServerEvent};
use crate::protocol::{Message, MessageType};

/// Run the receiver server, accepting and handling one connection at a time.
/// Supports graceful shutdown via CancellationToken.
pub async fn run_server(
    listener: TcpListener,
    state: Arc<AppState>,
    cancel: CancellationToken,
) -> Result<()> {
    let port = state.port;
    info!("server listening on {}", listener.local_addr()?);
    info!("pairing code: {}", state.pairing_code);

    state.emit(ServerEvent::ServerStarted {
        port,
        pairing_code: state.pairing_code.clone(),
    });

    loop {
        let (stream, addr) = tokio::select! {
            result = listener.accept() => result?,
            _ = cancel.cancelled() => {
                info!("server shutting down");
                return Ok(());
            }
        };
        info!("connection from {}", addr);

        match crypto::accept_connection(stream, &state.identity, &state.pairing_code, &state.store)
            .await
        {
            Ok((transport, remote_name)) => {
                info!("authenticated: {}", remote_name);
                state.emit(ServerEvent::DeviceConnected {
                    name: remote_name.clone(),
                });
                {
                    let mut connected = state.connected_device.write().await;
                    *connected = Some(remote_name.clone());
                }

                if let Err(e) = handle_session(transport, &state, &cancel).await {
                    warn!("session with {} ended: {}", remote_name, e);
                }

                state.emit(ServerEvent::DeviceDisconnected {
                    name: remote_name.clone(),
                });
                {
                    let mut connected = state.connected_device.write().await;
                    *connected = None;
                }
            }
            Err(e) => {
                warn!("handshake failed from {}: {}", addr, e);
                state.emit(ServerEvent::HandshakeFailed {
                    addr: addr.to_string(),
                    reason: e.to_string(),
                });
            }
        }
    }
}

/// Handle an authenticated session with a connected device.
async fn handle_session(
    mut transport: NoiseTransport,
    state: &AppState,
    cancel: &CancellationToken,
) -> Result<()> {
    // Exchange device info
    let info_msg = Message::device_info(&state.device_name);
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
                        let chars = text.len();
                        info!("received clipboard content ({} chars)", chars);
                        // Write to system clipboard
                        if let Err(e) = clipboard::set_clipboard_text(&text) {
                            error!("failed to set clipboard: {}", e);
                            let err_msg = Message::error(&format!("clipboard error: {}", e));
                            transport.send_message(&err_msg).await?;
                        } else {
                            transport.send_message(&Message::clipboard_ack()).await?;
                            state.emit(ServerEvent::ClipboardReceived { chars });
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
            _ = cancel.cancelled() => {
                info!("session cancelled");
                return Ok(());
            }
        }
    }
}
