//! The 3D city: instanced buildings and districts (a handful of draw calls for any city size), soft shadows,
//! dependency arcs with flowing particles, and PR beacons.
//!
//! This is the GPU side of the desktop viewer (`gui/mod.rs` owns the window, camera, UI and all the state that decides
//! *what* to show). It takes a [`CityModel`] — the laid-out city produced by the layout pass and resolved by
//! `city.rs` — plus per-building heights and colours computed by the app, and turns them into three-d draw calls.
//!
//! # Coordinates
//! World coordinates are the layout's, unchanged: `x` left to right, `z` back to front, the city centred on the
//! origin, `y` up, one unit per layout unit. District platforms are [`PLATFORM`] thick and a district at nesting
//! depth `n` occupies `y ∈ [n·PLATFORM, (n+1)·PLATFORM]`; a building's [`Lot::y`](crate::layout::Lot) is the top of
//! the platform it stands on, so a building is a box from `lot.y` up to `lot.y + height`.
//!
//! # Draw calls
//! Every building is one instance of a single unit box (`buildings`), and so is every district (`districts`), so the
//! whole city costs two instanced draws regardless of size. Changing a height or colour only rewrites the instance
//! buffers ([`Scene::set_buildings`]); the mesh is never rebuilt. Instance `i` of `buildings` is building `i` of
//! the model, and instance `i` of `districts` is district `i`, which is what makes [`Scene::pick`] work.
//!
//! Lit objects (ground, districts, buildings) are rendered with an ambient light and a directional sun that casts
//! shadows; overlays (arcs, particles, beacons, selection marker) use unlit [`ColorMaterial`]s and no lights.
//!
//! The shadow map is expensive to render, so it is only regenerated when something that casts shadows has changed
//! (`shadows_dirty`): a new model, a new palette, or building boxes with new heights or visibility (not a mere
//! colour change such as hover or search dimming).

use super::look::{self, Palette};
use super::material::BuildingMaterial;
use crate::city::CityModel;
use crate::layout::PLATFORM;
use three_d::*;

/// Side length in texels of the sun's square shadow map.
const SHADOW_MAP: u32 = 4096;
/// In walk mode, the shadow map covers a square this many units across around [`Scene::set_shadow_focus`]'s point
/// instead of the whole city: at street level a city-wide map has texels large enough to show as staircase edges,
/// especially on walls the sunlight grazes, which stretch each texel.
///
/// three-d sizes the (square, orthographic) shadow camera by the full 3D diagonal of the casters' box, so the box is
/// kept flat: [`FOCUS_SIZE`] × [`FOCUS_HEIGHT`] × [`FOCUS_SIZE`] gives a 60-unit frustum, about 1.5 cm per texel of
/// the 4096² map, against about 5 cm for a typical city-wide fit. The camera's depth range (± half the diagonal
/// along the steep sun direction) still takes in casters up to about 45 units high; what is cut off above that would
/// throw its shadow outside the area anyway.
const FOCUS_SIZE: f32 = 40.0;
const FOCUS_HEIGHT: f32 = 20.0;
/// The focus point may drift this far before the shadow map is re-rendered around the new one.
const FOCUS_SLACK: f32 = 3.0;

/// A shadow caster with a substitute bounding box. three-d fits the sun's shadow camera to the boxes of the casters
/// it is given (`DirectionalLight::generate_shadow_map`); handing it every caster under one smaller box fits the map
/// to that area while still drawing everything into it (what falls outside the area is clipped away).
#[derive(Clone, Copy)]
struct Fitted<'a> {
    inner: &'a dyn Geometry,
    aabb: AxisAlignedBoundingBox,
}

impl Geometry for Fitted<'_> {
    fn draw(&self, viewer: &dyn Viewer, program: &Program, render_states: RenderStates) {
        self.inner.draw(viewer, program, render_states)
    }
    fn vertex_shader_source(&self) -> String {
        self.inner.vertex_shader_source()
    }
    fn id(&self) -> GeometryId {
        self.inner.id()
    }
    fn render_with_material(&self, material: &dyn Material, viewer: &dyn Viewer, lights: &[&dyn Light]) {
        self.inner.render_with_material(material, viewer, lights)
    }
    fn render_with_effect(&self, effect: &dyn Effect, viewer: &dyn Viewer, lights: &[&dyn Light], color: Option<ColorTexture>, depth: Option<DepthTexture>) {
        self.inner.render_with_effect(effect, viewer, lights, color, depth)
    }
    fn aabb(&self) -> AxisAlignedBoundingBox {
        self.aabb
    }
}

