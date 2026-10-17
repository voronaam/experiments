//! Native desktop viewer: the same city as the web viewer, rendered with OpenGL (three-d) and an egui interface.

mod look;
mod material;
mod png;
mod scene;
mod source;

use crate::city::CityModel;
use crate::github::GitHubInfo;
use look::{ColorMetric, HeightMetric, Scale, Theme};
use scene::{Hit, Scene};
use source::SourceView;
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::mpsc;
use std::sync::Arc;
use three_d::egui::{self, Align2, Color32, CornerRadius, FontId, Frame, Margin, RichText, Stroke, Vec2};
use three_d::*;

pub type Rebuild = Box<dyn Fn() -> Arc<CityModel> + Send>;

/// Opens the window and blocks until it is closed.
pub fn run(model: Arc<CityModel>, watch: Option<(PathBuf, Rebuild)>) -> Result<(), String> {
    if cfg!(target_os = "linux") && std::env::var_os("DISPLAY").is_none() && std::env::var_os("WAYLAND_DISPLAY").is_none() {
        return Err("no graphical display found (DISPLAY / WAYLAND_DISPLAY unset). Run without --gui to use the browser viewer.".into());
    }
    let window = Window::new(WindowSettings {
        title: format!("{} · CodeCity", model.name),
        initial_size: Some((1440, 900)),
        ..Default::default()
    })
    .map_err(|e| format!("cannot open a window: {e}"))?;
    let context = window.gl();
    let (tx, rx) = mpsc::channel::<Arc<CityModel>>();
    let watcher = match watch {
        Some((root, rebuild)) => match crate::watch::watch(&root, move || {
            let _ = tx.send(rebuild());
        }) {
            Ok(w) => {
                println!("Watching for changes …");
                Some(w)
            }
            Err(e) => {
                eprintln!("cannot watch: {e}");
                None
            }
        },
        None => None,
    };
    let mut app = App::new(&context, model);
    let mut gui = GUI::new(&context);
    window.render_loop(move |mut frame| {
        let _keep_watching = &watcher;
        while let Ok(next) = rx.try_recv() {
            app.set_model(next, true);
        }
        app.frame(&mut frame, &mut gui)
    });
    Ok(())
}

// ------------------------------------------------------------------ preferences

struct Prefs {
    height: HeightMetric,
    color: Option<ColorMetric>,
    theme: Theme,
    show_tests: bool,
    labels: bool,
    beacons: bool,
    shadows: bool,
}

impl Prefs {
    fn path() -> PathBuf {
        let base = std::env::var_os("XDG_CONFIG_HOME")
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".config"));
        base.join("codecity").join("desktop.json")
    }

    fn load() -> Prefs {
        let v: serde_json::Value = std::fs::read_to_string(Self::path()).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        let s = |k: &str| v[k].as_str().unwrap_or("").to_string();
        let b = |k: &str, d: bool| v[k].as_bool().unwrap_or(d);
        Prefs {
            height: HeightMetric::ALL.into_iter().find(|m| format!("{m:?}") == s("height")).unwrap_or(HeightMetric::Nom),
            color: ColorMetric::ALL.into_iter().find(|m| format!("{m:?}") == s("color")),
            theme: if s("theme") == "night" { Theme::Night } else { Theme::Day },
            show_tests: b("tests", true),
            labels: b("labels", true),
            beacons: b("beacons", true),
            shadows: b("shadows", true),
        }
    }

    fn save(&self) {
        let v = serde_json::json!({
            "height": format!("{:?}", self.height),
            "color": self.color.map(|c| format!("{c:?}")),
            "theme": self.theme.name(),
            "tests": self.show_tests,
            "labels": self.labels,
            "beacons": self.beacons,
            "shadows": self.shadows,
        });
        let path = Self::path();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(path, v.to_string());
    }
}

// ------------------------------------------------------------------ camera

#[derive(Clone, Copy)]
struct Orbit {
    target: Vec3,
    yaw: f32,
    pitch: f32,
    distance: f32,
}

impl Orbit {
    fn position(&self) -> Vec3 {
        self.target + vec3(self.pitch.cos() * self.yaw.sin(), self.pitch.sin(), self.pitch.cos() * self.yaw.cos()) * self.distance
    }
}

struct Flight {
    from: Orbit,
    to_target: Vec3,
    to_distance: f32,
    start: f64,
    duration: f64,
}

struct Animation {
    from: Vec<f32>,
    to: Vec<f32>,
    start: f64,
    duration: f64,
}

#[derive(Default)]
struct Actions {
    select: Option<Option<usize>>,
    fly_selected: bool,
    recolor: bool,
    reheight: bool,
    theme: bool,
    frame_all: bool,
    snapshot: bool,
    open_source: Option<usize>,
    open_url: Option<String>,
}

// ------------------------------------------------------------------ the app

struct App {
    model: Arc<CityModel>,
    scene: Scene,
    camera: Camera,
    orbit: Orbit,
    flight: Option<Flight>,
    prefs: Prefs,
    color_metric: ColorMetric,
    selected: Option<usize>,
    hovered: Option<usize>,
    hovered_district: Option<usize>,
    pointer: Option<(f32, f32)>,
    press: Option<(f32, f32)>,
    last_click: (f64, Option<usize>),
    search: String,
    search_focused: bool,
    search_dirty: bool,
    /// pointer over an egui panel this frame: three-d only marks events handled while egui drags something
    over_gui: bool,
    matches: Option<HashSet<usize>>,
    results: Vec<usize>,
    heights: Vec<f32>,
    animation: Option<Animation>,
    colors: Vec<Srgba>,
    scale: Scale,
    buildings_dirty: bool,
    labels: Vec<(String, Vec3)>,
    source: Option<SourceView>,
    toast: Option<(String, f64)>,
    time: f64,
    visuals_theme: Option<Theme>,
}

impl App {
    fn new(context: &Context, model: Arc<CityModel>) -> App {
        let prefs = Prefs::load();
        let scene = Scene::new(context);
        let camera = Camera::new_perspective(Viewport::new_at_origo(1, 1), vec3(0.0, 50.0, 80.0), Vec3::zero(), Vec3::unit_y(), degrees(42.0), 0.3, 10000.0);
        let mut app = App {
            scale: Scale { metric: ColorMetric::Cx, lo: 0.0, hi: 1.0 },
            model: model.clone(),
            scene,
            camera,
            orbit: Orbit { target: Vec3::zero(), yaw: 0.6, pitch: 0.62, distance: 100.0 },
            flight: None,
            color_metric: ColorMetric::Cx,
            prefs,
            selected: None,
            hovered: None,
            hovered_district: None,
            pointer: None,
            press: None,
            last_click: (0.0, None),
            search: String::new(),
            search_focused: false,
            search_dirty: false,
            over_gui: false,
            matches: None,
            results: vec![],
            heights: vec![],
            animation: None,
            colors: vec![],
            buildings_dirty: true,
            labels: vec![],
            source: None,
            toast: None,
            time: 0.0,
            visuals_theme: None,
        };
        app.set_model(model, false);
        app
    }

