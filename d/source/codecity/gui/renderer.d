/// OpenGL 3.3 renderer: every building and district is one instance of a unit box (a handful of draw calls for any
/// city), lit with a sun + ambient model, soft PCF shadow mapping, and the night-time glow / office-window shader.
///
/// The renderer knows nothing about types or metrics: `codecity.gui.app` turns the city model into flat arrays of
/// `Instance`s (one per building, district, particle, …) and the arc mesh, uploads them through the `set*` methods
/// whenever they change, and calls `render` once per frame. Picking is not done here but on the CPU by ray-casting
/// against the same boxes (`App.pick` with `codecity.gui.math.rayBox`).
///
/// Each frame:
/// ---
/// 1. shadow pass (only when the districts/buildings changed): depth of districts + buildings, seen from the sun,
///    into a 4096² depth texture
/// 2. lit pass, opaque, back faces culled:   ground, districts (no night effects), buildings (glow + windows)
/// 3. unlit pass, opaque, no culling:        dependency arcs (mesh), particles on the arcs, PR beacon gems
/// 4. blended, no depth writes:              the translucent selection marker box
/// ---
/// ImGui is drawn afterwards by `runGui`, on top of all this.
///
/// Shapes and GL state: every shape is one `InstancedShape` (a VAO with its per-vertex buffer and a per-instance
/// buffer). All but the beacons share the unit box from `boxVertices`; a building is that box translated to its lot
/// and scaled to (side, height, side). The renderer leaves depth testing enabled and blending enabled when it returns;
/// ImGui's OpenGL3 backend sets up the state it needs itself.
///
/// Colours: instance colours are sRGB in 0..1. The lit shader converts them to linear light, lights them, and converts
/// back (gamma 2.2) because the default framebuffer is not an sRGB one; the unlit shaders pass them through as they
/// are. Lighting uniforms (`Lighting.ambient` / `sun`) are expected in linear light.
module codecity.gui.renderer;

import codecity.gui.gl;
import codecity.gui.math;
import std.conv : to;
import std.math : PI;
import std.string : toStringz;

// The shaders are D token strings (`q{...}`), which must consist of valid D tokens; GLSL fits that well enough.
// `layout(location = N)` must agree with the attribute numbers `InstancedShape` and `Renderer.this` set up:
// 0 = vertex position, 1 = normal (or the arc mesh's colour), 2 = instance offset, 3 = instance scale,
// 4 = instance colour.

/// Lit vertex shader: places one vertex of the unit box for one instance. The box has its base at y = 0, so
/// `iOffset` is the centre of the box's bottom face and `iScale` its (width, height, depth); `world = iOffset + aPos *
/// iScale`. Instances are only scaled and translated, never rotated, so the box's normals stay valid in world space
/// and are passed through unchanged (non-uniform scale does not affect axis-aligned normals). The vertex is also
/// projected into the sun's clip space (`vLight`) for the shadow lookup.
private enum litVertex = q{
    #version 330 core
    layout(location = 0) in vec3 aPos;
    layout(location = 1) in vec3 aNormal;
    layout(location = 2) in vec3 iOffset;
    layout(location = 3) in vec3 iScale;
    layout(location = 4) in vec4 iColor;
    uniform mat4 uViewProj;
    uniform mat4 uLightViewProj;
    out vec3 vPos;
    out vec3 vNormal;
    out vec4 vColor;
    out vec4 vLight;
    void main() {
        vec3 world = iOffset + aPos * iScale;
        vPos = world;
        vNormal = aNormal;
        vColor = iColor;
        vLight = uLightViewProj * vec4(world, 1.0);
        gl_Position = uViewProj * vec4(world, 1.0);
    }
};