/// What the pointer is over, as found by [`Scene::pick`]: an index into `CityModel::buildings` or
/// `CityModel::districts`.
pub enum Hit {
    Building(usize),
    District(usize),
}

/// One dependency arc: a quadratic Bézier from `p0` (roof of the depending building) through control point `p1`
/// to `p2` (roof of the building depended on), along which one particle travels.
///
/// Not to be confused with `std::sync::Arc`, which this module does not use.
struct Arc {
    p0: Vec3,
    p1: Vec3,
    p2: Vec3,
    /// Curve parameter advanced per second; chosen so particles move at a similar world speed on long and short
    /// arcs (see [`Scene::show_arcs`]).
    speed: f32,
    /// The particle's current curve parameter, in `[0, 1)`; wraps around at the end of the arc.
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
    /// Building indices that have at least one open pull request; these get a floating beacon.
    beacon_buildings: Vec<usize>,
    pub building_count: usize,
    /// Which districts are drawn (indexed like the model's districts), or None for all of them. The history replay
    /// hides districts that have no building yet; a hidden district keeps its instance, with no footprint, so instance
    /// ids keep matching district indices for picking.
    district_mask: Option<Vec<bool>>,
    /// Rough radius of the city in world units: half the diagonal of its footprint, at least 20. The app uses it to
    /// limit camera distance and to frame the city; here it sizes the ground plane.
    pub radius: f32,
    /// Whether the sun casts shadows (the user's preference, set by the app).
    pub shadows: bool,
    /// Set whenever shadow casters changed; [`Scene::render`] regenerates the shadow map and clears it.
    shadows_dirty: bool,
    /// Walk mode: the ground point the shadow map is currently fitted around (`None`: the whole city).
    shadow_focus: Option<Vec3>,
}

/// Unit box with its base at y = 0 and a 1×1 footprint centred on the origin.
fn unit_box() -> CpuMesh {
    let mut m = CpuMesh::cube();
    // `cube()` spans [-1, 1] on every axis. The matrix applies the translation first: shifting up by 1 gives
    // y ∈ [0, 2], and halving gives y ∈ [0, 1] and x, z ∈ [-0.5, 0.5].
    m.transform(Mat4::from_scale(0.5) * Mat4::from_translation(vec3(0.0, 1.0, 0.0))).unwrap();
    m
}

/// Per-instance data for an [`InstancedMesh`]: one transform and one colour per instance. three-d multiplies the
/// instance colour into the material, which is how each building gets its own colour from one material.
fn instances(transformations: Vec<Mat4>, colors: Vec<Srgba>) -> Instances {
    Instances { transformations, texture_transformations: None, colors: Some(colors) }
}

/// A placeholder instance list used wherever there is nothing to draw: one instance scaled to zero, so nothing is
/// visible. Every instanced mesh is created with it and falls back to it rather than to an empty list.
fn hidden() -> Instances {
    instances(vec![Mat4::from_scale(0.0)], vec![Srgba::WHITE])
}

/// A flat, unlit colour material; with a white base colour it shows the per-vertex or per-instance colour as is.
fn unlit(context: &Context, color: Srgba) -> ColorMaterial {
    ColorMaterial { color, ..ColorMaterial::new_opaque(context, &CpuMaterial::default()) }
}

/// Transform that turns [`unit_box`] into a box `w × h × d` whose base centre is at `(x, y, z)`.
///
/// The height is clamped to a tiny positive value so that a zero height still gives an invertible (very flat)
/// matrix rather than a degenerate one; callers hide a building by giving it zero width instead.
pub fn box_transform(x: f32, y: f32, z: f32, w: f32, h: f32, d: f32) -> Mat4 {
    Mat4::from_translation(vec3(x, y, z)) * Mat4::from_nonuniform_scale(w, h.max(0.0001), d)
}