    fn visible(&self, i: usize) -> bool {
        self.prefs.show_tests || !self.model.types()[self.model.buildings[i].index].test
    }

    fn set_model(&mut self, next: Arc<CityModel>, live: bool) {
        let previous = self.selected.map(|i| self.model.types()[i].fqn.clone());
        self.model = next;
        self.selected = None;
        self.hovered = None;
        self.color_metric = match self.prefs.color {
            Some(c) if c.unavailable(&self.model).is_none() => c,
            _ => if self.model.has_git() { ColorMetric::Commits } else { ColorMetric::Cx },
        };
        let n = self.model.buildings.len();
        let old = std::mem::take(&mut self.heights);
        self.heights = (0..n).map(|i| if live { old.get(i).copied().unwrap_or(0.0) } else { 0.0 }).collect();
        self.scene.set_model(&self.model, &self.prefs.theme.palette());
        self.scene.apply_palette(&self.model, &self.prefs.theme.palette());
        self.scene.shadows = self.prefs.shadows;
        self.build_labels();
        self.reheight(!live);
        self.recolor();
        if !live {
            self.frame_all(false);
        }
        if let Some(fqn) = previous {
            if let Some(i) = self.model.types().iter().position(|t| t.fqn == fqn) {
                self.select(Some(i), false);
            }
        }
        if !self.search.is_empty() {
            self.run_search();
        }
        if live {
            self.toast(format!("City rebuilt · {} types", look::fmt(n as f64)));
        } else if n == 0 {
            self.toast("No Java, TypeScript or Rust types found here".into());
        }
    }

    fn build_labels(&mut self) {
        let mut candidates: Vec<_> = self.model.districts.iter().filter(|d| (1..=2).contains(&d.depth) && d.types > 0).collect();
        candidates.sort_by(|a, b| (b.w * b.d).total_cmp(&(a.w * a.d)));
        let mut anchors: Vec<(f64, f64)> = vec![];
        self.labels.clear();
        for d in candidates.into_iter().take(24) {
            let front = d.z + d.d / 2.0;
            if anchors.len() >= 16 || anchors.iter().any(|(x, z)| (x - d.x).abs() < 8.0 && (z - front).abs() < 3.0) {
                continue;
            }
            anchors.push((d.x, front));
            let y = (d.depth + 1) as f64 * crate::layout::PLATFORM + 0.2;
            self.labels.push((d.label.clone(), vec3(d.x as f32, y as f32, front as f32)));
        }
    }

    fn reheight(&mut self, rise: bool) {
        let metric = self.prefs.height;
        let types = self.model.types();
        let mut values: Vec<f64> = (0..types.len()).filter(|&i| self.visible(i)).map(|i| metric.value(&types[i])).collect();
        values.sort_by(f64::total_cmp);
        let p95 = if values.is_empty() { 1.0 } else { values[((0.95 * (values.len() - 1) as f64) as usize).min(values.len() - 1)] }.max(1.0);
        let r = self.scene.radius as f64;
        let scale = (r * 0.2).clamp(8.0, 16.0) / p95;
        let to: Vec<f32> = (0..types.len())
            .map(|i| if self.visible(i) { (0.35 + metric.value(&types[i]) * scale).min(r * 0.75) as f32 } else { 0.0 })
            .collect();
        let from = if rise { vec![0.0; to.len()] } else { self.heights.clone() };
        self.animation = Some(Animation { from, to, start: self.time, duration: if rise { 1.4 } else { 0.65 } });
    }

    fn recolor(&mut self) {
        let m = &self.model;
        self.scale = Scale::of(self.color_metric, m);
        let palette = self.prefs.theme.palette();
        let mut related = HashSet::new();
        if let Some(s) = self.selected {
            related.insert(s);
            related.extend(m.buildings[s].deps.iter().copied());
            related.extend(m.buildings[s].incoming.iter().copied());
        }
        self.colors = m
            .buildings
            .iter()
            .map(|b| {
                let mut c = self.scale.color(&m.types()[b.index], b);
                let out_of_search = self.matches.as_ref().is_some_and(|ms| !ms.contains(&b.index));
                let out_of_focus = self.selected.is_some() && !related.contains(&b.index);
                if out_of_search || out_of_focus {
                    c = look::mix(c, palette.dim, 0.8);
                }
                if Some(b.index) == self.hovered && Some(b.index) != self.selected {
                    c = look::mix(c, Srgba::WHITE, 0.3);
                }
                c
            })
            .collect();
        self.buildings_dirty = true;
    }

    fn select(&mut self, i: Option<usize>, fly: bool) {
        self.selected = i;
        let night = self.prefs.theme == Theme::Night;
        let heights = self.target_heights();
        let visible: Vec<bool> = (0..self.model.buildings.len()).map(|j| self.visible(j)).collect();
        self.scene.show_arcs(&self.model, i, &heights, |j| visible[j], night);
        self.scene.set_marker(&self.model, i, &heights, night);
        self.recolor();
        if let (Some(i), true) = (i, fly) {
            self.fly_to_building(i);
        }
    }

    fn target_heights(&self) -> Vec<f32> {
        self.animation.as_ref().map(|a| a.to.clone()).unwrap_or_else(|| self.heights.clone())
    }

    fn apply_theme(&mut self) {
        let palette = self.prefs.theme.palette();
        self.scene.apply_palette(&self.model, &palette);
        self.select(self.selected, false);
    }

    fn frame_all(&mut self, animate: bool) {
        let r = self.scene.radius;
        let target = Orbit { target: Vec3::zero(), yaw: 0.6, pitch: 0.62, distance: r * 2.6 };
        if animate {
            self.fly(target.target, target.distance);
        } else {
            self.orbit = target;
        }
    }

    fn fly(&mut self, to_target: Vec3, to_distance: f32) {
        if self.orbit.pitch < 0.35 {
            self.orbit.pitch = 0.35;
        }
        self.flight = Some(Flight { from: self.orbit, to_target, to_distance, start: self.time, duration: 0.9 });
    }

    fn fly_to_building(&mut self, i: usize) {
        let b = &self.model.buildings[i];
        let h = self.target_heights()[i];
        let t = vec3(b.lot.x as f32, b.lot.y as f32 + h * 0.5, b.lot.z as f32);
        self.fly(t, (h * 1.8).max(b.lot.side as f32 * 7.0).max(22.0));
    }

