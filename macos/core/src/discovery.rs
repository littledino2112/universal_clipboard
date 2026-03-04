use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use tracing::{debug, info, warn};

const SERVICE_TYPE: &str = "_uclip._tcp.local.";

/// Register this device as a Universal Clipboard receiver via mDNS.
pub struct DiscoveryServer {
    mdns: ServiceDaemon,
    service_fullname: String,
}

impl DiscoveryServer {
    pub fn new(port: u16, device_name: &str) -> Result<Self> {
        let mdns = ServiceDaemon::new()?;
        let host_name = format!("{}.local.", device_name.replace(' ', "-"));
        let service_info = ServiceInfo::new(SERVICE_TYPE, device_name, &host_name, "", port, None)?;
        let fullname = service_info.get_fullname().to_string();

        mdns.register(service_info)?;
        info!("mDNS: advertising {} on port {}", device_name, port);

        Ok(Self {
            mdns,
            service_fullname: fullname,
        })
    }
}

impl Drop for DiscoveryServer {
    fn drop(&mut self) {
        if let Err(e) = self.mdns.unregister(&self.service_fullname) {
            tracing::warn!("failed to unregister mDNS service: {}", e);
        }
    }
}

/// A device discovered via mDNS browsing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    pub name: String,
    pub host: String,
    pub port: u16,
}

/// Browse for Universal Clipboard peers on the local network.
pub struct DiscoveryBrowser {
    _task: tokio::task::JoinHandle<()>,
    pub devices: tokio::sync::watch::Receiver<Vec<DiscoveredDevice>>,
}

impl DiscoveryBrowser {
    pub fn new() -> Result<Self> {
        let mdns = ServiceDaemon::new()?;
        let receiver = mdns.browse(SERVICE_TYPE)?;
        let (tx, rx) = tokio::sync::watch::channel::<Vec<DiscoveredDevice>>(vec![]);

        let task = tokio::task::spawn_blocking(move || {
            let mut devices: Vec<DiscoveredDevice> = Vec::new();
            loop {
                match receiver.recv() {
                    Ok(event) => match event {
                        ServiceEvent::ServiceResolved(info) => {
                            let name = info.get_fullname().to_string();
                            let port = info.get_port();
                            let host = info
                                .get_addresses()
                                .iter()
                                .find(|a| a.is_ipv4())
                                .or_else(|| info.get_addresses().iter().next())
                                .map(|a| a.to_string())
                                .unwrap_or_default();

                            if host.is_empty() {
                                debug!("skipping resolved service with no addresses: {}", name);
                                continue;
                            }

                            // Remove any existing entry with same name before adding
                            devices.retain(|d| d.name != name);
                            devices.push(DiscoveredDevice {
                                name: name.clone(),
                                host,
                                port,
                            });
                            info!("mDNS: discovered device: {}", name);
                            let _ = tx.send(devices.clone());
                        }
                        ServiceEvent::ServiceRemoved(_, fullname) => {
                            devices.retain(|d| d.name != fullname);
                            info!("mDNS: device removed: {}", fullname);
                            let _ = tx.send(devices.clone());
                        }
                        ServiceEvent::SearchStopped(_) => {
                            info!("mDNS: browse stopped");
                            break;
                        }
                        _ => {}
                    },
                    Err(e) => {
                        warn!("mDNS browse recv error: {}", e);
                        break;
                    }
                }
            }
            // Keep mdns alive until task ends
            drop(mdns);
        });

        Ok(Self {
            _task: task,
            devices: rx,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_discovered_device_fields() {
        let device = DiscoveredDevice {
            name: "my-mac._uclip._tcp.local.".to_string(),
            host: "192.168.1.42".to_string(),
            port: 9876,
        };
        assert_eq!(device.name, "my-mac._uclip._tcp.local.");
        assert_eq!(device.host, "192.168.1.42");
        assert_eq!(device.port, 9876);
    }
}