impl Scene {
    /// Creates the GPU resources. Everything starts hidden; [`Scene::set_model`], [`Scene::apply_palette`] and
    /// [`Scene::set_buildings`] fill it in.
    pub fn new(context: &Context) -> Scene {
        let unit_box = unit_box();
        let opaque = |albedo: Srgba, roughness: f32| {
            PhysicalMaterial::new_opaque(context, &CpuMaterial { albedo, roughness, metallic: 0.0, ..Default::default() })
        };
        // `square()` is a 2×2 quad in the xy plane facing +z; rotating it by -90° about x lays it flat, facing up.
        let mut ground_mesh = CpuMesh::square();
        ground_mesh.transform(Mat4::from_angle_x(degrees(-90.0))).unwrap();
        // Particle mesh: a unit-radius sphere (the scale of 1.0 is a no-op); [`Scene::animate`] sizes each instance.
        let mut sphere = CpuMesh::sphere(8);
        sphere.transform(Mat4::from_scale(1.0)).unwrap();
        let mut diamond = CpuMesh::sphere(2); // a low-poly sphere reads as a faceted gem
        diamond.transform(Mat4::from_nonuniform_scale(0.5, 0.8, 0.5)).unwrap();
        Scene {
            context: context.clone(),
            buildings: Gm::new(InstancedMesh::new(context, &hidden(), &unit_box), BuildingMaterial::default()),
            // White albedo: the colour comes from the per-instance colours (see `district_instances`).
            districts: Gm::new(InstancedMesh::new(context, &hidden(), &unit_box), opaque(Srgba::WHITE, 0.95)),
            ground: Gm::new(Mesh::new(context, &ground_mesh), opaque(Srgba::WHITE, 1.0)),
            arc_tubes: None,
            particles: Gm::new(InstancedMesh::new(context, &hidden(), &sphere), unlit(context, Srgba::WHITE)),
            beacons: Gm::new(InstancedMesh::new(context, &hidden(), &diamond), unlit(context, Srgba::WHITE)),
            // The selection marker is a translucent box around the selected building. It is blended over what is
            // behind it and writes colour only, not depth, so it does not hide the building inside it or anything
            // drawn after it.
            marker: Gm::new(InstancedMesh::new(context, &hidden(), &unit_box), {
                let mut m = unlit(context, Srgba::new(255, 255, 255, 70));
                m.is_transparent = true;
                m.render_states = RenderStates { write_mask: WriteMask::COLOR, blend: Blend::TRANSPARENCY, ..Default::default() };
                m
            }),
            unit_box,
            // Initial lights; `apply_palette` replaces their colour and intensity per theme but keeps the sun's
            // direction: mostly downwards, slanting towards +x and the back (-z), so shadows fall that way.
            ambient: AmbientLight::new(context, 0.5, Srgba::WHITE),
            sun: DirectionalLight::new(context, 2.0, Srgba::WHITE, vec3(0.55, -1.0, -0.3)),
            arcs: vec![],
            beacon_buildings: vec![],
            building_count: 0,
            district_mask: None,
            radius: 50.0,
            shadows: true,
            shadows_dirty: true,
            shadow_focus: None,
        }
    }

    /// Switches to a new city: resizes the ground, rebuilds the district instances and forgets arcs and beacons of
    /// the old model. Buildings are not touched here; the app follows up with [`Scene::set_buildings`].
    pub fn set_model(&mut self, model: &CityModel, palette: &Palette) {
        self.building_count = model.buildings.len();
        self.district_mask = None;
        self.radius = ((model.width.hypot(model.depth) / 2.0) as f32).max(20.0);
        let r = self.radius;
        // A ground square 14 radii across (7 from the centre to an edge; the app clamps the orbit distance to 6
        // radii). It sits 0.01 below y = 0 so it does not z-fight with the bottom face of the city platform.
        self.ground.set_transformation(Mat4::from_translation(vec3(0.0, -0.01, 0.0)) * Mat4::from_scale(r * 7.0));
        self.districts.geometry = InstancedMesh::new(&self.context, &self.district_instances(model, palette), &self.unit_box);
        self.beacon_buildings = model.buildings.iter().filter(|b| !b.prs.is_empty()).map(|b| b.index).collect();
        self.clear_arcs();
        self.shadows_dirty = true;
    }