/// Lit fragment shader.
///
/// `shadow()` returns the lit fraction 0..1. It maps the light-space position from NDC (-1..1) to texture/depth
/// coordinates (0..1); anything outside the shadow map's box is treated as lit. It then takes 3 × 3 samples one texel
/// apart (percentage-closer filtering): with a `sampler2DShadow`, every `texture()` call compares the fragment's depth
/// (minus a bias of 0.0012 against shadow acne) with the stored depth, and because the texture uses `GL_LINEAR`
/// filtering most hardware also blends the comparisons of the 2 × 2 nearest texels, so the 9 taps give a soft edge.
///
/// `main()`:
/// - `base`: the instance colour converted from sRGB to linear light (gamma 2.2).
/// - Lambert diffuse from the sun (`uSunDir` is the direction the light travels, hence `L = -uSunDir`). Faces turned
///   away from the sun skip the shadow lookup; `uShadows` switches shadows off entirely.
/// - Blinn-Phong specular (half vector `L + V`, exponent 32, strength 0.12), tinted by the sun, also shadowed.
/// - Ambient sky light scaled by `hemi`: 1.0 on roofs, 0.72 on walls, 0.44 on the (invisible) undersides.
/// - `uGlow` (night only, buildings only) adds a fraction of the building's own colour as emission, unlit.
/// - `uWindows` (night only, buildings only) paints office windows on walls (`abs(n.y) < 0.5`). The wall is divided
///   into a world-aligned grid of cells 0.42 wide and 0.5 high, using world `z` along walls that face ±x and world `x`
///   along walls that face ±z, so neighbouring buildings line up. The window is the middle of the cell (22–78 % of
///   its width, 28–72 % of its height). `rnd` is the classic `fract(sin(dot(...)) * 43758.5453)` hash of the cell,
///   offset by the wall's normal so opposite walls differ. About 42 % of windows are lit (`rnd >= 0.58`), none below
///   y = 0.9 (the ground floor, counted from the ground plane, not from the building's base). Most lit windows are
///   warm yellow, the brightest ones (`rnd >= 0.9`) cool white; brightness varies between 0.6 and 1.0 with `rnd`.
/// - The result is converted back to sRGB. Alpha is passed through but this pass does not blend.
private enum litFragment = q{
    #version 330 core
    in vec3 vPos;
    in vec3 vNormal;
    in vec4 vColor;
    in vec4 vLight;
    uniform vec3 uSunDir;
    uniform vec3 uSunColor;
    uniform vec3 uAmbient;
    uniform vec3 uCamera;
    uniform float uGlow;
    uniform float uWindows;
    uniform float uShadows;
    uniform sampler2DShadow uShadowMap;
    out vec4 outColor;

    float shadow() {
        vec3 p = vLight.xyz / vLight.w * 0.5 + 0.5;
        if (p.z > 1.0 || p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) return 1.0;
        vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
        float s = 0.0;
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                s += texture(uShadowMap, vec3(p.xy + vec2(x, y) * texel, p.z - 0.0012));
        return s / 9.0;
    }

    void main() {
        vec3 n = normalize(vNormal);
        vec3 base = pow(vColor.rgb, vec3(2.2));
        vec3 L = -normalize(uSunDir);
        float diffuse = max(dot(n, L), 0.0);
        float lit = uShadows > 0.5 && diffuse > 0.0 ? shadow() : 1.0;
        vec3 V = normalize(uCamera - vPos);
        float spec = pow(max(dot(n, normalize(L + V)), 0.0), 32.0) * 0.12;
        float hemi = 0.72 + 0.28 * n.y; // tops a little brighter than walls, like sky light
        vec3 color = base * (uAmbient * hemi + uSunColor * diffuse * lit) + uSunColor * spec * lit;
        color += base * uGlow;
        if (uWindows > 0.0 && abs(n.y) < 0.5) {
            // a world-aligned grid of office windows; a hash of the cell decides which ones are lit
            float u = abs(n.x) > 0.5 ? vPos.z : vPos.x;
            vec2 cell = floor(vec2(u / 0.42, vPos.y / 0.5));
            vec2 f = fract(vec2(u / 0.42, vPos.y / 0.5));
            float win = step(0.22, f.x) * step(f.x, 0.78) * step(0.28, f.y) * step(f.y, 0.72);
            float rnd = fract(sin(dot(cell + n.xz * 17.0, vec2(12.9898, 78.233))) * 43758.5453);
            float on = step(0.58, rnd) * step(0.9, vPos.y);
            vec3 warm = mix(vec3(1.0, 0.72, 0.38), vec3(0.75, 0.88, 1.0), step(0.9, rnd));
            color += warm * win * on * uWindows * (0.6 + 0.4 * rnd);
        }
        outColor = vec4(pow(color, vec3(1.0 / 2.2)), vColor.a);
    }
};

/// Shadow pass: only the position matters (no colour attachment, empty fragment shader); depth is written by the
/// fixed-function stage. `uViewProj` is the sun's orthographic view-projection.
private enum depthVertex = q{
    #version 330 core
    layout(location = 0) in vec3 aPos;
    layout(location = 2) in vec3 iOffset;
    layout(location = 3) in vec3 iScale;
    uniform mat4 uViewProj;
    void main() { gl_Position = uViewProj * vec4(iOffset + aPos * iScale, 1.0); }
};

