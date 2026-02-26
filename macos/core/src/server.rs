use anyhow::Result;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio::time;
use tokio_util::sync::CancellationToken;
use tracing::{error, info, warn};

use crate::clipboard;
use crate::crypto::{self, NoiseTransport};
use crate::events::{AppState, ServerEvent};
use crate::protocol::{Message, MessageType, IMAGE_CHUNK_SIZE, MAX_IMAGE_SIZE};

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

/// Send image as chunked messages through the session channel.
pub async fn send_image_chunks(
    tx: &mpsc::UnboundedSender<Message>,
    png_bytes: &[u8],
    width: u32,
    height: u32,
    state: &AppState,
) -> Result<()> {
    let total_bytes = png_bytes.len();
    let metadata = serde_json::json!({
        "width": width,
        "height": height,
        "totalBytes": total_bytes,
        "mimeType": "image/png"
    });
    tx.send(Message::image_send_start(&metadata.to_string()))?;

    let mut sent = 0usize;
    for chunk in png_bytes.chunks(IMAGE_CHUNK_SIZE) {
        tx.send(Message::image_chunk(chunk))?;
        sent += chunk.len();
        state.emit(ServerEvent::ImageTransferProgress {
            bytes_transferred: sent as u64,
            bytes_total: total_bytes as u64,
        });
    }

    tx.send(Message::image_send_end())?;
    info!(
        "image send complete: {}x{}, {} bytes in {} chunks",
        width,
        height,
        total_bytes,
        total_bytes.div_ceil(IMAGE_CHUNK_SIZE)
    );
    Ok(())
}

/// State for tracking an in-progress image receive.
struct ImageReceiveState {
    width: u32,
    height: u32,
    total_bytes: usize,
    buffer: Vec<u8>,
}

/// Handle an authenticated session with a connected device.
async fn handle_session(
    mut transport: NoiseTransport,
    state: &AppState,
    cancel: &CancellationToken,
) -> Result<()> {
    // Create outbound message channel
    let (tx, mut rx) = mpsc::unbounded_channel();
    {
        let mut session_tx = state.session_tx.write().await;
        *session_tx = Some(tx);
    }

    let result = handle_session_loop(&mut transport, &mut rx, state, cancel).await;

    // Cleanup: clear the session sender
    {
        let mut session_tx = state.session_tx.write().await;
        *session_tx = None;
    }

    result
}

