use anyhow::{Context, Result};
use arboard::Clipboard;
use tracing::info;

/// Write text content to the system clipboard.
pub fn set_clipboard_text(text: &str) -> Result<()> {
    let mut clipboard = Clipboard::new().context("failed to access clipboard")?;
    clipboard
        .set_text(text)
        .context("failed to write to clipboard")?;
    info!("clipboard updated ({} chars)", text.len());
    Ok(())
}

/// Read the current text content from the system clipboard.
pub fn get_clipboard_text() -> Result<Option<String>> {
    let mut clipboard = Clipboard::new().context("failed to access clipboard")?;
    match clipboard.get_text() {
        Ok(text) => Ok(Some(text)),
        Err(arboard::Error::ContentNotAvailable) => Ok(None),
        Err(e) => Err(e).context("failed to read clipboard"),
    }
}