    fn fly_to_district(&mut self, d: usize) {
        let d = &self.model.districts[d];
        let t = vec3(d.x as f32, (d.depth + 1) as f32 * crate::layout::PLATFORM as f32, d.z as f32);
        self.fly(t, (d.w.max(d.d) * 1.25 + 10.0) as f32);
    }

    fn run_search(&mut self) {
        let q = self.search.trim().to_lowercase();
        if q.chars().count() < 2 {
            self.matches = None;
            self.results.clear();
            self.recolor();
            return;
        }
        let mut scored: Vec<(usize, u8, usize)> = vec![];
        for (i, t) in self.model.types().iter().enumerate() {
            if !self.visible(i) {
                continue;
            }
            let name = t.name.to_lowercase();
            let score = if name == q {
                0
            } else if name.starts_with(&q) {
                1
            } else if name.contains(&q) {
                2
            } else if t.fqn.to_lowercase().contains(&q) {
                3
            } else {
                continue;
            };
            scored.push((i, score, name.len()));
        }
        scored.sort_by_key(|&(_, s, l)| (s, l));
        self.matches = Some(scored.iter().map(|s| s.0).collect());
        self.results = scored.iter().take(12).map(|s| s.0).collect();
        self.recolor();
    }

    fn toast(&mut self, text: String) {
        self.toast = Some((text, self.time + 2.6));
    }

    fn open_source(&mut self, i: usize) {
        let t = &self.model.types()[i];
        let b = &self.model.buildings[i];
        let code = std::fs::read(&t.file).map(|b| String::from_utf8_lossy(&b).into_owned()).unwrap_or_else(|e| format!("// unable to read: {e}"));
        self.source = Some(SourceView {
            title: format!("{}:{}", b.file, t.line),
            lines: source::highlight(&code, t.language),
            first: t.line as usize,
            last: t.end_line as usize,
            scroll_pending: true,
            open: true,
        });
    }

    fn github_link(gh: &GitHubInfo, kind: &str, model: &CityModel, i: usize) -> String {
        let t = &model.types()[i];
        let enc = |p: &str| p.split('/').map(url_encode).collect::<Vec<_>>().join("/");
        let path = enc(&format!("{}{}", gh.prefix, model.buildings[i].file));
        let url = format!("{}/{}/{}/{}", gh.repo.web_url(), kind, enc(&gh.git_ref), path);
        if kind == "commits" { url } else { format!("{url}#L{}-L{}", t.line, t.end_line) }
    }

    // -------------------------------------------------------------- per frame

    fn frame(&mut self, frame: &mut FrameInput, gui: &mut GUI) -> FrameOutput {
        let dt = (frame.elapsed_time / 1000.0) as f32;
        self.time = frame.accumulated_time / 1000.0;
        let viewport = frame.viewport;
        let dpr = frame.device_pixel_ratio;
        let mut actions = Actions::default();

        gui.update(&mut frame.events, frame.accumulated_time, viewport, dpr, |ui| {
            self.ui(ui, viewport, dpr, &mut actions);
        });
        self.handle_events(&frame.events, dpr, viewport, &mut actions);
        self.apply(&actions);

        // height animation
        if let Some(a) = &self.animation {
            let k = ((self.time - a.start) / a.duration).clamp(0.0, 1.0) as f32;
            let e = 1.0 - (1.0 - k).powi(3);
            self.heights = a.from.iter().zip(&a.to).map(|(f, t)| f + (t - f) * e).collect();
            self.buildings_dirty = true;
            if k >= 1.0 {
                self.animation = None;
                let s = self.selected;
                self.select(s, false);
                self.scene.invalidate_shadows();
            }
        }
        if self.buildings_dirty {
            self.scene.set_buildings(&self.model, &self.heights, &self.colors);
            self.buildings_dirty = false;
        }
        if let Some(f) = &self.flight {
            let k = ((self.time - f.start) / f.duration).clamp(0.0, 1.0) as f32;
            let e = if k < 0.5 { 4.0 * k * k * k } else { 1.0 - (-2.0 * k + 2.0).powi(3) / 2.0 };
            self.orbit.target = f.from.target + (f.to_target - f.from.target) * e;
            self.orbit.distance = f.from.distance + (f.to_distance - f.from.distance) * e;
            if k >= 1.0 {
                self.flight = None;
            }
        }
        self.scene.animate(&self.model, &self.heights, dt, self.time as f32, self.prefs.beacons);

        self.camera.set_viewport(viewport);
        let r = self.scene.radius;
        self.camera.set_perspective_projection(degrees(42.0), 0.3, r * 30.0);
        self.camera.set_view(self.orbit.position(), self.orbit.target, Vec3::unit_y());

        let bg = self.prefs.theme.palette().background;
        let screen = frame.screen();
        screen.clear(ClearState::color_and_depth(bg.r as f32 / 255.0, bg.g as f32 / 255.0, bg.b as f32 / 255.0, 1.0, 1.0));
        self.scene.render(&screen, &self.camera);
        if actions.snapshot {
            self.snapshot(&screen, viewport);
        }
        screen.write(|| gui.render()).ok();
        FrameOutput::default()
    }

    fn apply(&mut self, a: &Actions) {
        if let Some(sel) = a.select {
            self.select(sel, a.fly_selected);
        }
        if a.reheight {
            self.reheight(false);
            self.prefs.save();
        }
        if a.recolor {
            self.recolor();
            self.prefs.save();
        }
        if a.theme {
            self.prefs.theme = self.prefs.theme.other();
            self.prefs.save();
            self.apply_theme();
        }
        if a.frame_all {
            self.frame_all(true);
        }
        if let Some(i) = a.open_source {
            self.open_source(i);
        }
        if let Some(url) = &a.open_url {
            if let Err(e) = webbrowser::open(url) {
                self.toast(format!("Could not open browser: {e}"));
            }
        }
    }