/// Inner message loop for an authenticated session.
async fn handle_session_loop(
    transport: &mut NoiseTransport,
    rx: &mut mpsc::UnboundedReceiver<Message>,
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
    let mut image_receive: Option<ImageReceiveState> = None;
    let mut last_sent_image_bytes: Option<usize> = None;

    loop {
        tokio::select! {
            result = transport.recv_message() => {
                let msg = result?;
                match msg.msg_type {
                    MessageType::ClipboardSend => {
                        let text = msg.payload_text()?;
                        let chars = text.len();
                        info!("received clipboard content ({} chars)", chars);
                        if let Err(e) = clipboard::set_clipboard_text(&text) {
                            error!("failed to set clipboard: {}", e);
                            let err_msg = Message::error(&format!("clipboard error: {}", e));
                            transport.send_message(&err_msg).await?;
                        } else {
                            transport.send_message(&Message::clipboard_ack()).await?;
                            state.emit(ServerEvent::ClipboardReceived { chars });
                        }
                    }
                    MessageType::ClipboardAck => {
                        info!("received clipboard ACK from remote");
                        state.emit(ServerEvent::ClipboardSent { chars: 0 });
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
                        if image_receive.is_some() {
                            info!("aborting in-progress image receive due to remote error");
                            image_receive = None;
                            state.emit(ServerEvent::ImageTransferFailed {
                                reason: format!("remote error: {}", text),
                            });
                        }
                    }
                    MessageType::ImageSendStart => {
                        let json_str = msg.payload_text()?;
                        let meta: serde_json::Value = serde_json::from_str(&json_str)?;
                        let width = meta["width"].as_u64().unwrap_or(0) as u32;
                        let height = meta["height"].as_u64().unwrap_or(0) as u32;
                        let total_bytes = meta["totalBytes"].as_u64().unwrap_or(0) as usize;

                        if total_bytes > MAX_IMAGE_SIZE {
                            warn!("image too large: {} bytes (max {})", total_bytes, MAX_IMAGE_SIZE);
                            transport.send_message(&Message::error("image too large")).await?;
                            continue;
                        }
                        if image_receive.is_some() {
                            warn!("concurrent image transfer rejected");
                            transport.send_message(&Message::error("transfer already in progress")).await?;
                            continue;
                        }

                        info!("starting image receive: {}x{}, {} bytes", width, height, total_bytes);
                        image_receive = Some(ImageReceiveState {
                            width,
                            height,
                            total_bytes,
                            buffer: Vec::with_capacity(total_bytes),
                        });
                        state.emit(ServerEvent::ImageTransferProgress {
                            bytes_transferred: 0,
                            bytes_total: total_bytes as u64,
                        });
                    }
                    MessageType::ImageChunk => {
                        if let Some(ref mut recv_state) = image_receive {
                            if recv_state.buffer.len() + msg.payload.len() > MAX_IMAGE_SIZE {
                                warn!("cumulative image data exceeds max size, aborting");
                                image_receive = None;
                                transport.send_message(&Message::error("image data exceeds max size")).await?;
                                state.emit(ServerEvent::ImageTransferFailed {
                                    reason: "cumulative data exceeds max size".to_string(),
                                });
                                continue;
                            }
                            recv_state.buffer.extend_from_slice(&msg.payload);
                            state.emit(ServerEvent::ImageTransferProgress {
                                bytes_transferred: recv_state.buffer.len() as u64,
                                bytes_total: recv_state.total_bytes as u64,
                            });
                        } else {
                            warn!("unexpected IMAGE_CHUNK without active transfer");
                            transport.send_message(&Message::error("no active image transfer")).await?;
                        }
                    }
                    MessageType::ImageSendEnd => {
                        if let Some(recv_state) = image_receive.take() {
                            info!("image receive complete, writing to clipboard ({}x{}, {} bytes)",
                                recv_state.width, recv_state.height, recv_state.buffer.len());
                            if let Err(e) = clipboard::set_clipboard_image(&recv_state.buffer) {
                                error!("failed to set clipboard image: {}", e);
                                transport.send_message(&Message::error(&format!("clipboard error: {}", e))).await?;
                                state.emit(ServerEvent::ImageTransferFailed {
                                    reason: e.to_string(),
                                });
                            } else {
                                transport.send_message(&Message::image_ack()).await?;
                                state.emit(ServerEvent::ImageReceived {
                                    width: recv_state.width,
                                    height: recv_state.height,
                                    bytes: recv_state.buffer.len(),
                                });
                            }
                        } else {
                            warn!("unexpected IMAGE_SEND_END without active transfer");
                            transport.send_message(&Message::error("no active image transfer")).await?;
                        }
                    }
                    MessageType::ImageAck => {
                        let bytes = last_sent_image_bytes.take().unwrap_or(0);
                        info!("received image ACK from remote ({} bytes)", bytes);
                        state.emit(ServerEvent::ImageSent { bytes });
                    }
                    _ => {
                        warn!("unexpected message type: {:?}", msg.msg_type);
                    }
                }
            }
            Some(outbound_msg) = rx.recv() => {
                if outbound_msg.msg_type == MessageType::ImageSendStart {
                    if let Ok(json_str) = outbound_msg.payload_text() {
                        if let Ok(meta) = serde_json::from_str::<serde_json::Value>(&json_str) {
                            last_sent_image_bytes = meta["totalBytes"].as_u64().map(|b| b as usize);
                        }
                    }
                }
                transport.send_message(&outbound_msg).await?;
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