private enum depthFragment = q{
    #version 330 core
    void main() {}
};

/// Unlit instanced shapes (arc particles, PR beacons): flat instance colour, no lighting, no normals needed (the gem
/// has none).
private enum unlitInstancedVertex = q{
    #version 330 core
    layout(location = 0) in vec3 aPos;
    layout(location = 2) in vec3 iOffset;
    layout(location = 3) in vec3 iScale;
    layout(location = 4) in vec4 iColor;
    uniform mat4 uViewProj;
    out vec4 vColor;
    void main() { vColor = iColor; gl_Position = uViewProj * vec4(iOffset + aPos * iScale, 1.0); }
};

/// The dependency-arc mesh: plain world-space triangles with a per-vertex colour (attribute 1), no instancing.
private enum meshVertex = q{
    #version 330 core
    layout(location = 0) in vec3 aPos;
    layout(location = 1) in vec4 aColor;
    uniform mat4 uViewProj;
    out vec4 vColor;
    void main() { vColor = aColor; gl_Position = uViewProj * vec4(aPos, 1.0); }
};

/// Shared fragment shader of the two unlit programs: the interpolated colour as is.
private enum unlitFragment = q{
    #version 330 core
    in vec4 vColor;
    out vec4 outColor;
    void main() { outColor = vColor; }
};

/// One instance: offset (x, y, z), scale (w, h, d), colour (r, g, b, a) — 10 floats.
///
/// Uploaded as is, so the field order and the tight packing of floats are what the attribute pointers in
/// `InstancedShape` describe (offset at float 0, scale at float 3, colour at float 6, stride `Instance.sizeof`). For
/// the box shapes, `offset` is the centre of the bottom face; for the gem, its centre. Colour is sRGB in 0..1.
struct Instance
{
    float[3] offset;
    float[3] scale;
    float[4] color;
}

/// A vertex of the arc mesh (world position, sRGB colour); 7 tightly packed floats, matching the attribute pointers
/// set up for `arcVao`.
struct ColoredVertex
{
    float[3] position;
    float[4] color;
}

/// A mesh drawn many times with `glDrawArraysInstanced`: one static vertex buffer (`vbo`) plus one dynamic buffer of
/// `Instance`s (`instances`), bound together in a VAO. The VAO records which buffer each attribute reads from, so
/// later `upload`s can replace the instance buffer's contents without touching the VAO.
private final class InstancedShape
{
    GLuint vao, vbo, instances;
    /// Vertices in the mesh, and instances in the last upload.
    GLsizei vertexCount, count;

    /// `vertices` is a flat list of triangles: (x, y, z) per vertex, followed by (nx, ny, nz) when `withNormals`.
    this(const(float)[] vertices, bool withNormals)
    {
        glGenVertexArrays(1, &vao);
        glBindVertexArray(vao);
        glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices.length * float.sizeof, vertices.ptr, GL_STATIC_DRAW);
        const stride = cast(GLsizei)((withNormals ? 6 : 3) * float.sizeof);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, 0, stride, null);
        if (withNormals)
        {
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 3, GL_FLOAT, 0, stride, cast(void*)(3 * float.sizeof));
        }
        vertexCount = cast(GLsizei)(vertices.length / (withNormals ? 6 : 3));
        glGenBuffers(1, &instances);
        glBindBuffer(GL_ARRAY_BUFFER, instances);
        // per-instance attributes: offset (location 2), scale (3), colour (4), as [location, floats, first float].
        // A divisor of 1 advances the attribute once per instance instead of once per vertex.
        foreach (spec; [[2, 3, 0], [3, 3, 3], [4, 4, 6]])
        {
            glEnableVertexAttribArray(spec[0]);
            glVertexAttribPointer(spec[0], spec[1], GL_FLOAT, 0, Instance.sizeof, cast(void*)(spec[2] * float.sizeof));
            glVertexAttribDivisor(spec[0], 1);
        }
        glBindVertexArray(0);
    }

    /// Replaces all instances. `glBufferData` reallocates the buffer each time (fine for these sizes, and simpler than
    /// tracking capacity); null / empty data makes `draw` a no-op.
    void upload(const(Instance)[] data)
    {
        glBindBuffer(GL_ARRAY_BUFFER, instances);
        glBufferData(GL_ARRAY_BUFFER, data.length * Instance.sizeof, data.ptr, GL_DYNAMIC_DRAW);
        count = cast(GLsizei) data.length;
    }

    void draw()
    {
        if (count == 0) return;
        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLES, 0, vertexCount, count);
    }
}