    fn handle_events(&mut self, events: &[Event], dpr: f32, viewport: Viewport, actions: &mut Actions) {
        let over_gui = self.over_gui;
        for event in events {
            let handled = match event {
                Event::MousePress { handled, .. } | Event::MouseRelease { handled, .. } | Event::MouseWheel { handled, .. } => *handled,
                _ => false,
            };
            if over_gui && !handled && !matches!(event, Event::MouseMotion { .. } | Event::MouseLeave) {
                if matches!(event, Event::MouseRelease { .. }) {
                    self.press = None;
                }
                continue;
            }
            match event {
                Event::MousePress { button: MouseButton::Left, position, handled: false, .. } => {
                    self.press = Some((position.x, position.y));
                    self.flight = None;
                }
                Event::MouseRelease { button: MouseButton::Left, position, handled: false, .. } => {
                    let clicked = self.press.is_some_and(|(x, y)| (x - position.x).hypot(y - position.y) < 5.0 * dpr);
                    self.press = None;
                    if clicked {
                        match self.scene.pick(&self.camera, *position) {
                            Some(Hit::Building(i)) => {
                                actions.select = Some(Some(i));
                                actions.fly_selected = true;
                            }
                            Some(Hit::District(d)) => {
                                if self.last_click.1 == Some(d) && self.time - self.last_click.0 < 0.4 {
                                    self.fly_to_district(d);
                                }
                                self.last_click = (self.time, Some(d));
                                if self.selected.is_some() {
                                    actions.select = Some(None);
                                }
                            }
                            None => {
                                if self.selected.is_some() {
                                    actions.select = Some(None);
                                }
                            }
                        }
                    }
                }
                Event::MouseMotion { button, delta, position, handled, .. } => {
                    let blocked = *handled || (over_gui && button.is_none());
                    self.pointer = if blocked { None } else { Some((position.x / dpr, (viewport.height as f32 - position.y) / dpr)) };
                    if blocked {
                        if self.hovered.take().is_some() {
                            actions.recolor = true;
                        }
                        self.hovered_district = None;
                        continue;
                    }
                    match button {
                        Some(MouseButton::Left) => {
                            self.flight = None;
                            self.orbit.yaw -= delta.0 * 0.006;
                            self.orbit.pitch = (self.orbit.pitch + delta.1 * 0.006).clamp(0.07, 1.5);
                        }
                        Some(MouseButton::Right) | Some(MouseButton::Middle) => {
                            self.flight = None;
                            let k = self.orbit.distance * 0.0016;
                            let (s, c) = self.orbit.yaw.sin_cos();
                            let right = vec3(c, 0.0, -s);
                            let forward = vec3(-s, 0.0, -c);
                            self.orbit.target += -right * delta.0 * k + forward * delta.1 * k;
                        }
                        None => {
                            let hit = self.scene.pick(&self.camera, *position);
                            let (b, d) = match hit {
                                Some(Hit::Building(i)) => (Some(i), None),
                                Some(Hit::District(d)) => (None, Some(d)),
                                None => (None, None),
                            };
                            self.hovered_district = d;
                            if b != self.hovered {
                                self.hovered = b;
                                actions.recolor = true;
                            }
                        }
                    }
                }
                Event::MouseWheel { delta, handled: false, .. } => {
                    self.flight = None;
                    let r = self.scene.radius;
                    self.orbit.distance = (self.orbit.distance * (-delta.1 * 0.0015).exp()).clamp(4.0, r * 6.0);
                }
                Event::MouseLeave => {
                    self.pointer = None;
                    self.hovered_district = None;
                    if self.hovered.take().is_some() {
                        actions.recolor = true;
                    }
                }
                _ => {}
            }
        }
    }

    fn snapshot(&mut self, screen: &RenderTarget, viewport: Viewport) {
        let pixels: Vec<[u8; 4]> = screen.read_color(); // rows already run top to bottom
        let (w, h) = (viewport.width, viewport.height);
        let rgba: Vec<u8> = pixels.iter().flat_map(|p| [p[0], p[1], p[2], 255]).collect();
        let name = format!("{}-codecity-{}.png", self.model.name, look::now());
        let home = PathBuf::from(std::env::var_os("HOME").unwrap_or_default());
        let dir = if home.join("Pictures").is_dir() { home.join("Pictures") } else { std::env::current_dir().unwrap_or(home) };
        let path = dir.join(&name);
        match std::fs::write(&path, png::encode(w, h, &rgba)) {
            Ok(()) => self.toast(format!("Saved {}", path.display())),
            Err(e) => self.toast(format!("Could not save: {e}")),
        }
    }

    // -------------------------------------------------------------- interface

    fn ui(&mut self, ui: &mut egui::Ui, viewport: Viewport, dpr: f32, actions: &mut Actions) {
        let ctx = ui.ctx().clone();
        let night = self.prefs.theme == Theme::Night;
        self.over_gui = ctx.is_pointer_over_egui() || ctx.egui_is_using_pointer();
        if self.visuals_theme != Some(self.prefs.theme) {
            ctx.set_visuals(visuals(night));
            self.visuals_theme = Some(self.prefs.theme);
        }
        let screen = Vec2::new(viewport.width as f32 / dpr, viewport.height as f32 / dpr);
        self.draw_labels(&ctx, viewport, dpr, night);
        self.top_bar(&ctx, screen, actions);
        self.controls(&ctx, actions);
        self.inspector(&ctx, screen, actions);
        self.tooltip(&ctx);
        self.source_window(&ctx);

        egui::Area::new("hint".into()).anchor(Align2::CENTER_BOTTOM, Vec2::new(0.0, -14.0)).interactable(false).show(&ctx, |ui| {
            card(night).corner_radius(CornerRadius::same(255)).inner_margin(Margin::symmetric(12, 6)).show(ui, |ui| {
                ui.label(RichText::new("drag to orbit · right-drag to pan · scroll to zoom · click a building · / search · F frame · N night · Esc clear").small().weak());
            });
        });
        if let Some((text, until)) = self.toast.clone() {
            if self.time < until {
                egui::Area::new("toast".into()).anchor(Align2::CENTER_BOTTOM, Vec2::new(0.0, -58.0)).interactable(false).show(&ctx, |ui| {
                    card(night).corner_radius(CornerRadius::same(255)).inner_margin(Margin::symmetric(14, 8)).show(ui, |ui| ui.strong(text));
                });
            } else {
                self.toast = None;
            }
        }

        // keyboard shortcuts (unless typing)
        let typing = ctx.egui_wants_keyboard_input();
        ctx.input(|i| {
            if i.key_pressed(egui::Key::Escape) {
                if self.source.as_ref().is_some_and(|s| s.open) {
                    self.source = None;
                } else if self.selected.is_some() {
                    actions.select = Some(None);
                } else if !self.search.is_empty() {
                    self.search.clear();
                    self.run_search_later();
                }
            }
            if !typing {
                if i.key_pressed(egui::Key::F) {
                    actions.frame_all = true;
                }
                if i.key_pressed(egui::Key::N) {
                    actions.theme = true;
                }
            }
        });
        if !typing && ctx.input(|i| i.key_pressed(egui::Key::Slash)) {
            ctx.memory_mut(|m| m.request_focus(egui::Id::new("search")));
        }
        if self.search_dirty {
            self.search_dirty = false;
            self.run_search();
        }
    }

    fn run_search_later(&mut self) {
        self.search_dirty = true;
    }