    /// One platform box per district, in the model's (pre-order) district order, coloured by nesting depth.
    fn district_instances(&self, model: &CityModel, palette: &Palette) -> Instances {
        let mut t = Vec::with_capacity(model.districts.len());
        let mut c = Vec::with_capacity(model.districts.len());
        for (i, d) in model.districts.iter().enumerate() {
            let shown = self.district_mask.as_ref().is_none_or(|m| m[i]);
            let (w, depth) = if shown { (d.w as f32, d.d as f32) } else { (0.0, 0.0) };
            t.push(box_transform(d.x as f32, d.depth as f32 * PLATFORM as f32, d.z as f32, w, PLATFORM as f32, depth));
            // The palette has six platform colours; anything nested deeper reuses the last one.
            c.push(look::rgb(palette.districts[(d.depth as usize).min(5)]));
        }
        if t.is_empty() { hidden() } else { instances(t, c) }
    }

    /// Shows only the districts whose `mask` entry is true, or all of them for None (see `district_mask`).
    pub fn set_district_mask(&mut self, model: &CityModel, palette: &Palette, mask: Option<Vec<bool>>) {
        if self.district_mask != mask {
            self.district_mask = mask;
            self.districts.geometry.set_instances(&self.district_instances(model, palette));
            self.shadows_dirty = true;
        }
    }

    /// Applies a theme: platform colours, ground colour, light colours and intensities, and the night-time building
    /// effects. Lights are recreated because their colour and intensity are passed at construction.
    pub fn apply_palette(&mut self, model: &CityModel, palette: &Palette) {
        self.districts.geometry.set_instances(&self.district_instances(model, palette));
        self.ground.material.albedo = palette.ground;
        self.ambient = AmbientLight::new(&self.context, palette.ambient.1, palette.ambient.0);
        let dir = self.sun.direction;
        self.sun = DirectionalLight::new(&self.context, palette.sun.1, palette.sun.0, dir);
        self.buildings.material.glow = palette.glow;
        self.buildings.material.windows = palette.windows;
        // A theme with lit windows (night) also makes the facades a little glossier.
        self.buildings.material.roughness = if palette.windows > 0.0 { 0.4 } else { 0.6 };
        self.shadows_dirty = true;
    }

    /// Rewrites every building's box; a height of at most 0.001 hides building `i`.
    ///
    /// `heights`, `sides` (footprint side lengths) and `colors` are indexed by building index and must have one entry
    /// per building. Sides are the lots' sides except during a history replay, where buildings grow into their lots. Hidden buildings
    /// keep their instance (with zero footprint) so that instance ids keep matching building indices for picking.
    /// `geometry_changed` says whether heights or visibility differ from the last call; only then is the shadow map
    /// marked stale, so recolouring alone does not re-render it.
    pub fn set_buildings(&mut self, model: &CityModel, heights: &[f32], sides: &[f32], colors: &[Srgba], geometry_changed: bool) {
        if model.buildings.is_empty() {
            self.buildings.geometry.set_instances(&hidden());
            return;
        }
        let t: Vec<Mat4> = model
            .buildings
            .iter()
            .map(|b| {
                let h = heights[b.index];
                let s = if h > 0.001 { sides[b.index] } else { 0.0 };
                box_transform(b.lot.x as f32, b.lot.y as f32, b.lot.z as f32, s, h, s)
            })
            .collect();
        self.buildings.geometry.set_instances(&instances(t, colors.to_vec()));
        self.shadows_dirty |= geometry_changed;
    }

    /// Shows the translucent selection box around building `selected` (at its height and side, see
    /// [`Scene::set_buildings`]), or hides it.
    pub fn set_marker(&mut self, model: &CityModel, selected: Option<usize>, heights: &[f32], sides: &[f32], night: bool) {
        let inst = match selected {
            Some(i) => {
                let b = &model.buildings[i];
                // Slightly larger than the building on every side and above, and starting just below its base, so
                // the faces never coincide with the building's own.
                let s = sides[i] + 0.35;
                let color = if night { Srgba::new(255, 255, 255, 60) } else { Srgba::new(17, 24, 39, 50) };
                instances(vec![box_transform(b.lot.x as f32, b.lot.y as f32 - 0.02, b.lot.z as f32, s, heights[i] + 0.3, s)], vec![color])
            }
            None => hidden(),
        };
        self.marker.geometry.set_instances(&inst);
    }

