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

    #[allow(dead_code)]
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
#[allow(dead_code)]
const MAX_PAYLOAD_SIZE: u32 = 1_048_576;

/// Handshake type markers sent before the Noise handshake.
pub const HANDSHAKE_PAIRING: u8 = 0x00;
pub const HANDSHAKE_PAIRED: u8 = 0x01;

/// Read a plaintext protocol message from a TCP stream.
/// Used during handshake phase before Noise encryption is established.
#[allow(dead_code)]
pub async fn read_raw_message(stream: &mut TcpStream) -> Result<Message> {
    let msg_type_byte = stream.read_u8().await?;
    let msg_type = MessageType::try_from(msg_type_byte)?;
    let len = stream.read_u32().await?;
    if len > MAX_PAYLOAD_SIZE {
        bail!("payload too large: {} bytes", len);
    }
    let mut payload = vec![0u8; len as usize];
    stream.read_exact(&mut payload).await?;
    Ok(Message { msg_type, payload })
}

/// Write a plaintext protocol message to a TCP stream.
#[allow(dead_code)]
pub async fn write_raw_message(stream: &mut TcpStream, msg: &Message) -> Result<()> {
    let encoded = msg.encode();
    stream.write_all(&encoded).await?;
    stream.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_message_type_roundtrip() {
        let types = [
            (0x01u8, MessageType::ClipboardSend),
            (0x02, MessageType::ClipboardAck),
            (0x03, MessageType::Ping),
            (0x04, MessageType::Pong),
            (0x05, MessageType::DeviceInfo),
            (0x06, MessageType::Error),
        ];
        for (byte, expected) in types {
            let parsed = MessageType::try_from(byte).unwrap();
            assert_eq!(parsed, expected);
            assert_eq!(parsed as u8, byte);
        }
    }

    #[test]
    fn test_message_type_unknown_returns_error() {
        assert!(MessageType::try_from(0x00).is_err());
        assert!(MessageType::try_from(0x07).is_err());
        assert!(MessageType::try_from(0xFF).is_err());
    }

    #[test]
    fn test_clipboard_send_encode_decode() {
        let msg = Message::clipboard_send("hello world");
        let encoded = msg.encode();

        assert_eq!(encoded[0], 0x01);
        let len = u32::from_be_bytes([encoded[1], encoded[2], encoded[3], encoded[4]]);
        assert_eq!(len, 11);

        let decoded = Message::decode(&encoded).unwrap();
        assert_eq!(decoded.msg_type, MessageType::ClipboardSend);
        assert_eq!(decoded.payload_text().unwrap(), "hello world");
    }

    #[test]
    fn test_empty_payload_messages() {
        for msg in [Message::clipboard_ack(), Message::ping(), Message::pong()] {
            let encoded = msg.encode();
            let len = u32::from_be_bytes([encoded[1], encoded[2], encoded[3], encoded[4]]);
            assert_eq!(len, 0);
            assert_eq!(encoded.len(), 5);

            let decoded = Message::decode(&encoded).unwrap();
            assert_eq!(decoded.msg_type, msg.msg_type);
            assert!(decoded.payload.is_empty());
        }
    }

    #[test]
    fn test_device_info_json() {
        let msg = Message::device_info("My Mac");
        let decoded = Message::decode(&msg.encode()).unwrap();
        assert_eq!(decoded.msg_type, MessageType::DeviceInfo);

        let text = decoded.payload_text().unwrap();
        let json: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(json["name"], "My Mac");
    }

    #[test]
    fn test_error_message() {
        let msg = Message::error("something went wrong");
        let decoded = Message::decode(&msg.encode()).unwrap();
        assert_eq!(decoded.msg_type, MessageType::Error);
        assert_eq!(decoded.payload_text().unwrap(), "something went wrong");
    }

    #[test]
    fn test_unicode_payload() {
        let text = "Hello \u{1F44B} world \u{1F30D}";
        let msg = Message::clipboard_send(text);
        let decoded = Message::decode(&msg.encode()).unwrap();
        assert_eq!(decoded.payload_text().unwrap(), text);
    }

    #[test]
    fn test_large_payload() {
        let text = "A".repeat(10_000);
        let msg = Message::clipboard_send(&text);
        let encoded = msg.encode();
        assert_eq!(encoded.len(), 5 + 10_000);
        let decoded = Message::decode(&encoded).unwrap();
        assert_eq!(decoded.payload_text().unwrap(), text);
    }

    #[test]
    fn test_decode_too_short() {
        assert!(Message::decode(&[]).is_err());
        assert!(Message::decode(&[0x01]).is_err());
        assert!(Message::decode(&[0x01, 0, 0, 0]).is_err());
    }

    #[test]
    fn test_decode_truncated_payload() {
        // Header says 10 bytes payload but only 3 provided
        let data = [0x01, 0, 0, 0, 10, 1, 2, 3];
        assert!(Message::decode(&data).is_err());
    }

    #[test]
    fn test_decode_unknown_type() {
        let data = [0xFF, 0, 0, 0, 0];
        assert!(Message::decode(&data).is_err());
    }

    #[test]
    fn test_encode_decode_roundtrip_all_types() {
        let messages = vec![
            Message::clipboard_send("test data"),
            Message::clipboard_ack(),
            Message::ping(),
            Message::pong(),
            Message::device_info("test-device"),
            Message::error("test error"),
        ];
        for original in messages {
            let encoded = original.encode();
            let decoded = Message::decode(&encoded).unwrap();
            assert_eq!(decoded.msg_type, original.msg_type);
            assert_eq!(decoded.payload, original.payload);
        }
    }

    #[test]
    fn test_handshake_type_constants() {
        assert_eq!(HANDSHAKE_PAIRING, 0x00);
        assert_eq!(HANDSHAKE_PAIRED, 0x01);
    }
}