/// A linked vertex + fragment shader program. Compile and link errors throw with the driver's log; they happen in the
/// `Renderer` constructor, so the viewer fails to start rather than running with a missing program.
private final class Program
{
    GLuint id;
    /// Uniform locations by name, looked up once. A name the driver does not know (or optimised away) caches -1, and
    /// `glUniform*` silently ignores location -1.
    GLint[string] uniforms;

    this(string vertex, string fragment)
    {
        GLuint compile(GLenum type, string source)
        {
            auto s = glCreateShader(type);
            auto src = source.toStringz;
            glShaderSource(s, 1, &src, null);
            glCompileShader(s);
            GLint ok;
            glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
            if (!ok)
            {
                char[4096] log;
                GLsizei len;
                glGetShaderInfoLog(s, log.length, &len, log.ptr);
                throw new Exception("shader compile failed: " ~ log[0 .. len].idup);
            }
            return s;
        }
        auto vs = compile(GL_VERTEX_SHADER, vertex), fs = compile(GL_FRAGMENT_SHADER, fragment);
        id = glCreateProgram();
        glAttachShader(id, vs);
        glAttachShader(id, fs);
        glLinkProgram(id);
        GLint ok;
        glGetProgramiv(id, GL_LINK_STATUS, &ok);
        if (!ok)
        {
            char[4096] log;
            GLsizei len;
            glGetProgramInfoLog(id, log.length, &len, log.ptr);
            throw new Exception("shader link failed: " ~ log[0 .. len].idup);
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
    }

    GLint loc(string name)
    {
        if (auto l = name in uniforms) return *l;
        return uniforms[name] = glGetUniformLocation(id, name.toStringz);
    }

    void use() { glUseProgram(id); }
    void set(string n, float v) { glUniform1f(loc(n), v); }
    void set(string n, int v) { glUniform1i(loc(n), v); }
    void set(string n, Vec3 v) { glUniform3f(loc(n), v.x, v.y, v.z); }
    // column-major already, hence transpose = 0 (false)
    void set(string n, ref const Mat4 m) { glUniformMatrix4fv(loc(n), 1, 0, m.m.ptr); }
}

/// Unit box with its base at y = 0 and a 1×1 footprint centred on the origin: 36 vertices with normals.
/// Each face is two triangles (corners 0-1-2 and 0-2-3) wound counter-clockwise seen from outside, the OpenGL default
/// front face, so `GL_CULL_FACE` with `GL_BACK` removes the faces turned away from the camera. Layout per vertex:
/// x, y, z, nx, ny, nz.
private float[] boxVertices()
{
    float[] v;
    void face(float[3] n, float[3][4] c)
    {
        foreach (i; [0, 1, 2, 0, 2, 3]) v ~= c[i][] ~ n[];
    }
    enum a = -0.5f, b = 0.5f;
    face([0, 1, 0], [[a, 1, a], [a, 1, b], [b, 1, b], [b, 1, a]]);
    face([0, -1, 0], [[a, 0, a], [b, 0, a], [b, 0, b], [a, 0, b]]);
    face([1, 0, 0], [[b, 0, a], [b, 1, a], [b, 1, b], [b, 0, b]]);
    face([-1, 0, 0], [[a, 0, a], [a, 0, b], [a, 1, b], [a, 1, a]]);
    face([0, 0, 1], [[a, 0, b], [b, 0, b], [b, 1, b], [a, 1, b]]);
    face([0, 0, -1], [[a, 0, a], [a, 1, a], [b, 1, a], [b, 0, a]]);
    return v;
}

/// A faceted gem (octahedron) for the PR beacons: 1.6 tall and 1 wide, centred on the origin, positions only (it is
/// drawn unlit).
private float[] gemVertices()
{
    float[] v;
    float[3][6] p = [[0, 0.8f, 0], [0, -0.8f, 0], [0.5f, 0, 0], [-0.5f, 0, 0], [0, 0, 0.5f], [0, 0, -0.5f]];
    int[3][8] tris = [[0, 2, 4], [0, 4, 3], [0, 3, 5], [0, 5, 2], [1, 4, 2], [1, 3, 4], [1, 5, 3], [1, 2, 5]];
    foreach (t; tris) foreach (i; t) v ~= p[i][];
    return v;
}

/// The scene renderer. Must be created and used on the thread that owns the GL context, after `loadGL`.
final class Renderer
{
    private Program lit, depth, unlitInstanced, mesh;
    private InstancedShape buildings, districts, ground, particles, beacons, marker;
    private GLuint shadowFbo, shadowTex, arcVao, arcVbo;
    private GLsizei arcVertexCount;
    /// Shadow map resolution in texels per side: 16.7 M texels of 24-bit depth, typically about 64 MiB of GPU memory.
    private enum shadowSize = 4096;
    /// The sun's view-projection from the last shadow pass; the lit pass needs the same one to look up shadows.
    private Mat4 lightViewProj;
    /// Shadows are static as long as the districts and buildings are, so the shadow map is only re-rendered after
    /// they change (`setBuildings` with `geometryChanged` / `setDistricts`) or when shadows are switched back on
    /// (`invalidateShadows`). Re-uploads for colour changes alone (hover, selection, search) keep the shadow map; every
    /// frame of a height animation re-renders it.
    private bool shadowsDirty = true;
    /// Direction the sunlight travels (not necessarily normalised): downwards, towards +x and slightly towards -z.
    Vec3 sunDir = Vec3(0.55f, -1.0f, -0.3f);

