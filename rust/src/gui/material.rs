//! Building material: physically based lighting like three-d's `PhysicalMaterial`, plus two night-time effects
//! driven by the per-instance colour — a soft glow in the building's own colour and procedurally lit windows.

use three_d::*;

pub struct BuildingMaterial {
    pub roughness: f32,
    pub metallic: f32,
    pub glow: f32,
    pub windows: f32,
}

impl Default for BuildingMaterial {
    fn default() -> Self {
        BuildingMaterial { roughness: 0.6, metallic: 0.05, glow: 0.0, windows: 0.0 }
    }
}

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

    fn id(&self) -> EffectMaterialId {
        EffectMaterialId(0xA100)
    }

    fn use_uniforms(&self, program: &Program, viewer: &dyn Viewer, lights: &[&dyn Light]) {
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
