//! Minimal PNG encoder (RGBA8, uncompressed deflate blocks) for snapshots — avoids an image-library dependency.

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

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for chunk in data.chunks(5552) {
        for &x in chunk {
            a += x as u32;
            b += a;
        }
        a %= 65521;
        b %= 65521;
    }
    (b << 16) | a
}

fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    out.extend((data.len() as u32).to_be_bytes());
    let start = out.len();
    out.extend(kind);
    out.extend(data);
    let crc = crc32(&out[start..]);
    out.extend(crc.to_be_bytes());
}

/// `rgba` rows top to bottom.
pub fn encode(width: u32, height: u32, rgba: &[u8]) -> Vec<u8> {
    let row = width as usize * 4;
    let mut raw = Vec::with_capacity((row + 1) * height as usize);
    for y in 0..height as usize {
        raw.push(0); // filter: none
        raw.extend(&rgba[y * row..(y + 1) * row]);
    }
    let mut z = vec![0x78, 0x01];
    let mut blocks = raw.chunks(65535).peekable();
    while let Some(block) = blocks.next() {
        z.push(if blocks.peek().is_none() { 1 } else { 0 });
        let len = block.len() as u16;
        z.extend(len.to_le_bytes());
        z.extend((!len).to_le_bytes());
        z.extend(block);
    }
    z.extend(adler32(&raw).to_be_bytes());
    let mut ihdr = vec![];
    ihdr.extend(width.to_be_bytes());
    ihdr.extend(height.to_be_bytes());
    ihdr.extend([8, 6, 0, 0, 0]);
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
}