    this()
    {
        lit = new Program(litVertex, litFragment);
        depth = new Program(depthVertex, depthFragment);
        unlitInstanced = new Program(unlitInstancedVertex, unlitFragment);
        mesh = new Program(meshVertex, unlitFragment);
        const box = boxVertices();
        buildings = new InstancedShape(box, true);
        districts = new InstancedShape(box, true);
        ground = new InstancedShape(box, true);
        particles = new InstancedShape(box, true);
        marker = new InstancedShape(box, true);
        beacons = new InstancedShape(gemVertices(), false);

        // Shadow map: a depth texture used as a comparison sampler (`sampler2DShadow`): `texture()` returns
        // ref <= stored (GL_LEQUAL) instead of the depth, and GL_LINEAR blends four such results. Outside the texture
        // the border depth 1 (the far plane) means "nothing in the way", so the region beyond the map is lit.
        glGenTextures(1, &shadowTex);
        glBindTexture(GL_TEXTURE_2D, shadowTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, shadowSize, shadowSize, 0, GL_DEPTH_COMPONENT, GL_FLOAT, null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        float[4] border = [1, 1, 1, 1];
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, border.ptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        // a depth-only framebuffer: no colour buffer to draw into or read from
        glGenFramebuffers(1, &shadowFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTex, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // the arc mesh: position (location 0) and colour (location 1) interleaved as `ColoredVertex`
        glGenVertexArrays(1, &arcVao);
        glBindVertexArray(arcVao);
        glGenBuffers(1, &arcVbo);
        glBindBuffer(GL_ARRAY_BUFFER, arcVbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, 0, ColoredVertex.sizeof, null);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, 0, ColoredVertex.sizeof, cast(void*)(3 * float.sizeof));
        glBindVertexArray(0);
    }

    /// `geometryChanged`: positions, heights or visibility differ from the last upload, not only colours.
    void setBuildings(const(Instance)[] data, bool geometryChanged)
    {
        buildings.upload(data);
        if (geometryChanged) shadowsDirty = true;
    }
    void setDistricts(const(Instance)[] data) { districts.upload(data); shadowsDirty = true; }
    // The ground does not cast shadows, and particles, beacons and the marker are not in the shadow pass, so these
    // do not invalidate the shadow map.
    void setGround(Instance g) { ground.upload([g]); }
    void setParticles(const(Instance)[] data) { particles.upload(data); }
    void setBeacons(const(Instance)[] data) { beacons.upload(data); }
    void setMarker(const(Instance)[] data) { marker.upload(data); }

    /// Replaces the arc mesh (a triangle list); empty hides the arcs.
    void setArcs(const(ColoredVertex)[] vertices)
    {
        glBindBuffer(GL_ARRAY_BUFFER, arcVbo);
        glBufferData(GL_ARRAY_BUFFER, vertices.length * ColoredVertex.sizeof, vertices.ptr, GL_DYNAMIC_DRAW);
        arcVertexCount = cast(GLsizei) vertices.length;
    }

    void invalidateShadows() { shadowsDirty = true; }

    /// Per-frame lighting, from the theme's `Palette`. `ambient` and `sun` are linear-light colours already multiplied
    /// by their intensity; `glow` and `windows` drive the night effects; `shadows` is the user's toggle.
    struct Lighting
    {
        Vec3 ambient, sun;
        float glow = 0, windows = 0;
        bool shadows = true;
    }

    /// Draws one frame into the default framebuffer (see the module comment for the passes).
    ///
    /// `width` / `height` are the framebuffer size in pixels (not window coordinates, which differ on HiDPI screens),
    /// `viewProj` the camera's view-projection, `camera` its world position (for specular highlights), `radius` half
    /// the city's diagonal (which sizes the shadow camera), `background` the sRGB clear colour.
    void render(int width, int height, ref const Mat4 viewProj, Vec3 camera, float radius, Vec3 background, Lighting light)
    {
        if (light.shadows && shadowsDirty)
        {
            // The sun is a directional light, so the shadow camera is orthographic. It sits 3 radii from the centre,
            // against the light's direction, looking at the origin; the box it sees is 2.3 radii wide and tall (the
            // city fits in a circle of `radius`, plus some margin) and 7 radii deep, which covers the whole city
            // whatever the sun direction. With 4096 texels over 2.3 radii, a texel is ~1/1780 of the radius.
            const d = sunDir.normalized;
            const eye = -d * (radius * 3);
            lightViewProj = Mat4.orthographic(-radius * 1.15f, radius * 1.15f, -radius * 1.15f, radius * 1.15f, 0.1f, radius * 7)
                * Mat4.lookAt(eye, Vec3(0, 0, 0), Vec3(0, 1, 0));
            glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
            glViewport(0, 0, shadowSize, shadowSize);
            glClear(GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            // push the stored depths slightly away from the light (slope factor 2, constant 4 units) so that surfaces
            // do not shadow themselves (acne); this and the shader's bias work together
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(2.0f, 4.0f);
            depth.use();
            depth.set("uViewProj", lightViewProj);
            // the ground is not drawn: it is the lowest surface, so it only receives shadows
            districts.draw();
            buildings.draw();
            glDisable(GL_POLYGON_OFFSET_FILL);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            shadowsDirty = false;
        }

        glViewport(0, 0, width, height);
        glClearColor(background.x, background.y, background.z, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glDisable(GL_BLEND);

        lit.use();
        lit.set("uViewProj", viewProj);
        lit.set("uLightViewProj", lightViewProj);
        lit.set("uSunDir", sunDir.normalized);
        lit.set("uSunColor", light.sun);
        lit.set("uAmbient", light.ambient);
        lit.set("uCamera", camera);
        lit.set("uShadows", light.shadows ? 1.0f : 0.0f);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, shadowTex);
        lit.set("uShadowMap", 0); // texture unit 0
        // ground and districts never glow or get windows; only the buildings do
        lit.set("uGlow", 0.0f);
        lit.set("uWindows", 0.0f);
        ground.draw();
        districts.draw();
        lit.set("uGlow", light.glow);
        lit.set("uWindows", light.windows);
        buildings.draw();

        // Culling is off from here on, so winding does not matter for the arc tubes, particles and gems (the gem's
        // triangles are in fact wound clockwise, i.e. inside out), and the translucent marker shows its far faces
        // through its near ones.
        glDisable(GL_CULL_FACE);
        if (arcVertexCount)
        {
            mesh.use();
            mesh.set("uViewProj", viewProj);
            glBindVertexArray(arcVao);
            glDrawArrays(GL_TRIANGLES, 0, arcVertexCount);
        }
        unlitInstanced.use();
        unlitInstanced.set("uViewProj", viewProj);
        particles.draw();
        beacons.draw();
        // The selection marker is a translucent box around the selected building, drawn last with ordinary alpha
        // blending. It is depth-tested (so the city in front of it hides it) but does not write depth, so the
        // building inside it still shows through.
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(0);
        marker.draw();
        glDepthMask(1);
        glBindVertexArray(0);
    }

    /// Reads the colour buffer (rows bottom-up) as RGBA. Called right after `render` and before ImGui draws, so
    /// snapshots contain the city without the interface. A pack alignment of 1 keeps the rows tightly packed
    /// whatever the width.
    ubyte[] readPixels(int width, int height)
    {
        auto data = new ubyte[width * height * 4];
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data.ptr);
        return data;
    }
}
