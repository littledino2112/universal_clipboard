mod clipboard;
mod crypto;
mod discovery;
mod protocol;
mod server;
mod storage;

use anyhow::Result;
use clap::{Parser, Subcommand};
use tokio::net::TcpListener;

#[derive(Parser)]
#[command(name = "uclip", about = "Universal Clipboard - P2P encrypted clipboard receiver")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Start the clipboard receiver daemon
    Listen {
        /// Port to listen on
        #[arg(short, long, default_value_t = 9876)]
        port: u16,

        /// Device name for mDNS advertisement
        #[arg(short, long, default_value = "My Mac")]
        name: String,
    },
    /// Show current pairing info
    Status,
    /// List paired devices
    Devices,
    /// Remove a paired device
    Unpair {
        /// Name of the device to unpair
        name: String,
    },
    /// Reset identity (generates new keypair, removes all pairings)
    Reset,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt::init();

    let cli = Cli::parse();
    let store = storage::DeviceStore::default_location()?;

    match cli.command {
        Commands::Listen { port, name } => {
            let identity = crypto::Identity::load_or_generate(&store)?;
            let pairing_code = crypto::generate_pairing_code();

            println!("========================================");
            println!("  Universal Clipboard Receiver");
            println!("========================================");
            println!("  Device:  {}", name);
            println!("  Key:     {}...", &identity.public_key_hex()[..16]);
            println!("  Port:    {}", port);
            println!("----------------------------------------");
            println!("  PAIRING CODE:  {}", pairing_code);
            println!("----------------------------------------");
            println!("  Enter this code on your Android device");
            println!("  to pair. Code changes on each restart.");
            println!("========================================");

            // Start mDNS advertisement
            let _discovery = discovery::DiscoveryServer::new(port, &name)?;

            // Start TCP listener
            let listener = TcpListener::bind(format!("0.0.0.0:{}", port)).await?;
            server::run_server(listener, &identity, &pairing_code, &store, &name).await?;
        }

        Commands::Status => {
            let identity = crypto::Identity::load_or_generate(&store)?;
            println!("Public key: {}", identity.public_key_hex());
            let devices = store.list_paired_devices()?;
            println!("Paired devices: {}", devices.len());
        }

        Commands::Devices => {
            let devices = store.list_paired_devices()?;
            if devices.is_empty() {
                println!("No paired devices.");
            } else {
                println!("Paired devices:");
                for (name, key) in &devices {
                    println!("  {} (key: {}...)", name, &key[..16]);
                }
            }
        }

        Commands::Unpair { name } => {
            if store.remove_paired_device(&name)? {
                println!("Unpaired device: {}", name);
            } else {
                println!("Device not found: {}", name);
            }
        }

        Commands::Reset => {
            println!("This will delete your identity and all pairings.");
            println!("Are you sure? Type 'yes' to confirm:");
            let mut input = String::new();
            std::io::stdin().read_line(&mut input)?;
            if input.trim() == "yes" {
                let dir = directories::ProjectDirs::from("com", "uclip", "UniversalClipboard")
                    .expect("could not determine config directory");
                std::fs::remove_dir_all(dir.data_dir())?;
                println!("Identity and pairings deleted.");
            } else {
                println!("Cancelled.");
            }
        }
    }

    Ok(())
}
