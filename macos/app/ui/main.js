const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const statusDot = document.getElementById("statusDot");
const statusText = document.getElementById("statusText");
const deviceName = document.getElementById("deviceName");
const pairingCode = document.getElementById("pairingCode");
const connectionSection = document.getElementById("connectionSection");
const connectedDevice = document.getElementById("connectedDevice");
const devicesList = document.getElementById("devicesList");
const portInfo = document.getElementById("portInfo");

async function loadStatus() {
  try {
    const status = await invoke("get_status");
    pairingCode.textContent = status.pairing_code;
    deviceName.textContent = status.device_name;
    portInfo.textContent = `Port ${status.port}`;

    if (status.connected_device) {
      statusDot.className = "status-dot connected";
      statusText.textContent = "Connected";
      connectionSection.style.display = "";
      connectedDevice.textContent = status.connected_device;
    } else {
      statusDot.className = "status-dot";
      statusText.textContent = "Waiting for connection";
      connectionSection.style.display = "none";
    }
  } catch (e) {
    console.error("Failed to load status:", e);
  }
}

async function loadDevices() {
  try {
    const devices = await invoke("get_devices");
    if (devices.length === 0) {
      devicesList.innerHTML = '<div class="empty-state">No paired devices</div>';
      return;
    }
    devicesList.innerHTML = devices
      .map(
        (d) => `
      <div class="device-item">
        <div>
          <div class="device-item-name">${escapeHtml(d.name)}</div>
          <div class="device-item-key">${escapeHtml(d.key_prefix)}...</div>
        </div>
        <button class="unpair-btn" data-name="${escapeAttr(d.name)}">Unpair</button>
      </div>
    `
      )
      .join("");

    devicesList.querySelectorAll(".unpair-btn").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const name = btn.dataset.name;
        await invoke("unpair_device", { name });
        loadDevices();
      });
    });
  } catch (e) {
    console.error("Failed to load devices:", e);
  }
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str;
  return div.innerHTML;
}

function escapeAttr(str) {
  return str.replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

listen("server-event", (event) => {
  const data = event.payload;
  switch (data.type) {
    case "ServerStarted":
      pairingCode.textContent = data.data.pairing_code;
      portInfo.textContent = `Port ${data.data.port}`;
      statusDot.className = "status-dot";
      statusText.textContent = "Waiting for connection";
      break;
    case "DeviceConnected":
      statusDot.className = "status-dot connected";
      statusText.textContent = "Connected";
      connectionSection.style.display = "";
      connectedDevice.textContent = data.data.name;
      loadDevices();
      break;
    case "DeviceDisconnected":
      statusDot.className = "status-dot";
      statusText.textContent = "Waiting for connection";
      connectionSection.style.display = "none";
      break;
    case "ClipboardReceived":
      // Brief visual feedback could be added here
      break;
    case "HandshakeFailed":
      statusDot.className = "status-dot error";
      statusText.textContent = "Handshake failed";
      setTimeout(() => {
        statusDot.className = "status-dot";
        statusText.textContent = "Waiting for connection";
      }, 3000);
      break;
  }
});

// Initial load
loadStatus();
loadDevices();
