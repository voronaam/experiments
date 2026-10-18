/// Minimal PNG encoder (RGBA8, uncompressed deflate blocks) for the desktop viewer's snapshots.
///
/// The file is the 8-byte signature followed by three chunks: `IHDR` (size and pixel format), one `IDAT` holding a
/// zlib stream, and `IEND`. Every chunk is `length (4 bytes BE) | type (4 ASCII) | data | CRC-32 of type + data`. The
/// zlib stream wraps the filtered scanlines in "stored" (type 0, uncompressed) deflate blocks, so no compressor is
/// needed: the price is a file roughly as large as the raw pixels (≈ 4 bytes per pixel). Each scanline is prefixed
/// with filter type 0 (none). See RFC 2083 (PNG), RFC 1950 (zlib) and RFC 1951 §3.2.4 (stored blocks).
module codecity.gui.png;

/// The CRC-32 PNG chunks need (reflected polynomial 0xEDB88320, initial value and final XOR 0xFFFFFFFF), computed
/// bit by bit; a lookup table is not worth it for one snapshot.
private uint crc32(const(ubyte)[] data) pure
{
    uint crc = 0xffff_ffff;
    foreach (b; data)
    {
        crc ^= b;
        foreach (_; 0 .. 8) crc = (crc & 1) ? (crc >> 1) ^ 0xedb8_8320 : crc >> 1;
    }
    return ~crc;
}

/// The Adler-32 checksum that ends a zlib stream (RFC 1950): two running sums modulo 65521, the largest prime below
/// 2^16, over the uncompressed data.
private uint adler32(const(ubyte)[] data) pure
{
    uint a = 1, b = 0;
    foreach (x; data)
    {
        a = (a + x) % 65_521;
        b = (b + a) % 65_521;
    }
    return (b << 16) | a;
}

/// Appends `v` in big-endian (network) byte order, as PNG requires for all integers.
private void be32(ref ubyte[] output, uint v) pure
{
    output ~= [cast(ubyte)(v >> 24), cast(ubyte)(v >> 16), cast(ubyte)(v >> 8), cast(ubyte) v];
}

/// Appends one chunk; the CRC covers the type and the data but not the length.
private void chunk(ref ubyte[] output, string kind, const(ubyte)[] data) pure
{
    be32(output, cast(uint) data.length);
    const start = output.length;
    output ~= cast(const(ubyte)[]) kind;
    output ~= data;
    be32(output, crc32(output[start .. $]));
}

/// Encodes a `width` × `height` image as a PNG file. `rgba` holds 4 bytes per pixel with the rows top to bottom (the
/// caller flips OpenGL's bottom-up read-back first).
ubyte[] encode(uint width, uint height, const(ubyte)[] rgba) pure
{
    const row = width * 4;
    ubyte[] raw;
    raw.reserve((row + 1) * height);
    foreach (y; 0 .. height)
    {
        raw ~= 0; // filter: none
        raw ~= rgba[y * row .. (y + 1) * row];
    }
    // zlib header: CM = 8 (deflate) with a 32 KiB window, FLEVEL = 0 (fastest), FCHECK making 0x7801 divisible by 31
    ubyte[] z = [0x78, 0x01];
    // Stored blocks hold at most 65535 bytes each. Each block is: a header byte whose bit 0 (BFINAL) marks the last
    // block and whose BTYPE bits are 00 (stored), then LEN and its one's complement NLEN, both little-endian, then the
    // bytes. `off == 0` forces one (empty, final) block for an empty image; the `break` stops it repeating.
    for (size_t off = 0; off < raw.length || off == 0; off += 65_535)
    {
        const len = raw.length - off < 65_535 ? raw.length - off : 65_535;
        z ~= cast(ubyte)(off + len >= raw.length ? 1 : 0);
        z ~= [cast(ubyte) len, cast(ubyte)(len >> 8), cast(ubyte) ~len, cast(ubyte)(~len >> 8)];
        z ~= raw[off .. off + len];
        if (raw.length == 0) break;
    }
    ubyte[] adler;
    be32(adler, adler32(raw));
    z ~= adler;
    ubyte[] ihdr;
    be32(ihdr, width);
    be32(ihdr, height);
    ihdr ~= [8, 6, 0, 0, 0]; // bit depth 8, colour type 6 (RGBA), deflate, filter method 0, no interlace
    ubyte[] output = [0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a];
    chunk(output, "IHDR", ihdr);
    chunk(output, "IDAT", z);
    chunk(output, "IEND", []);
    return output;
}

unittest
{
    assert(crc32(cast(const(ubyte)[]) "IEND") == 0xae42_6082);
}
