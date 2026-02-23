// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod commands;

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use tauri::menu::{MenuBuilder, MenuItemBuilder};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;
use tracing::info;

use uclip_core::crypto;
use uclip_core::discovery::DiscoveryServer;
use uclip_core::events::AppState;
use uclip_core::server;
use uclip_core::storage::DeviceStore;

/// Timestamp (millis) of the last tray icon click, used to suppress
/// the blur-hide that races with the tray toggle.
static LAST_TRAY_CLICK_MS: AtomicU64 = AtomicU64::new(0);

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn main() {
    tracing_subscriber::fmt::init();

    tauri::Builder::default()
        .plugin(tauri_plugin_positioner::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Focused(false) = event {
                if window.label() == "panel" {
                    let last_click = LAST_TRAY_CLICK_MS.load(Ordering::Relaxed);
                    if now_ms().saturating_sub(last_click) > 500 {
                        let _ = window.hide();
                    }
                }
            }
        })
        .setup(|app| {
            // macOS: hide from dock (accessory/agent app)
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory);

            setup_tray(app.handle())?;

            // Enable autostart on first launch
            use tauri_plugin_autostart::ManagerExt;
            let autostart = app.autolaunch();
            if !autostart.is_enabled().unwrap_or(false) {
                let _ = autostart.enable();
                info!("autostart enabled");
            }

            // Initialize core state
            let store = DeviceStore::default_location()?;
            let identity = crypto::Identity::load_or_generate(&store)?;
            let pairing_code = crypto::generate_pairing_code();
            let port = 9876u16;
            let device_name = hostname();

            info!(
                "starting with pairing code: {}, port: {}",
                pairing_code, port
            );

            let state = Arc::new(AppState::new(
                identity,
                pairing_code,
                device_name.clone(),
                store,
                port,
            ));

            // Store state in Tauri's managed state
            app.manage(state.clone());
            app.manage(CancellationToken::new());

            // Spawn event forwarder
            let app_handle = app.handle().clone();
            let mut rx = state.subscribe();
            tauri::async_runtime::spawn(async move {
                while let Ok(event) = rx.recv().await {
                    let _ = app_handle.emit("server-event", &event);
                }
            });

            // Spawn server
            let cancel = CancellationToken::new();
            let server_state = state.clone();
            tauri::async_runtime::spawn(async move {
                // Start mDNS
                let _discovery =
                    DiscoveryServer::new(port, &device_name).expect("failed to start mDNS");

                let listener = TcpListener::bind(format!("0.0.0.0:{}", port))
                    .await
                    .expect("failed to bind TCP listener");

                if let Err(e) = server::run_server(listener, server_state, cancel).await {
                    tracing::error!("server error: {}", e);
                }
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_status,
            commands::get_devices,
            commands::unpair_device,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

fn setup_tray(app: &AppHandle) -> Result<(), Box<dyn std::error::Error>> {
    let icon_bytes = include_bytes!("../icons/icon.png");
    let icon = app
        .default_window_icon()
        .cloned()
        .or_else(|| tauri::image::Image::from_bytes(icon_bytes).ok())
        .expect("failed to load tray icon");

    // Right-click context menu
    let quit = MenuItemBuilder::with_id("quit", "Quit Universal Clipboard").build(app)?;
    let menu = MenuBuilder::new(app).item(&quit).build()?;

    let _tray = TrayIconBuilder::new()
        .icon(icon)
        .icon_as_template(true)
        .tooltip("Universal Clipboard")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| {
            if event.id().as_ref() == "quit" {
                app.exit(0);
            }
        })
        .on_tray_icon_event(|tray, event| {
            if let tauri::tray::TrayIconEvent::Click {
                button: tauri::tray::MouseButton::Left,
                button_state: tauri::tray::MouseButtonState::Down,
                ..
            } = event
            {
                LAST_TRAY_CLICK_MS.store(now_ms(), Ordering::Relaxed);
                toggle_panel(tray.app_handle());
            }
        })
        .build(app)?;

    Ok(())
}

fn toggle_panel(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("panel") {
        if window.is_visible().unwrap_or(false) {
            let _ = window.hide();
        } else {
            let _ = window.show();
            let _ = window.set_focus();
        }
    } else {
        let _window = WebviewWindowBuilder::new(app, "panel", WebviewUrl::default())
            .title("Universal Clipboard")
            .inner_size(320.0, 420.0)
            .resizable(false)
            .decorations(false)
            .always_on_top(true)
            .visible(true)
            .focused(true)
            .build()
            .expect("failed to create panel window");
    }
}

fn hostname() -> String {
    #[cfg(target_os = "macos")]
    {
        use std::process::Command;
        Command::new("scutil")
            .arg("--get")
            .arg("ComputerName")
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "My Mac".to_string())
    }
    #[cfg(not(target_os = "macos"))]
    {
        "My Mac".to_string()
    }
}
