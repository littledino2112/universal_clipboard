use anyhow::{bail, Result};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

/// Message types for the clipboard sync protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MessageType {
    ClipboardSend = 0x01,
    ClipboardAck = 0x02,
    Ping = 0x03,
    Pong = 0x04,
    DeviceInfo = 0x05,
    Error = 0x06,
}

impl TryFrom<u8> for MessageType {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            0x01 => Ok(Self::ClipboardSend),
            0x02 => Ok(Self::ClipboardAck),
            0x03 => Ok(Self::Ping),
            0x04 => Ok(Self::Pong),
            0x05 => Ok(Self::DeviceInfo),
            0x06 => Ok(Self::Error),
            _ => bail!("unknown message type: 0x{:02x}", value),
        }
    }
}

/// A protocol message consisting of a type and payload.
#[derive(Debug, Clone)]
pub struct Message {
    pub msg_type: MessageType,
    pub payload: Vec<u8>,
}

impl Message {
    pub fn new(msg_type: MessageType, payload: Vec<u8>) -> Self {
        Self { msg_type, payload }
    }

    pub fn clipboard_send(text: &str) -> Self {
        Self::new(MessageType::ClipboardSend, text.as_bytes().to_vec())
    }

    pub fn clipboard_ack() -> Self {
        Self::new(MessageType::ClipboardAck, vec![])
    }

    pub fn ping() -> Self {
        Self::new(MessageType::Ping, vec![])
    }

    pub fn pong() -> Self {
        Self::new(MessageType::Pong, vec![])
    }

    pub fn device_info(name: &str) -> Self {
        let json = serde_json::json!({ "name": name });
        Self::new(MessageType::DeviceInfo, json.to_string().into_bytes())
    }

    pub fn error(msg: &str) -> Self {
        Self::new(MessageType::Error, msg.as_bytes().to_vec())
    }

    /// Encode message into wire format: [type(1) | length(4) | payload(N)]
    pub fn encode(&self) -> Vec<u8> {
        let len = self.payload.len() as u32;
        let mut buf = Vec::with_capacity(5 + self.payload.len());
        buf.push(self.msg_type as u8);
        buf.extend_from_slice(&len.to_be_bytes());
        buf.extend_from_slice(&self.payload);
        buf
    }

    /// Decode a message from wire format bytes.
    pub fn decode(data: &[u8]) -> Result<Self> {
        if data.len() < 5 {
            bail!("message too short: {} bytes", data.len());
        }
        let msg_type = MessageType::try_from(data[0])?;
        let len = u32::from_be_bytes([data[1], data[2], data[3], data[4]]) as usize;
        if data.len() < 5 + len {
            bail!(
                "payload too short: expected {} bytes, got {}",
                len,
                data.len() - 5
            );
        }
        let payload = data[5..5 + len].to_vec();
        Ok(Self { msg_type, payload })
    }

    /// Get payload as UTF-8 string.
    pub fn payload_text(&self) -> Result<String> {
        Ok(String::from_utf8(self.payload.clone())?)
    }
}

/// Maximum message payload size (1 MB).
const MAX_PAYLOAD_SIZE: u32 = 1_048_576;

/// Handshake type markers sent before the Noise handshake.
pub const HANDSHAKE_PAIRING: u8 = 0x00;
pub const HANDSHAKE_PAIRED: u8 = 0x01;

/// Read a plaintext protocol message from a TCP stream.
/// Used during handshake phase before Noise encryption is established.
pub async fn read_raw_message(stream: &mut TcpStream) -> Result<Message> {
    let msg_type_byte = stream.read_u8().await?;
    let msg_type = MessageType::try_from(msg_type_byte)?;
    let len = stream.read_u32().await?;
    if len > MAX_PAYLOAD_SIZE {
        bail!("payload too large: {} bytes", len);
    }
    let mut payload = vec![0u8; len as usize];
    stream.read_exact(&mut payload).await?;
    Ok(Message {
        msg_type,
        payload,
    })
}

/// Write a plaintext protocol message to a TCP stream.
pub async fn write_raw_message(stream: &mut TcpStream, msg: &Message) -> Result<()> {
    let encoded = msg.encode();
    stream.write_all(&encoded).await?;
    stream.flush().await?;
    Ok(())
}
