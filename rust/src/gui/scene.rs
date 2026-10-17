//! The 3D city: instanced buildings and districts (a handful of draw calls for any city size), soft shadows,
//! dependency arcs with flowing particles, and PR beacons.

use super::look::{self, Palette};
use super::material::BuildingMaterial;
use crate::city::CityModel;
use crate::layout::PLATFORM;
use three_d::*;

const SHADOW_MAP: u32 = 4096;

pub enum Hit {
    Building(usize),
    District(usize),
}

struct Arc {
    p0: Vec3,
    p1: Vec3,
    p2: Vec3,
    speed: f32,
    t: f32,
    color: Srgba,
}

pub struct Scene {
    context: Context,
    unit_box: CpuMesh,
    buildings: Gm<InstancedMesh, BuildingMaterial>,
    districts: Gm<InstancedMesh, PhysicalMaterial>,
    ground: Gm<Mesh, PhysicalMaterial>,
    arc_tubes: Option<Gm<Mesh, ColorMaterial>>,
    particles: Gm<InstancedMesh, ColorMaterial>,
    beacons: Gm<InstancedMesh, ColorMaterial>,
    marker: Gm<InstancedMesh, ColorMaterial>,
    ambient: AmbientLight,
    sun: DirectionalLight,
    arcs: Vec<Arc>,
    beacon_buildings: Vec<usize>,
    pub building_count: usize,
    pub radius: f32,
    pub shadows: bool,
    shadows_dirty: bool,
}

/// Unit box with its base at y = 0 and a 1×1 footprint centred on the origin.
fn unit_box() -> CpuMesh {
    let mut m = CpuMesh::cube();
    m.transform(Mat4::from_scale(0.5) * Mat4::from_translation(vec3(0.0, 1.0, 0.0))).unwrap();
    m
}

fn instances(transformations: Vec<Mat4>, colors: Vec<Srgba>) -> Instances {
    Instances { transformations, texture_transformations: None, colors: Some(colors) }
}

fn hidden() -> Instances {
    instances(vec![Mat4::from_scale(0.0)], vec![Srgba::WHITE])
}

fn unlit(context: &Context, color: Srgba) -> ColorMaterial {
    ColorMaterial { color, ..ColorMaterial::new_opaque(context, &CpuMaterial::default()) }
}

pub fn box_transform(x: f32, y: f32, z: f32, w: f32, h: f32, d: f32) -> Mat4 {
    Mat4::from_translation(vec3(x, y, z)) * Mat4::from_nonuniform_scale(w, h.max(0.0001), d)
}

impl Scene {
    pub fn new(context: &Context) -> Scene {
        let unit_box = unit_box();
        let opaque = |albedo: Srgba, roughness: f32| {
            PhysicalMaterial::new_opaque(context, &CpuMaterial { albedo, roughness, metallic: 0.0, ..Default::default() })
        };
        let mut ground_mesh = CpuMesh::square();
        ground_mesh.transform(Mat4::from_angle_x(degrees(-90.0))).unwrap();
        let mut sphere = CpuMesh::sphere(8);
        sphere.transform(Mat4::from_scale(1.0)).unwrap();
        let mut diamond = CpuMesh::sphere(2); // a low-poly sphere reads as a faceted gem
        diamond.transform(Mat4::from_nonuniform_scale(0.5, 0.8, 0.5)).unwrap();
        Scene {
            context: context.clone(),
            buildings: Gm::new(InstancedMesh::new(context, &hidden(), &unit_box), BuildingMaterial::default()),
            districts: Gm::new(InstancedMesh::new(context, &hidden(), &unit_box), opaque(Srgba::WHITE, 0.95)),
            ground: Gm::new(Mesh::new(context, &ground_mesh), opaque(Srgba::WHITE, 1.0)),
            arc_tubes: None,
            particles: Gm::new(InstancedMesh::new(context, &hidden(), &sphere), unlit(context, Srgba::WHITE)),
            beacons: Gm::new(InstancedMesh::new(context, &hidden(), &diamond), unlit(context, Srgba::WHITE)),
            marker: Gm::new(InstancedMesh::new(context, &hidden(), &unit_box), {
                let mut m = unlit(context, Srgba::new(255, 255, 255, 70));
                m.is_transparent = true;
                m.render_states = RenderStates { write_mask: WriteMask::COLOR, blend: Blend::TRANSPARENCY, ..Default::default() };
                m
            }),
            unit_box,
            ambient: AmbientLight::new(context, 0.5, Srgba::WHITE),
            sun: DirectionalLight::new(context, 2.0, Srgba::WHITE, vec3(0.55, -1.0, -0.3)),
            arcs: vec![],
            beacon_buildings: vec![],
            building_count: 0,
            radius: 50.0,
            shadows: true,
            shadows_dirty: true,
        }
    }

