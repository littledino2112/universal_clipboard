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
const pasteImageBtn = document.getElementById("pasteImageBtn");
const clipboardList = document.getElementById("clipboardList");
const clipCount = document.getElementById("clipCount");
const transferProgress = document.getElementById("transferProgress");
const transferLabel = document.getElementById("transferLabel");
const transferFill = document.getElementById("transferFill");

let isConnected = false;
let isTransferActive = false;

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

function formatBytes(bytes) {
  if (bytes >= 1024 * 1024) {
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
  }
  return (bytes / 1024).toFixed(0) + " KB";
}

function renderClipboardItems(items) {
  clipCount.textContent = `${items.length}/5`;
  if (items.length === 0) {
    clipboardList.innerHTML = '<div class="empty-state">No clipboard items</div>';
    return;
  }
  clipboardList.innerHTML = items
    .map((item) => {
      const isImage = item.item_type === "image";
      const icon = isImage ? "🖼" : "";
      const sendDisabled = !isConnected || isTransferActive;
      return `
    <div class="clipboard-item ${isImage ? "clipboard-item-image" : ""}" data-id="${item.id}">
      <div class="clipboard-item-left">
        <div class="clipboard-item-preview">${icon ? icon + " " : ""}${escapeHtml(item.preview)}</div>
        <div class="clipboard-item-meta">
          <span class="clipboard-item-time">${formatTime(item.timestamp)}</span>
          ${item.sent ? '<span class="sent-badge">Sent</span>' : ""}
        </div>
      </div>
      <div class="clipboard-item-actions">
        <button class="send-btn" data-id="${item.id}" data-type="${item.item_type}" ${sendDisabled ? "disabled" : ""}>Send</button>
        <button class="delete-btn" data-id="${item.id}">&times;</button>
      </div>
    </div>
  `;
    })
    .join("");

  clipboardList.querySelectorAll(".send-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = Number(btn.dataset.id);
      const type = btn.dataset.type;
      try {
        if (type === "image") {
          await invoke("send_image_item", { id });
        } else {
          await invoke("send_clipboard_item", { id });
        }
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

pasteImageBtn.addEventListener("click", async () => {
  try {
    const items = await invoke("paste_image_from_clipboard");
    renderClipboardItems(items);
  } catch (e) {
    console.error("Failed to paste image:", e);
  }
});

function updateSendButtons() {
  clipboardList.querySelectorAll(".send-btn").forEach((btn) => {
    btn.disabled = !isConnected || isTransferActive;
  });
}

function showTransferProgress(label, percent) {
  transferProgress.classList.remove("hidden");
  transferLabel.textContent = label;
  if (percent !== null) {
    transferFill.style.width = percent + "%";
  } else {
    transferFill.style.width = "100%";
    transferFill.classList.add("indeterminate");
  }
  isTransferActive = true;
  updateSendButtons();
}

function hideTransferProgress() {
  transferProgress.classList.add("hidden");
  transferFill.style.width = "0%";
  transferFill.classList.remove("indeterminate");
  isTransferActive = false;
  updateSendButtons();
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
    case "ImageTransferProgress": {
      const sent = data.data.bytes_transferred;
      const total = data.data.bytes_total;
      const pct = Math.round((sent / total) * 100);
      showTransferProgress(`Transferring ${formatBytes(sent)} / ${formatBytes(total)}`, pct);
      break;
    }
    case "ImageReceived":
      hideTransferProgress();
      loadClipboardItems();
      break;
    case "ImageSent":
      hideTransferProgress();
      loadClipboardItems();
      break;
    case "ImageTransferFailed":
      transferLabel.textContent = "Transfer failed: " + (data.data.reason || "Unknown error");
      transferFill.style.width = "0%";
      setTimeout(hideTransferProgress, 3000);
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
