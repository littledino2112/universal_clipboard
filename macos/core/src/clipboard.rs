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

/// Read image from system clipboard and return as PNG bytes.
/// Returns `Ok(None)` if no image is on the clipboard.
pub fn get_clipboard_image() -> Result<Option<Vec<u8>>> {
    let mut clipboard = Clipboard::new().context("failed to access clipboard")?;
    let image_data = match clipboard.get_image() {
        Ok(img) => img,
        Err(arboard::Error::ContentNotAvailable) => return Ok(None),
        Err(e) => return Err(e).context("failed to read image from clipboard"),
    };

    let png_bytes = encode_rgba_to_png(
        &image_data.bytes,
        image_data.width as u32,
        image_data.height as u32,
    )?;

    info!(
        "clipboard image read ({}x{}, {} bytes PNG)",
        image_data.width,
        image_data.height,
        png_bytes.len()
    );
    Ok(Some(png_bytes))
}

/// Write PNG bytes to the system clipboard as an image.
pub fn set_clipboard_image(png_bytes: &[u8]) -> Result<()> {
    let (rgba, width, height) = decode_png_to_rgba(png_bytes)?;

    let image_data = arboard::ImageData {
        width: width as usize,
        height: height as usize,
        bytes: rgba.into(),
    };

    let mut clipboard = Clipboard::new().context("failed to access clipboard")?;
    clipboard
        .set_image(image_data)
        .context("failed to write image to clipboard")?;

    info!(
        "clipboard image set ({}x{}, {} bytes PNG)",
        width,
        height,
        png_bytes.len()
    );
    Ok(())
}

/// Encode RGBA pixel data to PNG bytes.
pub fn encode_rgba_to_png(rgba: &[u8], width: u32, height: u32) -> Result<Vec<u8>> {
    use image::codecs::png::PngEncoder;
    use image::ImageEncoder;
    use std::io::Cursor;

    let mut buf = Cursor::new(Vec::new());
    let encoder = PngEncoder::new(&mut buf);
    encoder
        .write_image(rgba, width, height, image::ExtendedColorType::Rgba8)
        .context("failed to encode PNG")?;
    Ok(buf.into_inner())
}

/// Decode PNG bytes to RGBA pixel data, returning (rgba_bytes, width, height).
pub fn decode_png_to_rgba(png_bytes: &[u8]) -> Result<(Vec<u8>, u32, u32)> {
    let img = image::load_from_memory_with_format(png_bytes, image::ImageFormat::Png)
        .context("failed to decode PNG")?;
    let rgba = img.to_rgba8();
    let (width, height) = rgba.dimensions();
    Ok((rgba.into_raw(), width, height))
}

/// Get dimensions from PNG bytes without fully decoding.
pub fn png_dimensions(png_bytes: &[u8]) -> Result<(u32, u32)> {
    use std::io::Cursor;
    let reader = image::ImageReader::with_format(Cursor::new(png_bytes), image::ImageFormat::Png);
    let (w, h) = reader
        .into_dimensions()
        .context("failed to read PNG dimensions")?;
    Ok((w, h))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_png_encode_decode_roundtrip() {
        // Create a small 2x2 RGBA image
        let width = 2u32;
        let height = 2u32;
        let rgba: Vec<u8> = vec![
            255, 0, 0, 255, // red
            0, 255, 0, 255, // green
            0, 0, 255, 255, // blue
            255, 255, 0, 255, // yellow
        ];

        let png = encode_rgba_to_png(&rgba, width, height).unwrap();
        assert!(!png.is_empty());
        // PNG magic bytes
        assert_eq!(&png[..4], &[0x89, 0x50, 0x4E, 0x47]);

        let (decoded_rgba, decoded_w, decoded_h) = decode_png_to_rgba(&png).unwrap();
        assert_eq!(decoded_w, width);
        assert_eq!(decoded_h, height);
        assert_eq!(decoded_rgba, rgba);
    }

    #[test]
    fn test_set_clipboard_image_invalid_png() {
        let garbage = vec![0x00, 0x01, 0x02, 0x03, 0xFF];
        let result = decode_png_to_rgba(&garbage);
        assert!(result.is_err());
    }

    #[test]
    fn test_png_dimensions() {
        let rgba = vec![0u8; 4 * 3 * 5]; // 3x5 image
        let png = encode_rgba_to_png(&rgba, 3, 5).unwrap();
        let (w, h) = png_dimensions(&png).unwrap();
        assert_eq!(w, 3);
        assert_eq!(h, 5);
    }
}
