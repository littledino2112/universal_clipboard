use anyhow::{Context, Result};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

use crate::crypto::Identity;

/// Persistent storage for device identity and paired devices.
pub struct DeviceStore {
    base_dir: PathBuf,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct StoredIdentity {
    private_key: String, // hex
    public_key: String,  // hex
}

#[derive(serde::Serialize, serde::Deserialize)]
struct PairedDevices {
    devices: HashMap<String, String>, // name -> public_key (hex)
}

impl DeviceStore {
    pub fn new(base_dir: PathBuf) -> Result<Self> {
        fs::create_dir_all(&base_dir)
            .with_context(|| format!("failed to create store dir: {:?}", base_dir))?;
        Ok(Self { base_dir })
    }

    /// Default store location.
    pub fn default_location() -> Result<Self> {
        let dir = directories::ProjectDirs::from("com", "uclip", "UniversalClipboard")
            .context("could not determine config directory")?;
        Self::new(dir.data_dir().to_path_buf())
    }

    fn identity_path(&self) -> PathBuf {
        self.base_dir.join("identity.json")
    }

    fn devices_path(&self) -> PathBuf {
        self.base_dir.join("paired_devices.json")
    }

    pub fn load_identity(&self) -> Result<Option<Identity>> {
        let path = self.identity_path();
        if !path.exists() {
            return Ok(None);
        }
        let data = fs::read_to_string(&path)?;
        let stored: StoredIdentity = serde_json::from_str(&data)?;
        Ok(Some(Identity {
            private_key: hex::decode(&stored.private_key)?,
            public_key: hex::decode(&stored.public_key)?,
        }))
    }

    pub fn save_identity(&self, identity: &Identity) -> Result<()> {
        let stored = StoredIdentity {
            private_key: hex::encode(&identity.private_key),
            public_key: hex::encode(&identity.public_key),
        };
        let json = serde_json::to_string_pretty(&stored)?;
        fs::write(self.identity_path(), json)?;
        Ok(())
    }

    fn load_paired_devices(&self) -> Result<PairedDevices> {
        let path = self.devices_path();
        if !path.exists() {
            return Ok(PairedDevices {
                devices: HashMap::new(),
            });
        }
        let data = fs::read_to_string(&path)?;
        let devices: PairedDevices = serde_json::from_str(&data)?;
        Ok(devices)
    }

    fn save_paired_devices(&self, devices: &PairedDevices) -> Result<()> {
        let json = serde_json::to_string_pretty(devices)?;
        fs::write(self.devices_path(), json)?;
        Ok(())
    }

    pub fn save_paired_device(&self, name: &str, public_key: &[u8]) -> Result<()> {
        let mut devices = self.load_paired_devices()?;
        devices
            .devices
            .insert(name.to_string(), hex::encode(public_key));
        self.save_paired_devices(&devices)?;
        Ok(())
    }

    pub fn find_device_by_key(&self, public_key: &[u8]) -> Result<Option<String>> {
        let devices = self.load_paired_devices()?;
        let key_hex = hex::encode(public_key);
        for (name, stored_key) in &devices.devices {
            if *stored_key == key_hex {
                return Ok(Some(name.clone()));
            }
        }
        Ok(None)
    }

    pub fn list_paired_devices(&self) -> Result<Vec<(String, String)>> {
        let devices = self.load_paired_devices()?;
        Ok(devices.devices.into_iter().collect())
    }

    pub fn remove_paired_device(&self, name: &str) -> Result<bool> {
        let mut devices = self.load_paired_devices()?;
        let removed = devices.devices.remove(name).is_some();
        if removed {
            self.save_paired_devices(&devices)?;
        }
        Ok(removed)
    }
}
