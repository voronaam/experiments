/// Minimal PNG encoder (RGBA8, uncompressed deflate blocks) for snapshots.
module codecity.gui.png;

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

private void be32(ref ubyte[] output, uint v) pure
{
    output ~= [cast(ubyte)(v >> 24), cast(ubyte)(v >> 16), cast(ubyte)(v >> 8), cast(ubyte) v];
}

private void chunk(ref ubyte[] output, string kind, const(ubyte)[] data) pure
{
    be32(output, cast(uint) data.length);
    const start = output.length;
    output ~= cast(const(ubyte)[]) kind;
    output ~= data;
    be32(output, crc32(output[start .. $]));
}

/// `rgba` rows top to bottom.
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
    ubyte[] z = [0x78, 0x01];
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
    ihdr ~= [8, 6, 0, 0, 0];
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