    /// Centre of building `i`'s roof at its current height.
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
    ///
    /// Every arc is a quadratic Bézier between two roofs whose control point sits above the midpoint, higher for
    /// buildings further apart. All arcs are
    /// merged into one mesh of thin tubes (one draw call); each also gets one particle that [`Scene::animate`] moves
    /// from the depending building to its dependency, so the flow shows the direction of the dependency.
    ///
    /// Only arcs whose both ends are `visible` are kept, and of those at most 200 per direction are drawn.
    ///
    /// ```text
    ///                 p1
    ///              .-'  '-.
    ///          .-'          '-.
    ///        p0                p2      p1.y = max(p0.y, p2.y) + 0.35·|p0 p2| + 2
    ///        ██                ██
    /// ```
    pub fn show_arcs(&mut self, model: &CityModel, selected: Option<usize>, heights: &[f32], visible: impl Fn(usize) -> bool, night: bool) {
        self.clear_arcs();
        // A hidden selection has no visible arcs at all.
        let Some(sel) = selected.filter(|&s| visible(s)) else { return };
        let b = &model.buildings[sel];
        // Total edge count before filtering and capping; above 40, thinner tubes are used (below).
        let total = b.deps.len() + b.incoming.len();
        // At night the arc colours are mixed 25% towards white.
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
        // Each tube is a chain of SEGMENTS straight pieces; its cross-section is a square of four vertices (a ring).
        const SEGMENTS: usize = 24;
        for (from, to, color) in pairs {
            let p0 = Self::top(model, from, heights);
            let p2 = Self::top(model, to, heights);
            let dist = p0.distance(p2);
            let mid = (p0 + p2) * 0.5;
            let p1 = vec3(mid.x, p0.y.max(p2.y) + dist * 0.35 + 2.0, mid.z);
            // Tube "radius" (distance from the centre line to each ring vertex), growing slightly with length.
            let r = if total > 40 { 0.03 } else { 0.055 } + dist * 0.0005;
            let base = positions.len() as u32;
            for k in 0..=SEGMENTS {
                let t = k as f32 / SEGMENTS as f32;
                let at = bezier(p0, p1, p2, t);
                // Central-difference tangent, clamped at the ends of the curve.
                let tangent = bezier(p0, p1, p2, (t + 0.01).min(1.0)) - bezier(p0, p1, p2, (t - 0.01).max(0.0));
                // Two axes perpendicular to the tangent span the ring: u is horizontal (tangent × up), v completes
                // the frame. A vertical tangent makes the cross product vanish, so fall back to the x axis.
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
            // Stitch ring k to ring k + 1: each of the four sides is a quad a-b-c-d, split into two triangles. Vertex
            // `base + ring * 4 + side` is side `side` of ring `ring`; `(s + 1) % 4` wraps round the square.
            for k in 0..SEGMENTS as u32 {
                for s in 0..4u32 {
                    let a = base + k * 4 + s;
                    let b = base + k * 4 + (s + 1) % 4;
                    let c = base + (k + 1) * 4 + (s + 1) % 4;
                    let d = base + (k + 1) * 4 + s;
                    indices.extend([a, b, c, a, c, d]);
                }
            }
            // Speed in curve parameter per second: about 14 / 1.2 ≈ 12 world units per second measured along the
            // straight line, capped for short arcs (dist < 5 / 1.2) at 2.8 trips per second. The start position is a
            // deterministic hash of the pair, so particles on different arcs are not in lock-step.
            self.arcs.push(Arc { p0, p1, p2, speed: 14.0 / (dist * 1.2).max(5.0), t: rand(from * 31 + to), color });
        }
        let mesh = CpuMesh {
            positions: Positions::F32(positions),
            indices: Indices::U32(indices),
            colors: Some(colors),
            ..Default::default()
        };
        // Colours come from the vertex colours. No culling: the tubes are seen from every side, and drawing both
        // faces means the winding of the generated quads does not matter.
        let mut material = unlit(&self.context, Srgba::WHITE);
        material.render_states.cull = Cull::None;
        self.arc_tubes = Some(Gm::new(Mesh::new(&self.context, &mesh), material));
    }

    /// Advances particles and beacons. Called every frame; `dt` is the frame time and `time` the app's clock, both in
    /// seconds.
    pub fn animate(&mut self, model: &CityModel, heights: &[f32], dt: f32, time: f32, beacons_on: bool) {
        if !self.arcs.is_empty() {
            let mut t = vec![];
            let mut c = vec![];
            for a in &mut self.arcs {
                a.t = (a.t + dt * a.speed) % 1.0;
                let p = bezier(a.p0, a.p1, a.p2, a.t);
                // Particle radius in world units, slightly larger on long arcs.
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
                // Hidden building (same threshold as `set_buildings`): no beacon.
                if h <= 0.001 {
                    continue;
                }
                // Bigger for more open PRs, up to 1.9 at five or more.
                let size = 0.7 + (b.prs.len() as f32 * 0.25).min(1.2);
                // Hovers 1.4 above the roof, bobbing ±0.25 and spinning; the index offsets the phase so beacons do
                // not move in unison.
                let y = b.lot.y as f32 + h + 1.4 + (time * 2.0 + i as f32).sin() * 0.25;
                t.push(Mat4::from_translation(vec3(b.lot.x as f32, y, b.lot.z as f32)) * Mat4::from_angle_y(radians(time * 1.2 + i as f32)) * Mat4::from_scale(size));
                c.push(look::rgb(look::PR));
            }
            self.beacons.geometry.set_instances(&if t.is_empty() { hidden() } else { instances(t, c) });
        } else {
            self.beacons.geometry.set_instances(&hidden());
        }
    }

    /// What is under `pixel`, by GPU picking against buildings and districts only (overlays are ignored).
    ///
    /// three-d reports the index of the geometry in the list passed (0 = buildings, 1 = districts) and the instance
    /// index within it, which equals the building or district index. The `building_count` check rejects the
    /// placeholder instance of [`hidden`] in a city without buildings.
    pub fn pick(&self, camera: &Camera, pixel: PhysicalPoint) -> Option<Hit> {
        let hit = pick(&self.context, camera, pixel, [&self.buildings.geometry as &dyn Geometry, &self.districts.geometry], Cull::Back).ok()??;
        match hit.geometry_id {
            0 if (hit.instance_id as usize) < self.building_count => Some(Hit::Building(hit.instance_id as usize)),
            1 => Some(Hit::District(hit.instance_id as usize)),
            _ => None,
        }
    }

    /// Draws the scene into `target` in three passes: the lit city, then the arcs and particles, then the beacons and
    /// the translucent selection marker. The ground receives shadows but is not passed as a shadow caster.
    pub fn render(&mut self, target: &RenderTarget, camera: &Camera) {
        if self.shadows && self.shadows_dirty {
            let casters = [&self.buildings.geometry as &dyn Geometry, &self.districts.geometry];
            // A failure only costs the shadows, so it is ignored.
            match self.shadow_focus {
                Some(c) => {
                    let h = FOCUS_SIZE / 2.0;
                    let aabb = AxisAlignedBoundingBox::new_with_positions(&[vec3(c.x - h, 0.0, c.z - h), vec3(c.x + h, FOCUS_HEIGHT, c.z + h)]);
                    self.sun.generate_shadow_map(SHADOW_MAP, casters.map(|inner| Fitted { inner, aabb })).ok();
                }
                None => {
                    self.sun.generate_shadow_map(SHADOW_MAP, casters).ok();
                }
            }
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

    /// Fits the shadow map around `focus` (walk mode; see [`FOCUS_SIZE`]) or back to the whole city (`None`). Moving
    /// the focus less than [`FOCUS_SLACK`] keeps the current map, so walking re-renders shadows every few steps rather
    /// than every frame.
    pub fn set_shadow_focus(&mut self, focus: Option<Vec3>) {
        let stale = match (self.shadow_focus, focus) {
            (None, None) => false,
            (Some(a), Some(b)) => a.distance(b) > FOCUS_SLACK,
            _ => true,
        };
        if stale {
            self.shadow_focus = focus;
            self.shadows_dirty = true;
        }
    }

    pub fn invalidate_shadows(&mut self) {
        self.shadows_dirty = true;
    }
}

/// Point at parameter `t ∈ [0, 1]` on the quadratic Bézier curve with end points `p0`, `p2` and control point `p1`.
fn bezier(p0: Vec3, p1: Vec3, p2: Vec3, t: f32) -> Vec3 {
    p0 * ((1.0 - t) * (1.0 - t)) + p1 * (2.0 * (1.0 - t) * t) + p2 * (t * t)
}

/// Deterministic pseudo-random number in [0, 1).
///
/// The usual `fract(sin(x · 12.9898) · 43758.5453)` shader hash; the window hash in `material.rs` is the same idea.
/// Statistically poor, but good enough to scatter particle start positions.
fn rand(seed: usize) -> f32 {
    let x = (seed as f32 * 12.9898).sin() * 43758.547;
    x - x.floor()
}
