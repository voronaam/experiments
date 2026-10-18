//! Minimal PNG encoder (RGBA8, uncompressed deflate blocks) for snapshots — avoids an image-library dependency.
//!
//! Used only by the GUI's snapshot command (`App::snapshot` in `gui/mod.rs`), which reads the rendered frame back
//! from the GPU and writes it to `~/Pictures` (or the working directory). The file is laid out as
//!
//! ```text
//!   PNG signature
//!   IHDR  width, height, 8-bit RGBA, no interlace
//!   IDAT  zlib stream: header 78 01 · stored (uncompressed) deflate blocks · Adler-32 of the raw data
//!   IEND
//! ```
//!
//! Nothing is compressed, so a snapshot is about `4 · width · height` bytes — large, but correct and trivial to
//! write. Every chunk ends with a CRC-32 over its type and data, as the PNG specification requires.

/// CRC-32 as used by PNG (and zlib/gzip): reflected polynomial `0xEDB88320`, initial value and final XOR all ones.
/// Bitwise rather than table-driven; snapshots are rare, so speed does not matter.

fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xffff_ffffu32;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 { (crc >> 1) ^ 0xedb8_8320 } else { crc >> 1 };
        }
    }
    !crc
}

/// Adler-32, the checksum that ends a zlib stream. It is computed over the uncompressed data.
fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    // 5552 is the largest number of bytes that can be summed before `b` could overflow a u32 (the same constant
    // zlib uses as NMAX), so the modulo is only needed once per chunk instead of once per byte.
    for chunk in data.chunks(5552) {
        for &x in chunk {
            a += x as u32;
            b += a;
        }
        // 65521 is the largest prime below 2^16, the modulus Adler-32 is defined with.
        a %= 65521;
        b %= 65521;
    }
    (b << 16) | a
}

/// Appends one PNG chunk: big-endian data length, 4-byte type, data, then the CRC-32 of type and data (the length
/// is not covered by the CRC).
fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    out.extend((data.len() as u32).to_be_bytes());
    let start = out.len();
    out.extend(kind);
    out.extend(data);
    let crc = crc32(&out[start..]);
    out.extend(crc.to_be_bytes());
}

/// Encodes an image as a PNG file and returns its bytes.
///
/// `rgba` holds `width * height` pixels of 4 bytes each (R, G, B, A), rows top to bottom with no padding between
/// rows. It must be at least that long, or the slicing below panics.
pub fn encode(width: u32, height: u32, rgba: &[u8]) -> Vec<u8> {
    let row = width as usize * 4;
    let mut raw = Vec::with_capacity((row + 1) * height as usize);
    for y in 0..height as usize {
        // Every scanline starts with its filter type byte.
        raw.push(0); // filter: none
        raw.extend(&rgba[y * row..(y + 1) * row]);
    }
    // zlib header: CMF 0x78 = deflate with a 32 KiB window; FLG 0x01 = "fastest" level, no preset dictionary, and
    // the check bits that make 0x7801 a multiple of 31.
    let mut z = vec![0x78, 0x01];
    // A stored deflate block holds at most 65535 bytes, because its length field is 16 bits.
    let mut blocks = raw.chunks(65535).peekable();
    while let Some(block) = blocks.next() {
        // Block header byte: bit 0 is BFINAL (set on the last block), bits 1-2 are BTYPE = 00 (stored). The rest of
        // the byte is padding, since stored blocks start on a byte boundary.
        z.push(if blocks.peek().is_none() { 1 } else { 0 });
        // LEN and its ones' complement NLEN, both little-endian (deflate's byte order, unlike PNG's big-endian).
        let len = block.len() as u16;
        z.extend(len.to_le_bytes());
        z.extend((!len).to_le_bytes());
        z.extend(block);
    }
    // An empty image (zero width or height) has no raw data and so no blocks above, but a deflate stream needs at
    // least one block: an empty, final stored block (LEN 0, NLEN 0xFFFF).
    if raw.is_empty() {
        z.extend([1, 0, 0, 0xff, 0xff]);
    }
    z.extend(adler32(&raw).to_be_bytes());
    let mut ihdr = vec![];
    ihdr.extend(width.to_be_bytes());
    ihdr.extend(height.to_be_bytes());
    // Bit depth 8, colour type 6 (truecolour with alpha), compression method 0 (deflate), filter method 0,
    // interlace method 0 (none).
    ihdr.extend([8, 6, 0, 0, 0]);
    // The fixed 8-byte PNG signature.
    let mut out = vec![0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];
    chunk(&mut out, b"IHDR", &ihdr);
    chunk(&mut out, b"IDAT", &z);
    chunk(&mut out, b"IEND", &[]);
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn crc_matches_reference() {
        assert_eq!(super::crc32(b"IEND"), 0xae42_6082);
    }

    #[test]
    fn empty_image_has_final_block() {
        let png = super::encode(4, 0, &[]);
        // IDAT data starts after the signature (8), the IHDR chunk (25) and IDAT's length and type (8).
        let idat = &png[41..41 + 11];
        assert_eq!(idat, &[0x78, 0x01, 1, 0, 0, 0xff, 0xff, 0, 0, 0, 1]);
    }
}
