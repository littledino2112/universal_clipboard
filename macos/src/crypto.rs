use anyhow::{bail, Context, Result};
use hkdf::Hkdf;
use rand::Rng;
use sha2::Sha256;
use snow::{Builder, HandshakeState, TransportState};
use std::path::PathBuf;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tracing::{debug, info};

use crate::protocol::{HANDSHAKE_PAIRED, HANDSHAKE_PAIRING};
use crate::storage::DeviceStore;

/// Noise protocol pattern for initial pairing (with pre-shared key).
const NOISE_PATTERN_PAIRING: &str = "Noise_XXpsk0_25519_ChaChaPoly_SHA256";

/// Noise protocol pattern for reconnection (both keys known).
const NOISE_PATTERN_PAIRED: &str = "Noise_KK_25519_ChaChaPoly_SHA256";

/// Maximum Noise message size.
const MAX_NOISE_MSG_LEN: usize = 65535;

/// Derive a 32-byte PSK from a 6-digit pairing code.
pub fn derive_psk_from_code(code: &str) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(Some(b"uclip-pair-v1"), code.as_bytes());
    let mut psk = [0u8; 32];
    hk.expand(b"psk", &mut psk)
        .expect("HKDF expand should not fail for 32 bytes");
    psk
}

/// Generate a random 6-digit pairing code.
pub fn generate_pairing_code() -> String {
    let code: u32 = rand::thread_rng().gen_range(100_000..1_000_000);
    code.to_string()
}

/// Our static keypair, loaded from or generated into persistent storage.
pub struct Identity {
    pub private_key: Vec<u8>,
    pub public_key: Vec<u8>,
}

impl Identity {
    /// Load or generate identity from the device store.
    pub fn load_or_generate(store: &DeviceStore) -> Result<Self> {
        if let Some(identity) = store.load_identity()? {
            info!("loaded existing identity");
            Ok(identity)
        } else {
            info!("generating new identity keypair");
            let builder = Builder::new(NOISE_PATTERN_PAIRING.parse()?);
            let keypair = builder.generate_keypair()?;
            let identity = Identity {
                private_key: keypair.private.to_vec(),
                public_key: keypair.public.to_vec(),
            };
            store.save_identity(&identity)?;
            Ok(identity)
        }
    }

    pub fn public_key_hex(&self) -> String {
        hex::encode(&self.public_key)
    }
}

/// Encrypted transport wrapping a TCP stream with Noise.
pub struct NoiseTransport {
    transport: TransportState,
    stream: TcpStream,
}

impl NoiseTransport {
    /// Send an encrypted message.
    pub async fn send(&mut self, plaintext: &[u8]) -> Result<()> {
        let mut buf = vec![0u8; MAX_NOISE_MSG_LEN];
        let len = self
            .transport
            .write_message(plaintext, &mut buf)
            .context("noise encrypt failed")?;
        // Send length-prefixed ciphertext
        self.stream.write_u16(len as u16).await?;
        self.stream.write_all(&buf[..len]).await?;
        self.stream.flush().await?;
        Ok(())
    }

    /// Receive and decrypt a message.
    pub async fn recv(&mut self) -> Result<Vec<u8>> {
        let len = self.stream.read_u16().await? as usize;
        if len > MAX_NOISE_MSG_LEN {
            bail!("noise message too large: {} bytes", len);
        }
        let mut ciphertext = vec![0u8; len];
        self.stream.read_exact(&mut ciphertext).await?;
        let mut plaintext = vec![0u8; MAX_NOISE_MSG_LEN];
        let plain_len = self
            .transport
            .read_message(&ciphertext, &mut plaintext)
            .context("noise decrypt failed")?;
        Ok(plaintext[..plain_len].to_vec())
    }

    /// Send a protocol message (encode then encrypt).
    pub async fn send_message(&mut self, msg: &crate::protocol::Message) -> Result<()> {
        let encoded = msg.encode();
        self.send(&encoded).await
    }

    /// Receive and decode a protocol message.
    pub async fn recv_message(&mut self) -> Result<crate::protocol::Message> {
        let data = self.recv().await?;
        crate::protocol::Message::decode(&data)
    }

    /// Get the inner TCP stream reference (for shutdown).
    pub fn stream_mut(&mut self) -> &mut TcpStream {
        &mut self.stream
    }
}

