use anyhow::Result;
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;
use tracing::{info, warn};

use crate::crypto::{self, Identity};
use crate::events::{AppState, ServerEvent};
use crate::server::handle_session;

/// Connect to a remote Mac as the TCP/Noise initiator.
/// If `pairing_code` is Some, performs a first-time pairing (XXpsk0).
/// If `remote_public_key` is Some, performs a paired reconnection (KK).
/// After handshake, runs the same handle_session loop as the server path.
pub async fn connect(
    addr: &str,
    identity: &Identity,
    pairing_code: Option<&str>,
    remote_public_key: Option<&[u8]>,
    state: Arc<AppState>,
    cancel: CancellationToken,
) -> Result<()> {
    info!("connecting to {}", addr);
    let stream = TcpStream::connect(addr).await?;
    info!("TCP connected to {}", addr);

    let (transport, remote_name) =
        crypto::connect_to_peer(stream, identity, pairing_code, &state.store, remote_public_key)
            .await?;

    info!("authenticated with: {}", remote_name);
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

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::DeviceStore;

    #[tokio::test]
    async fn test_connect_returns_error_on_refused() {
        let store =
            DeviceStore::new(tempfile::TempDir::new().unwrap().path().to_path_buf()).unwrap();
        let identity = Identity {
            private_key: vec![0u8; 32],
            public_key: vec![0u8; 32],
        };
        let state = Arc::new(AppState::new(
            identity,
            "000000".to_string(),
            "test".to_string(),
            store,
            9876,
        ));
        let cancel = CancellationToken::new();

        let identity2 = Identity {
            private_key: vec![0u8; 32],
            public_key: vec![0u8; 32],
        };

        let result = connect(
            "127.0.0.1:1",
            &identity2,
            Some("123456"),
            None,
            state,
            cancel,
        )
        .await;

        assert!(result.is_err(), "connect to refused port should fail");
    }
}
