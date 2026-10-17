/// OpenGL 3.3 renderer: every building and district is one instance of a unit box (a handful of draw calls for any
/// city), lit with a sun + ambient model, soft PCF shadow mapping, and the night-time glow / office-window shader.
module codecity.gui.renderer;

import codecity.gui.gl;
import codecity.gui.math;
import std.conv : to;
import std.math : PI;
import std.string : toStringz;

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

private enum meshVertex = q{
    #version 330 core
    layout(location = 0) in vec3 aPos;
    layout(location = 1) in vec4 aColor;
    uniform mat4 uViewProj;
    out vec4 vColor;
    void main() { vColor = aColor; gl_Position = uViewProj * vec4(aPos, 1.0); }
};

private enum unlitFragment = q{
    #version 330 core
    in vec4 vColor;
    out vec4 outColor;
    void main() { outColor = vColor; }
};

/// One instance: offset (x, y, z), scale (w, h, d), colour (r, g, b, a) — 10 floats.
struct Instance
{
    float[3] offset;
    float[3] scale;
    float[4] color;
}

/// A vertex of the arc mesh.
struct ColoredVertex
{
    float[3] position;
    float[4] color;
}

private final class InstancedShape
{
    GLuint vao, vbo, instances;
    GLsizei vertexCount, count;

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
        // per-instance attributes: offset (location 2), scale (3), colour (4)
        foreach (spec; [[2, 3, 0], [3, 3, 3], [4, 4, 6]])
        {
            glEnableVertexAttribArray(spec[0]);
            glVertexAttribPointer(spec[0], spec[1], GL_FLOAT, 0, Instance.sizeof, cast(void*)(spec[2] * float.sizeof));
            glVertexAttribDivisor(spec[0], 1);
        }
        glBindVertexArray(0);
    }

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

private final class Program
{
    GLuint id;
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
    void set(string n, ref const Mat4 m) { glUniformMatrix4fv(loc(n), 1, 0, m.m.ptr); }
}

/// Unit box with its base at y = 0 and a 1×1 footprint centred on the origin: 36 vertices with normals.
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

/// A faceted gem (octahedron) for the PR beacons.
private float[] gemVertices()
{
    float[] v;
    float[3][6] p = [[0, 0.8f, 0], [0, -0.8f, 0], [0.5f, 0, 0], [-0.5f, 0, 0], [0, 0, 0.5f], [0, 0, -0.5f]];
    int[3][8] tris = [[0, 2, 4], [0, 4, 3], [0, 3, 5], [0, 5, 2], [1, 4, 2], [1, 3, 4], [1, 5, 3], [1, 2, 5]];
    foreach (t; tris) foreach (i; t) v ~= p[i][];
    return v;
}

final class Renderer
{
    private Program lit, depth, unlitInstanced, mesh;
    private InstancedShape buildings, districts, ground, particles, beacons, marker;
    private GLuint shadowFbo, shadowTex, arcVao, arcVbo;
    private GLsizei arcVertexCount;
    private enum shadowSize = 4096;
    private Mat4 lightViewProj;
    private bool shadowsDirty = true;
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
        glGenFramebuffers(1, &shadowFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTex, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

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

    void setBuildings(const(Instance)[] data) { buildings.upload(data); shadowsDirty = true; }
    void setDistricts(const(Instance)[] data) { districts.upload(data); shadowsDirty = true; }
    void setGround(Instance g) { ground.upload([g]); }
    void setParticles(const(Instance)[] data) { particles.upload(data); }
    void setBeacons(const(Instance)[] data) { beacons.upload(data); }
    void setMarker(const(Instance)[] data) { marker.upload(data); }

    void setArcs(const(ColoredVertex)[] vertices)
    {
        glBindBuffer(GL_ARRAY_BUFFER, arcVbo);
        glBufferData(GL_ARRAY_BUFFER, vertices.length * ColoredVertex.sizeof, vertices.ptr, GL_DYNAMIC_DRAW);
        arcVertexCount = cast(GLsizei) vertices.length;
    }

    void invalidateShadows() { shadowsDirty = true; }

    struct Lighting
    {
        Vec3 ambient, sun;
        float glow = 0, windows = 0;
        bool shadows = true;
    }

    void render(int width, int height, ref const Mat4 viewProj, Vec3 camera, float radius, Vec3 background, Lighting light)
    {
        if (light.shadows && shadowsDirty)
        {
            const d = sunDir.normalized;
            const eye = -d * (radius * 3);
            lightViewProj = Mat4.orthographic(-radius * 1.15f, radius * 1.15f, -radius * 1.15f, radius * 1.15f, 0.1f, radius * 7)
                * Mat4.lookAt(eye, Vec3(0, 0, 0), Vec3(0, 1, 0));
            glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo);
            glViewport(0, 0, shadowSize, shadowSize);
            glClear(GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(2.0f, 4.0f);
            depth.use();
            depth.set("uViewProj", lightViewProj);
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
        lit.set("uShadowMap", 0);
        lit.set("uGlow", 0.0f);
        lit.set("uWindows", 0.0f);
        ground.draw();
        districts.draw();
        lit.set("uGlow", light.glow);
        lit.set("uWindows", light.windows);
        buildings.draw();

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
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(0);
        marker.draw();
        glDepthMask(1);
        glBindVertexArray(0);
    }

    /// Reads the colour buffer (rows bottom-up) as RGBA.
    ubyte[] readPixels(int width, int height)
    {
        auto data = new ubyte[width * height * 4];
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data.ptr);
        return data;
    }
}