/// Perform a Noise XXpsk0 handshake as the responder (receiver/macOS side).
/// Returns the transport and the remote's static public key.
pub async fn handshake_pairing_responder(
    mut stream: TcpStream,
    identity: &Identity,
    pairing_code: &str,
) -> Result<(NoiseTransport, Vec<u8>)> {
    let psk = derive_psk_from_code(pairing_code);

    let builder = Builder::new(NOISE_PATTERN_PAIRING.parse()?)
        .local_private_key(&identity.private_key)
        .psk(0, &psk);
    let mut handshake = builder.build_responder()?;
    let mut buf = vec![0u8; MAX_NOISE_MSG_LEN];

    // <- e (read message 1 from initiator)
    debug!("pairing: waiting for message 1");
    let len = stream.read_u16().await? as usize;
    let mut msg = vec![0u8; len];
    stream.read_exact(&mut msg).await?;
    handshake.read_message(&msg, &mut buf)?;

    // -> e, ee, s, es (send message 2)
    debug!("pairing: sending message 2");
    let len = handshake.write_message(&[], &mut buf)?;
    stream.write_u16(len as u16).await?;
    stream.write_all(&buf[..len]).await?;
    stream.flush().await?;

    // <- s, se (read message 3)
    debug!("pairing: waiting for message 3");
    let len = stream.read_u16().await? as usize;
    let mut msg = vec![0u8; len];
    stream.read_exact(&mut msg).await?;
    handshake.read_message(&msg, &mut buf)?;

    let remote_static = handshake
        .get_remote_static()
        .context("no remote static key after handshake")?
        .to_vec();

    info!(
        "pairing handshake complete, remote key: {}",
        hex::encode(&remote_static)
    );

    let transport = handshake.into_transport_mode()?;
    Ok((NoiseTransport { transport, stream }, remote_static))
}

/// Perform a Noise KK handshake as the responder for a paired device.
pub async fn handshake_paired_responder(
    mut stream: TcpStream,
    identity: &Identity,
    remote_static_key: &[u8],
) -> Result<NoiseTransport> {
    let builder = Builder::new(NOISE_PATTERN_PAIRED.parse()?)
        .local_private_key(&identity.private_key)
        .remote_public_key(remote_static_key);
    let mut handshake = builder.build_responder()?;
    let mut buf = vec![0u8; MAX_NOISE_MSG_LEN];

    // <- e, es, ss (read message 1)
    debug!("paired: waiting for message 1");
    let len = stream.read_u16().await? as usize;
    let mut msg = vec![0u8; len];
    stream.read_exact(&mut msg).await?;
    handshake.read_message(&msg, &mut buf)?;

    // -> e, ee, se (send message 2)
    debug!("paired: sending message 2");
    let len = handshake.write_message(&[], &mut buf)?;
    stream.write_u16(len as u16).await?;
    stream.write_all(&buf[..len]).await?;
    stream.flush().await?;

    info!("paired handshake complete");
    let transport = handshake.into_transport_mode()?;
    Ok(NoiseTransport { transport, stream })
}

/// Determine the handshake type and dispatch accordingly.
pub async fn accept_connection(
    mut stream: TcpStream,
    identity: &Identity,
    pairing_code: &str,
    store: &DeviceStore,
) -> Result<(NoiseTransport, String)> {
    // Read 1-byte handshake type marker
    let handshake_type = stream.read_u8().await?;

    match handshake_type {
        HANDSHAKE_PAIRING => {
            info!("incoming pairing request");
            let (transport, remote_key) =
                handshake_pairing_responder(stream, identity, pairing_code).await?;
            let device_name = format!("device-{}", hex::encode(&remote_key[..4]));
            store.save_paired_device(&device_name, &remote_key)?;
            Ok((transport, device_name))
        }
        HANDSHAKE_PAIRED => {
            // Read the remote static public key (32 bytes) to identify the device
            let mut remote_key = vec![0u8; 32];
            stream.read_exact(&mut remote_key).await?;

            if let Some(device_name) = store.find_device_by_key(&remote_key)? {
                info!("incoming connection from paired device: {}", device_name);
                let transport =
                    handshake_paired_responder(stream, identity, &remote_key).await?;
                Ok((transport, device_name))
            } else {
                bail!(
                    "unknown device with key: {}",
                    hex::encode(&remote_key)
                );
            }
        }
        _ => bail!("unknown handshake type: 0x{:02x}", handshake_type),
    }
}
