use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use tracing::info;

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
