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

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn test_store() -> (TempDir, DeviceStore) {
        let dir = TempDir::new().unwrap();
        let store = DeviceStore::new(dir.path().to_path_buf()).unwrap();
        (dir, store)
    }

    #[test]
    fn test_identity_save_and_load() {
        let (_dir, store) = test_store();

        // No identity initially
        assert!(store.load_identity().unwrap().is_none());

        let identity = Identity {
            private_key: vec![1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16],
            public_key: vec![0xAA, 0xBB, 0xCC, 0xDD],
        };
        store.save_identity(&identity).unwrap();

        let loaded = store.load_identity().unwrap().unwrap();
        assert_eq!(loaded.private_key, identity.private_key);
        assert_eq!(loaded.public_key, identity.public_key);
    }

    #[test]
    fn test_paired_device_crud() {
        let (_dir, store) = test_store();

        // Empty initially
        let devices = store.list_paired_devices().unwrap();
        assert!(devices.is_empty());

        // Save a device
        let key = vec![0x01, 0x02, 0x03, 0x04];
        store.save_paired_device("phone-1", &key).unwrap();

        // Find by key
        let found = store.find_device_by_key(&key).unwrap();
        assert_eq!(found, Some("phone-1".to_string()));

        // Not found with different key
        let not_found = store.find_device_by_key(&[0xFF]).unwrap();
        assert!(not_found.is_none());

        // List devices
        let devices = store.list_paired_devices().unwrap();
        assert_eq!(devices.len(), 1);

        // Remove device
        assert!(store.remove_paired_device("phone-1").unwrap());
        assert!(!store.remove_paired_device("phone-1").unwrap()); // already removed

        let devices = store.list_paired_devices().unwrap();
        assert!(devices.is_empty());
    }

    #[test]
    fn test_multiple_paired_devices() {
        let (_dir, store) = test_store();

        store.save_paired_device("phone", &[1, 2, 3]).unwrap();
        store.save_paired_device("tablet", &[4, 5, 6]).unwrap();
        store.save_paired_device("laptop", &[7, 8, 9]).unwrap();

        let devices = store.list_paired_devices().unwrap();
        assert_eq!(devices.len(), 3);

        assert_eq!(
            store.find_device_by_key(&[4, 5, 6]).unwrap(),
            Some("tablet".to_string())
        );

        store.remove_paired_device("tablet").unwrap();
        assert!(store.find_device_by_key(&[4, 5, 6]).unwrap().is_none());
        assert_eq!(store.list_paired_devices().unwrap().len(), 2);
    }

    #[test]
    fn test_save_device_overwrites_existing() {
        let (_dir, store) = test_store();

        store.save_paired_device("phone", &[1, 2, 3]).unwrap();
        store.save_paired_device("phone", &[4, 5, 6]).unwrap();

        let devices = store.list_paired_devices().unwrap();
        assert_eq!(devices.len(), 1);

        // Should find with new key
        assert_eq!(
            store.find_device_by_key(&[4, 5, 6]).unwrap(),
            Some("phone".to_string())
        );
        // Should NOT find with old key
        assert!(store.find_device_by_key(&[1, 2, 3]).unwrap().is_none());
    }
}
