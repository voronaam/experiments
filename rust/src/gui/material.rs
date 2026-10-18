//! Building material: physically based lighting like three-d's `PhysicalMaterial`, plus two night-time effects
//! driven by the per-instance colour — a soft glow in the building's own colour and procedurally lit windows.
//!
//! Used only for the instanced buildings in `scene.rs`. The material has no colour of its own: three-d's instanced
//! mesh vertex shader hands the per-instance colour to the fragment shader as `col`, and that is the surface colour.
//! The lighting itself is three-d's (`calculate_lighting` from the lights' shader source); this material only adds
//! an emissive term before it and the same tone and colour mapping that three-d's own materials apply after it.
//!
//! In the day theme `glow` and `windows` are 0 and this behaves like a plain physical material; the night theme
//! (see `look.rs`) turns both on.

use three_d::*;

pub struct BuildingMaterial {
    pub roughness: f32,
    pub metallic: f32,
    /// Emission in the building's own colour, as a fraction of that colour (0 = none).
    pub glow: f32,
    /// Brightness multiplier for lit windows (0 = no windows at all).
    pub windows: f32,
}

impl Default for BuildingMaterial {
    fn default() -> Self {
        BuildingMaterial { roughness: 0.6, metallic: 0.05, glow: 0.0, windows: 0.0 }
    }
}

/// Fragment shader body. It is appended after three-d's lighting, tone-mapping and colour-mapping functions (see
/// `fragment_shader_source`), and gets its inputs from three-d's mesh vertex shader: `pos` is the fragment's
/// world-space position, `nor` the normal and `col` the instance colour.
///
/// Back faces use the flipped normal. The windows are drawn on walls only (`|normal.y| < 0.5`), in world space, so
/// they line up across neighbouring buildings and do not stretch with a building's size:
///
/// - The wall is cut into cells 0.42 wide (along `z` on walls facing ±x, along `x` otherwise) and 0.5 tall. The
///   window is the middle of the cell: 22–78% of its width and 28–72% of its height.
/// - Each cell gets a pseudo-random value from the classic `fract(sin(dot(p, (12.9898, 78.233))) · 43758.5453)`
///   hash of its cell coordinates. `normal.xz · 17` is added to the cell first so that walls facing different
///   directions get different patterns.
/// - A window is lit when the hash is at least 0.58 (about 42% of windows) and it is at least 0.9 above the ground
///   plane (world `y`). Most lit windows are warm amber; those with a hash of 0.9 or more (about a quarter of
///   the lit ones) are a cool white-blue. Brightness varies between 0.6 and 1.0 with the same hash and is scaled by
///   `windows`.
///
/// The final `1.0` passed to `calculate_lighting` is the ambient occlusion factor (none). Alpha is always 1: the
/// material is opaque.
const SHADER: &str = r#"
uniform float metallic;
uniform float roughness;
uniform float glow;
uniform float windows;
uniform vec3 cameraPosition;

in vec3 pos;
in vec3 nor;
in vec4 col;

layout (location = 0) out vec4 outColor;

void main()
{
    vec3 normal = normalize(gl_FrontFacing ? nor : -nor);
    vec3 surface = col.rgb;
    vec3 emissive = surface * glow;
    if (windows > 0.0 && abs(normal.y) < 0.5) {
        // a world-aligned grid of office windows; a hash of the cell decides which ones are lit
        float u = abs(normal.x) > 0.5 ? pos.z : pos.x;
        vec2 cell = floor(vec2(u / 0.42, pos.y / 0.5));
        vec2 f = fract(vec2(u / 0.42, pos.y / 0.5));
        float win = step(0.22, f.x) * step(f.x, 0.78) * step(0.28, f.y) * step(f.y, 0.72);
        float rnd = fract(sin(dot(cell + normal.xz * 17.0, vec2(12.9898, 78.233))) * 43758.5453);
        float lit = step(0.58, rnd) * step(0.9, pos.y);
        vec3 warm = mix(vec3(1.0, 0.72, 0.38), vec3(0.75, 0.88, 1.0), step(0.9, rnd));
        emissive += warm * win * lit * windows * (0.6 + 0.4 * rnd);
    }
    outColor.rgb = emissive + calculate_lighting(cameraPosition, surface, pos, normal, metallic, roughness, 1.0);
    outColor.rgb = tone_mapping(outColor.rgb);
    outColor.rgb = color_mapping(outColor.rgb);
    outColor.a = 1.0;
}
"#;

impl Material for BuildingMaterial {
    fn fragment_shader_source(&self, lights: &[&dyn Light]) -> String {
        let mut s = lights_shader_source(lights);
        s.push_str(ToneMapping::fragment_shader_source());
        s.push_str(ColorMapping::fragment_shader_source());
        s.push_str(SHADER);
        s
    }

    /// three-d caches compiled shader programs by this id, so it must be unique among the materials in use and the
    /// same for every `BuildingMaterial` (they all produce the same shader source for the same lights).
    /// `0x0100` is in the range three-d 0.19 reserves for user materials (`0x0000`–`0x4FFF`, see `EffectMaterialId`).
    fn id(&self) -> EffectMaterialId {
        EffectMaterialId(0x0100)
    }

    fn use_uniforms(&self, program: &Program, viewer: &dyn Viewer, lights: &[&dyn Light]) {
        // three-d's lighting-model ids: 1 = Phong, 2 = Blinn, 3-5 = Cook-Torrance variants.
        program.use_uniform_if_required("lightingModel", 2u32); // Blinn
        viewer.tone_mapping().use_uniforms(program);
        viewer.color_mapping().use_uniforms(program);
        program.use_uniform_if_required("cameraPosition", viewer.position());
        for (i, light) in lights.iter().enumerate() {
            light.use_uniforms(program, i as u32);
        }
        program.use_uniform_if_required("metallic", self.metallic);
        program.use_uniform_if_required("roughness", self.roughness);
        program.use_uniform_if_required("glow", self.glow);
        program.use_uniform_if_required("windows", self.windows);
    }

    fn render_states(&self) -> RenderStates {
        RenderStates::default()
    }

    fn material_type(&self) -> MaterialType {
        MaterialType::Opaque
    }
}
