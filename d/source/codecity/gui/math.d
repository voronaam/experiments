/// Minimal vector/matrix maths for the renderer (column-major matrices, as OpenGL expects).
module codecity.gui.math;

import std.math : cos, sin, sqrt, tan;

struct Vec3
{
    float x = 0, y = 0, z = 0;

    Vec3 opBinary(string op)(Vec3 o) const if (op == "+" || op == "-")
    {
        return mixin("Vec3(x " ~ op ~ " o.x, y " ~ op ~ " o.y, z " ~ op ~ " o.z)");
    }

    Vec3 opBinary(string op)(float s) const if (op == "*" || op == "/")
    {
        return mixin("Vec3(x " ~ op ~ " s, y " ~ op ~ " s, z " ~ op ~ " s)");
    }

    Vec3 opUnary(string op)() const if (op == "-") { return Vec3(-x, -y, -z); }
    float dot(Vec3 o) const { return x * o.x + y * o.y + z * o.z; }
    Vec3 cross(Vec3 o) const { return Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
    float length() const { return sqrt(dot(this)); }
    Vec3 normalized() const { const l = length; return l > 1e-12 ? this / l : this; }
    float distance(Vec3 o) const { return (this - o).length; }
}

struct Mat4
{
    float[16] m = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

    Mat4 opBinary(string op : "*")(Mat4 b) const
    {
        Mat4 r;
        foreach (c; 0 .. 4)
            foreach (row; 0 .. 4)
            {
                float s = 0;
                foreach (k; 0 .. 4) s += m[k * 4 + row] * b.m[c * 4 + k];
                r.m[c * 4 + row] = s;
            }
        return r;
    }

    /// Transforms a point (w = 1) and returns clip coordinates (x, y, z, w).
    float[4] transform(Vec3 p) const
    {
        float[4] r;
        foreach (row; 0 .. 4) r[row] = m[row] * p.x + m[4 + row] * p.y + m[8 + row] * p.z + m[12 + row];
        return r;
    }

    static Mat4 perspective(float fovyRadians, float aspect, float near, float far)
    {
        Mat4 r;
        r.m[] = 0;
        const f = 1 / tan(fovyRadians / 2);
        r.m[0] = f / aspect;
        r.m[5] = f;
        r.m[10] = (far + near) / (near - far);
        r.m[11] = -1;
        r.m[14] = 2 * far * near / (near - far);
        return r;
    }

    static Mat4 orthographic(float l, float r_, float b, float t, float n, float f)
    {
        Mat4 r;
        r.m[0] = 2 / (r_ - l);
        r.m[5] = 2 / (t - b);
        r.m[10] = -2 / (f - n);
        r.m[12] = -(r_ + l) / (r_ - l);
        r.m[13] = -(t + b) / (t - b);
        r.m[14] = -(f + n) / (f - n);
        return r;
    }

    static Mat4 lookAt(Vec3 eye, Vec3 target, Vec3 up)
    {
        const f = (target - eye).normalized;
        const s = f.cross(up).normalized;
        const u = s.cross(f);
        Mat4 r;
        r.m[0] = s.x; r.m[4] = s.y; r.m[8] = s.z;
        r.m[1] = u.x; r.m[5] = u.y; r.m[9] = u.z;
        r.m[2] = -f.x; r.m[6] = -f.y; r.m[10] = -f.z;
        r.m[12] = -s.dot(eye);
        r.m[13] = -u.dot(eye);
        r.m[14] = f.dot(eye);
        return r;
    }
}

/// Ray / axis-aligned box intersection (slab test); returns the entry distance or -1.
float rayBox(Vec3 origin, Vec3 dir, Vec3 lo, Vec3 hi)
{
    float tmin = 0, tmax = float.max;
    foreach (axis; 0 .. 3)
    {
        const o = axis == 0 ? origin.x : axis == 1 ? origin.y : origin.z;
        const d = axis == 0 ? dir.x : axis == 1 ? dir.y : dir.z;
        const a = axis == 0 ? lo.x : axis == 1 ? lo.y : lo.z;
        const b = axis == 0 ? hi.x : axis == 1 ? hi.y : hi.z;
        if (d > -1e-9 && d < 1e-9)
        {
            if (o < a || o > b) return -1;
            continue;
        }
        float t1 = (a - o) / d, t2 = (b - o) / d;
        if (t1 > t2)
        {
            const t = t1;
            t1 = t2;
            t2 = t;
        }
        if (t1 > tmin) tmin = t1;
        if (t2 < tmax) tmax = t2;
        if (tmin > tmax) return -1;
    }
    return tmin;
}

unittest
{
    assert(rayBox(Vec3(0, 5, 0), Vec3(0, -1, 0), Vec3(-1, 0, -1), Vec3(1, 1, 1)) == 4);
    assert(rayBox(Vec3(5, 5, 0), Vec3(0, -1, 0), Vec3(-1, 0, -1), Vec3(1, 1, 1)) < 0);
}
