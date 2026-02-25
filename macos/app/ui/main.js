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
const pasteBtn = document.getElementById("pasteBtn");
const clipboardList = document.getElementById("clipboardList");
const clipCount = document.getElementById("clipCount");

let isConnected = false;

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
      isConnected = true;
    } else {
      statusDot.className = "status-dot";
      statusText.textContent = "Waiting for connection";
      connectionSection.style.display = "none";
      isConnected = false;
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

function formatTime(epochMs) {
  const d = new Date(epochMs);
  const h = String(d.getHours()).padStart(2, "0");
  const m = String(d.getMinutes()).padStart(2, "0");
  const s = String(d.getSeconds()).padStart(2, "0");
  return `${h}:${m}:${s}`;
}

function renderClipboardItems(items) {
  clipCount.textContent = `${items.length}/5`;
  if (items.length === 0) {
    clipboardList.innerHTML = '<div class="empty-state">No clipboard items</div>';
    return;
  }
  clipboardList.innerHTML = items
    .map(
      (item) => `
    <div class="clipboard-item" data-id="${item.id}">
      <div class="clipboard-item-left">
        <div class="clipboard-item-preview">${escapeHtml(item.preview)}</div>
        <div class="clipboard-item-meta">
          <span class="clipboard-item-time">${formatTime(item.timestamp)}</span>
          ${item.sent ? '<span class="sent-badge">Sent</span>' : ""}
        </div>
      </div>
      <div class="clipboard-item-actions">
        <button class="send-btn" data-id="${item.id}" ${!isConnected ? "disabled" : ""}>Send</button>
        <button class="delete-btn" data-id="${item.id}">&times;</button>
      </div>
    </div>
  `
    )
    .join("");

  clipboardList.querySelectorAll(".send-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = Number(btn.dataset.id);
      try {
        await invoke("send_clipboard_item", { id });
        loadClipboardItems();
      } catch (e) {
        console.error("Failed to send clipboard item:", e);
      }
    });
  });

  clipboardList.querySelectorAll(".delete-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = Number(btn.dataset.id);
      try {
        const updated = await invoke("remove_clipboard_item", { id });
        renderClipboardItems(updated);
      } catch (e) {
        console.error("Failed to delete clipboard item:", e);
      }
    });
  });
}

async function loadClipboardItems() {
  try {
    const items = await invoke("get_clipboard_items");
    renderClipboardItems(items);
  } catch (e) {
    console.error("Failed to load clipboard items:", e);
  }
}

pasteBtn.addEventListener("click", async () => {
  try {
    const items = await invoke("paste_clipboard");
    renderClipboardItems(items);
  } catch (e) {
    console.error("Failed to paste clipboard:", e);
  }
});

function updateSendButtons() {
  clipboardList.querySelectorAll(".send-btn").forEach((btn) => {
    btn.disabled = !isConnected;
  });
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
      isConnected = true;
      updateSendButtons();
      loadDevices();
      break;
    case "DeviceDisconnected":
      statusDot.className = "status-dot";
      statusText.textContent = "Waiting for connection";
      connectionSection.style.display = "none";
      isConnected = false;
      updateSendButtons();
      break;
    case "ClipboardReceived":
      break;
    case "ClipboardSent":
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
loadClipboardItems();