    fn draw_labels(&self, ctx: &egui::Context, viewport: Viewport, dpr: f32, night: bool) {
        if !self.prefs.labels {
            return;
        }
        let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Background, egui::Id::new("labels")));
        let cam = self.camera.position();
        let dir = self.camera.view_direction();
        let font = FontId::proportional(11.0);
        for (text, pos) in &self.labels {
            if (*pos - cam).dot(dir) <= 0.0 {
                continue;
            }
            let p = self.camera.pixel_at_position(*pos);
            let at = egui::pos2(p.x / dpr, (viewport.height as f32 - p.y) / dpr);
            let galley = painter.layout_no_wrap(text.clone(), font.clone(), if night { Color32::from_rgb(226, 232, 240) } else { Color32::from_rgb(15, 23, 42) });
            let rect = egui::Rect::from_center_size(at - Vec2::new(0.0, galley.size().y / 2.0 + 4.0), galley.size() + Vec2::new(16.0, 6.0));
            let fill = if night { Color32::from_rgba_unmultiplied(13, 19, 33, 215) } else { Color32::from_rgba_unmultiplied(255, 255, 255, 215) };
            painter.rect(rect, CornerRadius::same(255), fill, Stroke::new(1.0_f32, Color32::from_black_alpha(25)), egui::StrokeKind::Inside);
            painter.galley(rect.center() - galley.size() / 2.0, galley, Color32::PLACEHOLDER);
        }
    }

    fn top_bar(&mut self, ctx: &egui::Context, screen: Vec2, actions: &mut Actions) {
        let night = self.prefs.theme == Theme::Night;
        let width = (screen.x - 32.0).max(300.0);
        let mut search_rect = egui::Rect::NOTHING;
        egui::Area::new("topbar".into()).fixed_pos(egui::pos2(16.0, 16.0)).show(ctx, |ui| {
            card(night).inner_margin(Margin::symmetric(14, 10)).show(ui, |ui| {
                ui.set_width(width - 28.0);
                ui.horizontal(|ui| {
                    logo(ui);
                    ui.vertical(|ui| {
                        ui.label(RichText::new("CodeCity").strong().size(14.0));
                        ui.label(RichText::new(&self.model.name).small().weak());
                    });
                    ui.add_space(24.0);
                    let edit = egui::TextEdit::singleline(&mut self.search)
                        .id(egui::Id::new("search"))
                        .hint_text("Search types…   ( / )")
                        .desired_width(380.0)
                        .margin(Margin::symmetric(10, 6));
                    let response = ui.add(edit);
                    search_rect = response.rect;
                    self.search_focused = response.has_focus();
                    if response.changed() {
                        self.search_dirty = true;
                    }
                    if response.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                        if let Some(&first) = self.results.first() {
                            actions.select = Some(Some(first));
                            actions.fly_selected = true;
                        }
                    }
                });
            });
        });
        // stats chips: their own right-anchored area, laid out left to right
        egui::Area::new("stats".into()).anchor(Align2::RIGHT_TOP, Vec2::new(-30.0, 27.0)).order(egui::Order::Foreground).show(ctx, |ui| {
            ui.horizontal(|ui| {
                if let Some(gh) = &self.model.github {
                    let prs = gh
                        .pulls
                        .as_ref()
                        .map(|p| format!(" · {}{} PRs", p.len(), if gh.pulls_source.as_deref().is_some_and(|s| s.starts_with("all")) { "" } else { "+" }))
                        .unwrap_or_default();
                    if ui.add(egui::Button::new(RichText::new(format!("{}/{}{prs}", gh.repo.owner, gh.repo.name)).strong()).corner_radius(CornerRadius::same(255))).on_hover_text("Open on GitHub").clicked() {
                        actions.open_url = Some(gh.repo.web_url());
                    }
                }
                let s = &self.model.stats;
                if !s.languages.is_empty() {
                    let total: usize = s.languages.iter().map(|l| l.1).sum();
                    let label = s
                        .languages
                        .iter()
                        .map(|(l, c)| if s.languages.len() > 1 { format!("{} {}%", l.display_name(), (100 * c + total / 2) / total) } else { l.display_name().to_string() })
                        .collect::<Vec<_>>()
                        .join(" · ");
                    chip(ui, "", &label, night);
                }
                chip(ui, &look::fmt(s.types as f64), "types", night);
                chip(ui, &look::fmt(s.packages as f64), "packages", night);
                chip(ui, &look::fmt(s.methods as f64), "methods", night);
                chip(ui, &look::fmt(s.loc as f64), "LOC", night);
                if s.failed_files > 0 {
                    chip(ui, &format!("{}", s.failed_files), "parse errors", night);
                }
            });
        });
        // results dropdown under the search field
        if (self.search_focused || ctx.memory(|m| m.has_focus(egui::Id::new("search_results")))) && self.matches.is_some() {
            egui::Area::new("search_results".into()).fixed_pos(search_rect.left_bottom() + Vec2::new(0.0, 6.0)).order(egui::Order::Foreground).show(ctx, |ui| {
                card(night).inner_margin(Margin::same(6)).show(ui, |ui| {
                    ui.set_width(search_rect.width().max(360.0));
                    if self.results.is_empty() {
                        ui.label(RichText::new("No matching types").weak());
                    }
                    for &i in &self.results {
                        let t = &self.model.types()[i];
                        let r = ui.add(egui::Button::new(RichText::new(format!("{}\n{}", t.name, t.package)).size(12.5)).frame(false).min_size(Vec2::new(ui.available_width(), 0.0)));
                        if r.clicked() {
                            actions.select = Some(Some(i));
                            actions.fly_selected = true;
                            ctx.memory_mut(|m| m.surrender_focus(egui::Id::new("search")));
                        }
                    }
                    let more = self.matches.as_ref().map(|m| m.len()).unwrap_or(0).saturating_sub(self.results.len());
                    if more > 0 {
                        ui.label(RichText::new(format!("{more} more highlighted in the city")).small().weak());
                    }
                });
            });
        }
    }

    fn controls(&mut self, ctx: &egui::Context, actions: &mut Actions) {
        let night = self.prefs.theme == Theme::Night;
        egui::Area::new("controls".into()).fixed_pos(egui::pos2(16.0, 92.0)).show(ctx, |ui| {
            card(night).inner_margin(Margin::same(14)).show(ui, |ui| {
                ui.set_width(210.0);
                caption(ui, "HEIGHT");
                egui::ComboBox::from_id_salt("height").width(210.0).selected_text(self.prefs.height.label()).show_ui(ui, |ui| {
                    for m in HeightMetric::ALL {
                        if ui.selectable_value(&mut self.prefs.height, m, m.label()).clicked() {
                            actions.reheight = true;
                        }
                    }
                });
                ui.add_space(8.0);
                caption(ui, "COLOUR");
                egui::ComboBox::from_id_salt("colour").width(210.0).selected_text(self.color_metric.label()).show_ui(ui, |ui| {
                    for m in ColorMetric::ALL {
                        let why = m.unavailable(&self.model);
                        let label = format!("{}{}", m.label(), why.unwrap_or(""));
                        if ui.add_enabled(why.is_none(), egui::Button::selectable(self.color_metric == m, label)).clicked() {
                            self.color_metric = m;
                            self.prefs.color = Some(m);
                            actions.recolor = true;
                        }
                    }
                });
                self.legend(ui);
                ui.add_space(8.0);
                if ui.checkbox(&mut self.prefs.show_tests, "Test code").changed() {
                    if self.selected.is_some_and(|s| !self.visible(s)) {
                        actions.select = Some(None);
                    }
                    actions.reheight = true;
                    actions.recolor = true;
                    self.search_dirty = !self.search.is_empty();
                }
                if ui.checkbox(&mut self.prefs.labels, "District labels").changed() {
                    self.prefs.save();
                }
                if self.model.has_pulls() && ui.checkbox(&mut self.prefs.beacons, "Open-PR beacons").changed() {
                    self.prefs.save();
                }
                if ui.checkbox(&mut self.prefs.shadows, "Shadows").changed() {
                    self.scene.shadows = self.prefs.shadows;
                    self.scene.invalidate_shadows();
                    self.prefs.save();
                }
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    let w = (210.0 - 2.0 * ui.spacing().item_spacing.x) / 3.0;
                    if ui.add_sized([w, 28.0], egui::Button::new(if night { "Day" } else { "Night" })).clicked() {
                        actions.theme = true;
                    }
                    if ui.add_sized([w, 28.0], egui::Button::new("Frame")).clicked() {
                        actions.frame_all = true;
                    }
                    if ui.add_sized([w, 28.0], egui::Button::new("Snapshot")).on_hover_text("Save a PNG to ~/Pictures (or the current directory)").clicked() {
                        actions.snapshot = true;
                    }
                });
            });
        });
    }

    fn legend(&self, ui: &mut egui::Ui) {
        if self.scale.metric == ColorMetric::Kind {
            let present: HashSet<&str> = self.model.types().iter().map(|t| t.kind).collect();
            egui::Grid::new("kinds").num_columns(2).spacing([12.0, 4.0]).show(ui, |ui| {
                for (n, (kind, color)) in look::KINDS.iter().filter(|(k, _)| present.contains(k)).enumerate() {
                    ui.horizontal(|ui| {
                        let (r, _) = ui.allocate_exact_size(Vec2::splat(10.0), egui::Sense::hover());
                        ui.painter().rect_filled(r, CornerRadius::same(3), look::egui_rgb(look::rgb(*color)));
                        ui.label(RichText::new(*kind).small());
                    });
                    if n % 2 == 1 {
                        ui.end_row();
                    }
                }
            });
            return;
        }
        let (rect, _) = ui.allocate_exact_size(Vec2::new(210.0, 8.0), egui::Sense::hover());
        let steps = 64;
        for k in 0..steps {
            let x0 = rect.left() + rect.width() * k as f32 / steps as f32;
            let x1 = rect.left() + rect.width() * (k + 1) as f32 / steps as f32 + 0.5;
            let c = look::ramp(k as f64 / (steps - 1) as f64);
            ui.painter().rect_filled(egui::Rect::from_x_y_ranges(x0..=x1, rect.y_range()), CornerRadius::ZERO, look::egui_rgb(c));
        }
        let s = &self.scale;
        let (lo, hi) = (s.show(s.lo), format!("{}+", s.show(s.hi)));
        let (left, right) = if s.metric == ColorMetric::Age { (hi, lo) } else { (lo, hi) };
        let (wl, wr) = s.metric.words();
        for (a, b) in [(left, right), (wl.to_string(), wr.to_string())] {
            ui.horizontal(|ui| {
                ui.label(RichText::new(a).small().weak());
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| ui.label(RichText::new(b).small().weak()));
            });
        }
    }

    fn inspector(&mut self, ctx: &egui::Context, screen: Vec2, actions: &mut Actions) {
        let Some(i) = self.selected else { return };
        let night = self.prefs.theme == Theme::Night;
        let model = self.model.clone();
        let t = &model.types()[i];
        let b = &model.buildings[i];
        egui::Area::new("inspector".into()).anchor(Align2::RIGHT_TOP, Vec2::new(-16.0, 92.0)).show(ctx, |ui| {
            card(night).inner_margin(Margin::same(16)).show(ui, |ui| {
                ui.set_width(330.0);
                let height = (screen.y - 170.0).max(200.0);
                egui::ScrollArea::vertical().max_height(height).min_scrolled_height(height).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.set_max_width(280.0);
                            ui.label(RichText::new(&t.name).strong().size(17.0));
                            let pkg = if t.package.is_empty() { if t.language == crate::model::Language::Java { "(default package)" } else { "(root)" } } else { &t.package };
                            let module = if t.module.is_empty() { String::new() } else { format!(" · {}", t.module) };
                            ui.label(RichText::new(format!("{pkg}{module}")).monospace().small().weak());
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Min), |ui| {
                            if ui.button("×").on_hover_text("Close (Esc)").clicked() {
                                actions.select = Some(None);
                            }
                        });
                    });
                    ui.horizontal(|ui| {
                        badge(ui, t.kind, look::egui_rgb(look::kind_color(t.kind)), Color32::WHITE);
                        badge(ui, t.language.display_name(), ui.visuals().faint_bg_color, ui.visuals().text_color());
                        if t.test {
                            badge(ui, "test", ui.visuals().faint_bg_color, ui.visuals().text_color());
                        }
                    });
                    ui.add_space(6.0);
                    let visible = |j: &&usize| self.visible(**j);
                    let out: Vec<usize> = b.deps.iter().filter(visible).copied().collect();
                    let inc: Vec<usize> = b.incoming.iter().filter(visible).copied().collect();
                    let mut cells: Vec<(String, &str)> = vec![
                        (t.nom().to_string(), "methods"),
                        (t.noa.to_string(), "attributes"),
                        (look::fmt(t.loc as f64), "lines of code"),
                        (t.complexity().to_string(), "complexity"),
                        (if t.nom() == 0 { "0".into() } else { format!("{:.1}", t.complexity() as f64 / t.nom() as f64) }, "cx / method"),
                        (format!("{} / {}", out.len(), inc.len()), "uses / used by"),
                    ];
                    if model.has_git() {
                        let h = b.history;
                        cells.push((h.map(|h| h.commits.to_string()).unwrap_or("0".into()), "commits"));
                        cells.push((h.map(|h| h.authors.to_string()).unwrap_or("0".into()), "authors"));
                        cells.push((h.map(|h| look::rel_time(h.last_commit)).unwrap_or("—".into()), "last change"));
                    }
                    egui::Grid::new("metrics").num_columns(3).spacing([6.0, 6.0]).min_col_width(100.0).show(ui, |ui| {
                        for (n, (value, label)) in cells.iter().enumerate() {
                            Frame::new().fill(ui.visuals().faint_bg_color).corner_radius(CornerRadius::same(10)).inner_margin(Margin::symmetric(9, 7)).show(ui, |ui| {
                                ui.set_width(88.0);
                                ui.vertical(|ui| {
                                    ui.label(RichText::new(value).strong().size(16.0));
                                    ui.label(RichText::new(*label).small().weak());
                                });
                            });
                            if n % 3 == 2 {
                                ui.end_row();
                            }
                        }
                    });
                    ui.add_space(8.0);
                    Frame::new().fill(ui.visuals().faint_bg_color).corner_radius(CornerRadius::same(10)).inner_margin(Margin::symmetric(10, 6)).show(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.add(egui::Label::new(RichText::new(format!("{}:{}", b.file, t.line)).monospace().small().weak()).truncate()).on_hover_text(&b.file);
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                if ui.button("Source").clicked() {
                                    actions.open_source = Some(i);
                                }
                            });
                        });
                    });
                    if let Some(gh) = &model.github {
                        ui.add_space(6.0);
                        ui.horizontal(|ui| {
                            for (label, kind) in [("File on GitHub", "blob"), ("Blame", "blame"), ("History", "commits")] {
                                let url = Self::github_link(gh, kind, &model, i);
                                if ui.button(label).on_hover_text(&url).clicked() {
                                    actions.open_url = Some(url);
                                }
                            }
                        });
                        if let Some(pulls) = &gh.pulls {
                            section(ui, "OPEN PULL REQUESTS", b.prs.len());
                            for &k in &b.prs {
                                let pr = &pulls[k];
                                let updated = parse_iso(&pr.updated_at).map(look::rel_time).unwrap_or_default();
                                let text = format!(
                                    "#{}  {}\n{} · {} · {} files{}",
                                    pr.number,
                                    pr.title,
                                    pr.author.as_deref().unwrap_or("ghost"),
                                    updated,
                                    pr.files.len(),
                                    if pr.draft { " · draft" } else { "" }
                                );
                                if ui.add(egui::Button::new(RichText::new(text).size(12.0)).min_size(Vec2::new(ui.available_width(), 0.0))).on_hover_text(&pr.url).clicked() {
                                    actions.open_url = Some(pr.url.clone());
                                }
                            }
                            if b.prs.is_empty() {
                                let src = gh.pulls_source.clone().unwrap_or_default();
                                ui.label(RichText::new(if src.starts_with("all") { "none".to_string() } else { format!("none among the {src}") }).small().weak());
                            }
                        }
                    }
                    if !b.methods.is_empty() {
                        section(ui, "METHODS BY COMPLEXITY", t.methods.len());
                        let max_cx = b.methods.iter().map(|m| m.complexity).max().unwrap_or(1).max(1);
                        for m in b.methods.iter().take(15) {
                            ui.horizontal(|ui| {
                                ui.add(egui::Label::new(RichText::new(format!("{}({})", m.name, if m.parameters > 0 { "…" } else { "" })).monospace().size(11.5)).truncate());
                                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                    ui.label(RichText::new(format!("cx {} · {} loc", m.complexity, m.loc)).small().weak());
                                });
                            })
                            .response
                            .on_hover_text(format!("line {} · {} LOC · {} params", m.line, m.loc, m.parameters));
                            let (rect, _) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 3.0), egui::Sense::hover());
                            ui.painter().rect_filled(rect, CornerRadius::same(2), ui.visuals().faint_bg_color);
                            let fill = egui::Rect::from_min_size(rect.min, Vec2::new(rect.width() * m.complexity as f32 / max_cx as f32, 3.0));
                            ui.painter().rect_filled(fill, CornerRadius::same(2), look::egui_rgb(look::ramp(m.complexity as f64 / max_cx.max(10) as f64)));
                        }
                        if b.methods.len() > 15 {
                            ui.label(RichText::new(format!("+ {} more", b.methods.len() - 15)).small().weak());
                        }
                    }
                    for (title, list, color) in [("USES", &out, look::OUT), ("USED BY", &inc, look::IN)] {
                        section(ui, title, list.len());
                        ui.horizontal_wrapped(|ui| {
                            for &j in list.iter() {
                                let name = &model.types()[j].name;
                                let r = ui.add(egui::Button::new(RichText::new(name).monospace().size(11.5)).stroke(Stroke::new(1.0_f32, look::egui_rgb(look::rgb(color)))));
                                if r.on_hover_text(&model.types()[j].fqn).clicked() {
                                    actions.select = Some(Some(j));
                                    actions.fly_selected = true;
                                }
                            }
                            if list.is_empty() {
                                ui.label(RichText::new(if title == "USES" { "nothing in this project" } else { "no project types" }).small().weak());
                            }
                        });
                    }
                });
            });
        });
    }

    fn tooltip(&self, ctx: &egui::Context) {
        let Some((x, y)) = self.pointer else { return };
        let text = if let Some(i) = self.hovered {
            let t = &self.model.types()[i];
            let b = &self.model.buildings[i];
            let mut row = format!("{} methods · {} attrs · {} loc · cx {}", t.nom(), t.noa, look::fmt(t.loc as f64), t.complexity());
            if let Some(h) = b.history {
                row.push_str(&format!(" · {} commits", h.commits));
            }
            if !b.prs.is_empty() {
                row.push_str(&format!(" · {} open PR{}", b.prs.len(), if b.prs.len() > 1 { "s" } else { "" }));
            }
            Some((format!("{}  ·  {}", t.name, t.kind), t.package.clone(), row))
        } else if let Some(d) = self.hovered_district {
            let d = &self.model.districts[d];
            (d.depth > 0).then(|| (d.label.clone(), d.id.trim_start_matches("module:").to_string(), format!("{} types · double-click to zoom", d.types)))
        } else {
            None
        };
        let Some((title, sub, row)) = text else { return };
        egui::Area::new("tooltip".into()).fixed_pos(egui::pos2(x + 14.0, y + 14.0)).order(egui::Order::Tooltip).interactable(false).show(ctx, |ui| {
            Frame::new().fill(Color32::from_rgba_unmultiplied(15, 23, 42, 235)).corner_radius(CornerRadius::same(10)).inner_margin(Margin::symmetric(10, 8)).show(ui, |ui| {
                ui.label(RichText::new(title).strong().color(Color32::WHITE));
                ui.label(RichText::new(sub).monospace().small().color(Color32::from_rgb(148, 163, 184)));
                ui.label(RichText::new(row).small().color(Color32::from_rgb(203, 213, 225)));
            });
        });
    }

    fn source_window(&mut self, ctx: &egui::Context) {
        let Some(view) = &mut self.source else { return };
        let mut open = view.open;
        let font = FontId::monospace(12.5);
        let row_height = 18.0;
        let digits = view.lines.len().to_string().len();
        egui::Window::new(RichText::new(&view.title).monospace())
            .id(egui::Id::new("source"))
            .open(&mut open)
            .default_size([980.0, 680.0])
            .frame(Frame::window(&ctx.global_style()).fill(Color32::from_rgb(13, 17, 23)))
            .show(ctx, |ui| {
                let mut area = egui::ScrollArea::both().auto_shrink(false);
                if view.scroll_pending {
                    area = area.vertical_scroll_offset(((view.first.saturating_sub(4)) as f32) * row_height);
                    view.scroll_pending = false;
                }
                area.show_rows(ui, row_height, view.lines.len(), |ui, range| {
                    for n in range {
                        let job = source::layout(&view.lines[n], n + 1, digits, &font);
                        let in_type = n + 1 >= view.first && n + 1 <= view.last;
                        let (rect, _) = ui.allocate_exact_size(Vec2::new(ui.available_width().max(900.0), row_height), egui::Sense::hover());
                        if in_type {
                            ui.painter().rect_filled(rect, CornerRadius::ZERO, Color32::from_rgba_unmultiplied(56, 139, 253, 30));
                            ui.painter().rect_filled(egui::Rect::from_min_size(rect.min, Vec2::new(2.0, row_height)), CornerRadius::ZERO, Color32::from_rgb(56, 139, 253));
                        }
                        let galley = ui.fonts_mut(|f| f.layout_job(job));
                        ui.painter().galley(rect.min + Vec2::new(8.0, 1.0), galley, Color32::PLACEHOLDER);
                    }
                });
            });
        if !open {
            self.source = None;
        } else if let Some(v) = &mut self.source {
            v.open = open;
        }
    }
}