    pub fn set_model(&mut self, model: &CityModel, palette: &Palette) {
        self.building_count = model.buildings.len();
        self.radius = ((model.width.hypot(model.depth) / 2.0) as f32).max(20.0);
        let r = self.radius;
        self.ground.set_transformation(Mat4::from_translation(vec3(0.0, -0.01, 0.0)) * Mat4::from_scale(r * 7.0));
        self.districts.geometry = InstancedMesh::new(&self.context, &self.district_instances(model, palette), &self.unit_box);
        self.beacon_buildings = model.buildings.iter().filter(|b| !b.prs.is_empty()).map(|b| b.index).collect();
        self.clear_arcs();
        self.shadows_dirty = true;
    }

    fn district_instances(&self, model: &CityModel, palette: &Palette) -> Instances {
        let mut t = Vec::with_capacity(model.districts.len());
        let mut c = Vec::with_capacity(model.districts.len());
        for d in &model.districts {
            t.push(box_transform(d.x as f32, d.depth as f32 * PLATFORM as f32, d.z as f32, d.w as f32, PLATFORM as f32, d.d as f32));
            c.push(look::rgb(palette.districts[(d.depth as usize).min(5)]));
        }
        if t.is_empty() { hidden() } else { instances(t, c) }
    }

    pub fn apply_palette(&mut self, model: &CityModel, palette: &Palette) {
        self.districts.geometry.set_instances(&self.district_instances(model, palette));
        self.ground.material.albedo = palette.ground;
        self.ambient = AmbientLight::new(&self.context, palette.ambient.1, palette.ambient.0);
        let dir = self.sun.direction;
        self.sun = DirectionalLight::new(&self.context, palette.sun.1, palette.sun.0, dir);
        self.buildings.material.glow = palette.glow;
        self.buildings.material.windows = palette.windows;
        self.buildings.material.roughness = if palette.windows > 0.0 { 0.4 } else { 0.6 };
        self.shadows_dirty = true;
    }

    /// Rewrites every building's box; `heights[i] <= 0` hides building i.
    pub fn set_buildings(&mut self, model: &CityModel, heights: &[f32], colors: &[Srgba]) {
        if model.buildings.is_empty() {
            self.buildings.geometry.set_instances(&hidden());
            return;
        }
        let t: Vec<Mat4> = model
            .buildings
            .iter()
            .map(|b| {
                let h = heights[b.index];
                let s = if h > 0.001 { b.lot.side as f32 } else { 0.0 };
                box_transform(b.lot.x as f32, b.lot.y as f32, b.lot.z as f32, s, h, s)
            })
            .collect();
        self.buildings.geometry.set_instances(&instances(t, colors.to_vec()));
        self.shadows_dirty = true;
    }

    pub fn set_marker(&mut self, model: &CityModel, selected: Option<usize>, heights: &[f32], night: bool) {
        let inst = match selected {
            Some(i) => {
                let b = &model.buildings[i];
                let s = b.lot.side as f32 + 0.35;
                let color = if night { Srgba::new(255, 255, 255, 60) } else { Srgba::new(17, 24, 39, 50) };
                instances(vec![box_transform(b.lot.x as f32, b.lot.y as f32 - 0.02, b.lot.z as f32, s, heights[i] + 0.3, s)], vec![color])
            }
            None => hidden(),
        };
        self.marker.geometry.set_instances(&inst);
    }

    fn top(model: &CityModel, i: usize, heights: &[f32]) -> Vec3 {
        let b = &model.buildings[i];
        vec3(b.lot.x as f32, b.lot.y as f32 + heights[i], b.lot.z as f32)
    }

    pub fn clear_arcs(&mut self) {
        self.arcs.clear();
        self.arc_tubes = None;
        self.particles.geometry.set_instances(&hidden());
    }

    /// Dependency arcs for the selection: orange to what it uses, blue from what uses it.
    pub fn show_arcs(&mut self, model: &CityModel, selected: Option<usize>, heights: &[f32], visible: impl Fn(usize) -> bool, night: bool) {
        self.clear_arcs();
        let Some(sel) = selected else { return };
        let b = &model.buildings[sel];
        let total = b.deps.len() + b.incoming.len();
        let boost = |c: Srgba| if night { look::mix(c, Srgba::WHITE, 0.25) } else { c };
        let mut pairs: Vec<(usize, usize, Srgba)> = vec![];
        pairs.extend(b.deps.iter().filter(|&&j| visible(j)).take(200).map(|&j| (sel, j, boost(look::rgb(look::OUT)))));
        pairs.extend(b.incoming.iter().filter(|&&j| visible(j)).take(200).map(|&j| (j, sel, boost(look::rgb(look::IN)))));
        if pairs.is_empty() {
            return;
        }
        let mut positions = vec![];
        let mut colors = vec![];
        let mut indices: Vec<u32> = vec![];
        const SEGMENTS: usize = 24;
        for (from, to, color) in pairs {
            let p0 = Self::top(model, from, heights);
            let p2 = Self::top(model, to, heights);
            let dist = p0.distance(p2);
            let mid = (p0 + p2) * 0.5;
            let p1 = vec3(mid.x, p0.y.max(p2.y) + dist * 0.35 + 2.0, mid.z);
            let r = if total > 40 { 0.03 } else { 0.055 } + dist * 0.0005;
            let base = positions.len() as u32;
            for k in 0..=SEGMENTS {
                let t = k as f32 / SEGMENTS as f32;
                let at = bezier(p0, p1, p2, t);
                let tangent = bezier(p0, p1, p2, (t + 0.01).min(1.0)) - bezier(p0, p1, p2, (t - 0.01).max(0.0));
                let mut u = tangent.cross(vec3(0.0, 1.0, 0.0));
                if u.magnitude() < 1e-6 {
                    u = vec3(1.0, 0.0, 0.0);
                }
                let u = u.normalize() * r;
                let v = tangent.cross(u).normalize() * r;
                for q in [at + u, at + v, at - u, at - v] {
                    positions.push(q);
                    colors.push(color);
                }
            }
            for k in 0..SEGMENTS as u32 {
                for s in 0..4u32 {
                    let a = base + k * 4 + s;
                    let b = base + k * 4 + (s + 1) % 4;
                    let c = base + (k + 1) * 4 + (s + 1) % 4;
                    let d = base + (k + 1) * 4 + s;
                    indices.extend([a, b, c, a, c, d]);
                }
            }
            self.arcs.push(Arc { p0, p1, p2, speed: 14.0 / (dist * 1.2).max(5.0), t: rand(from * 31 + to), color });
        }
        let mesh = CpuMesh {
            positions: Positions::F32(positions),
            indices: Indices::U32(indices),
            colors: Some(colors),
            ..Default::default()
        };
        let mut material = unlit(&self.context, Srgba::WHITE);
        material.render_states.cull = Cull::None;
        self.arc_tubes = Some(Gm::new(Mesh::new(&self.context, &mesh), material));
    }