// ------------------------------------------------------------------ widgets & helpers

fn visuals(night: bool) -> egui::Visuals {
    let mut v = if night { egui::Visuals::dark() } else { egui::Visuals::light() };
    v.window_corner_radius = CornerRadius::same(14);
    v.widgets.inactive.corner_radius = CornerRadius::same(8);
    v.widgets.hovered.corner_radius = CornerRadius::same(8);
    v.widgets.active.corner_radius = CornerRadius::same(8);
    v.selection.bg_fill = if night { Color32::from_rgb(37, 99, 235) } else { Color32::from_rgb(37, 99, 235) };
    v.faint_bg_color = if night { Color32::from_rgba_unmultiplied(148, 163, 184, 22) } else { Color32::from_rgba_unmultiplied(15, 23, 42, 12) };
    v
}

fn card(night: bool) -> Frame {
    let fill = if night { Color32::from_rgba_unmultiplied(13, 19, 33, 238) } else { Color32::from_rgba_unmultiplied(255, 255, 255, 240) };
    let stroke = if night { Color32::from_rgba_unmultiplied(148, 163, 184, 35) } else { Color32::from_rgba_unmultiplied(15, 23, 42, 22) };
    Frame::new()
        .fill(fill)
        .stroke(Stroke::new(1.0_f32, stroke))
        .corner_radius(CornerRadius::same(14))
        .shadow(egui::Shadow { offset: [0, 6], blur: 24, spread: 0, color: Color32::from_black_alpha(if night { 90 } else { 30 }) })
}

fn caption(ui: &mut egui::Ui, text: &str) {
    ui.label(RichText::new(text).small().strong().weak());
}

fn section(ui: &mut egui::Ui, title: &str, count: usize) {
    ui.add_space(10.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new(title).small().strong().weak());
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| ui.label(RichText::new(count.to_string()).small().weak()));
    });
}

fn chip(ui: &mut egui::Ui, value: &str, label: &str, night: bool) {
    let fill = if night { Color32::from_rgba_unmultiplied(148, 163, 184, 22) } else { Color32::from_rgba_unmultiplied(15, 23, 42, 12) };
    Frame::new().fill(fill).corner_radius(CornerRadius::same(255)).inner_margin(Margin::symmetric(9, 4)).show(ui, |ui| {
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 5.0;
            if !value.is_empty() {
                ui.label(RichText::new(value).strong());
            }
            ui.label(RichText::new(label).weak());
        });
    });
}

fn badge(ui: &mut egui::Ui, text: &str, fill: Color32, color: Color32) {
    Frame::new().fill(fill).corner_radius(CornerRadius::same(255)).inner_margin(Margin::symmetric(8, 2)).show(ui, |ui| {
        ui.label(RichText::new(text).small().strong().color(color));
    });
}

fn logo(ui: &mut egui::Ui) {
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(30.0), egui::Sense::hover());
    let bars = [(5.0, 16.0, 5.0, 11.0, 0x38bdf8), (11.5, 7.0, 5.0, 20.0, 0xf97316), (18.0, 12.0, 4.0, 15.0, 0xfacc15), (23.5, 19.0, 4.0, 8.0, 0x34d399)];
    for (x, y, w, h, c) in bars {
        let r = egui::Rect::from_min_size(rect.min + Vec2::new(x * 30.0 / 32.0, y * 30.0 / 32.0), Vec2::new(w * 30.0 / 32.0, h * 30.0 / 32.0));
        ui.painter().rect_filled(r, CornerRadius::same(1), look::egui_rgb(look::rgb(c)));
    }
}

fn url_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// Seconds since the epoch for an ISO-8601 UTC timestamp such as `2026-09-30T12:00:00Z`.
fn parse_iso(s: &str) -> Option<i64> {
    let (date, time) = s.split_once('T')?;
    let mut d = date.split('-').map(|x| x.parse::<i64>());
    let (y, m, day) = (d.next()?.ok()?, d.next()?.ok()?, d.next()?.ok()?);
    let mut t = time.trim_end_matches('Z').split(':').map(|x| x.parse::<f64>());
    let (hh, mm, ss) = (t.next()?.ok()?, t.next()?.ok()?, t.next().and_then(|x| x.ok()).unwrap_or(0.0));
    // days-from-civil (Howard Hinnant)
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    Some(days * 86400 + (hh * 3600.0 + mm * 60.0 + ss) as i64)
}