    /// Advances particles and beacons.
    pub fn animate(&mut self, model: &CityModel, heights: &[f32], dt: f32, time: f32, beacons_on: bool) {
        if !self.arcs.is_empty() {
            let mut t = vec![];
            let mut c = vec![];
            for a in &mut self.arcs {
                a.t = (a.t + dt * a.speed) % 1.0;
                let p = bezier(a.p0, a.p1, a.p2, a.t);
                let size = 0.18 + a.p0.distance(a.p2) * 0.002;
                t.push(Mat4::from_translation(p) * Mat4::from_scale(size));
                c.push(a.color);
            }
            self.particles.geometry.set_instances(&instances(t, c));
        }
        if beacons_on && !self.beacon_buildings.is_empty() {
            let mut t = vec![];
            let mut c = vec![];
            for &i in &self.beacon_buildings {
                let b = &model.buildings[i];
                let h = heights[i];
                if h <= 0.001 {
                    continue;
                }
                let size = 0.7 + (b.prs.len() as f32 * 0.25).min(1.2);
                let y = b.lot.y as f32 + h + 1.4 + (time * 2.0 + i as f32).sin() * 0.25;
                t.push(Mat4::from_translation(vec3(b.lot.x as f32, y, b.lot.z as f32)) * Mat4::from_angle_y(radians(time * 1.2 + i as f32)) * Mat4::from_scale(size));
                c.push(look::rgb(look::PR));
            }
            self.beacons.geometry.set_instances(&if t.is_empty() { hidden() } else { instances(t, c) });
        } else {
            self.beacons.geometry.set_instances(&hidden());
        }
    }

    pub fn pick(&self, camera: &Camera, pixel: PhysicalPoint) -> Option<Hit> {
        let hit = pick(&self.context, camera, pixel, [&self.buildings.geometry as &dyn Geometry, &self.districts.geometry], Cull::Back).ok()??;
        match hit.geometry_id {
            0 if (hit.instance_id as usize) < self.building_count => Some(Hit::Building(hit.instance_id as usize)),
            1 => Some(Hit::District(hit.instance_id as usize)),
            _ => None,
        }
    }

    pub fn render(&mut self, target: &RenderTarget, camera: &Camera) {
        if self.shadows && self.shadows_dirty {
            self.sun
                .generate_shadow_map(SHADOW_MAP, [&self.buildings.geometry as &dyn Geometry, &self.districts.geometry])
                .ok();
            self.shadows_dirty = false;
        } else if !self.shadows {
            self.sun.clear_shadow_map();
        }
        let lights: [&dyn Light; 2] = [&self.ambient, &self.sun];
        target.render(camera, [&self.ground as &dyn Object, &self.districts, &self.buildings], &lights);
        if let Some(arcs) = &self.arc_tubes {
            target.render(camera, [arcs as &dyn Object, &self.particles], &[]);
        }
        target.render(camera, [&self.beacons as &dyn Object, &self.marker], &[]);
    }

    pub fn invalidate_shadows(&mut self) {
        self.shadows_dirty = true;
    }
}

fn bezier(p0: Vec3, p1: Vec3, p2: Vec3, t: f32) -> Vec3 {
    p0 * ((1.0 - t) * (1.0 - t)) + p1 * (2.0 * (1.0 - t) * t) + p2 * (t * t)
}

/// Deterministic pseudo-random number in [0, 1).
fn rand(seed: usize) -> f32 {
    let x = (seed as f32 * 12.9898).sin() * 43758.547;
    x - x.floor()
}
