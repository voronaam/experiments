//! Native desktop viewer: the same city as the web viewer, rendered with OpenGL (three-d) and an egui interface.
//!
//! This is the last stage of the pipeline (walk → parse/analyse → link dependencies → git history → city model →
//! layout → viewers). It takes a finished [`CityModel`] and never re-runs the analysis itself; with `--watch` a
//! background thread rebuilds the model and hands the new one over through a channel (see [`run`]).
//!
//! # Structure
//! - [`App`] owns the per-session state: the model, the camera [`Orbit`], selection/hover/search, building heights
//!   and colours, and the user's [`Prefs`]. Its [`App::frame`] is called once per frame by three-d's render loop.
//! - [`scene::Scene`] owns the GPU side (instanced buildings and districts, shadows, dependency arcs, beacons,
//!   picking). The app only pushes data into it: heights, colours, selection.
//! - [`walk`] is the first-person walk mode's movement and collision logic (toggled with G or the Walk button);
//!   the app feeds it raw winit input ([`RawInput`]) and captures the cursor while walking ([`Capture`]).
//! - [`replay`] is the history replay's playback state and interpolation rules (Replay button or R, for a city built
//!   with `--history`); the app draws its timeline bar and feeds the interpolated heights, sides and colours to the
//!   scene.
//! - [`look`] holds metrics, colour scales and palettes; [`source`] the syntax-highlighted source window; [`png`]
//!   the snapshot encoder.
//!
//! # One frame
//! 1. egui builds the interface ([`App::ui`]). Widgets never change state that the 3D scene depends on directly;
//!    they record what should happen in an [`Actions`] value.
//! 2. [`App::handle_events`] turns the remaining mouse events into camera moves, hovering and picking.
//! 3. [`App::apply`] carries out the collected actions, so selection/recolouring happens once per frame no matter
//!    how many widgets asked for it.
//! 4. Height and camera animations advance, the scene is updated and rendered, and egui is drawn on top.
//!
//! # Indices and coordinates
//! A building index `i` is also the index of its type in `model.types()` (`buildings[i].index == i`, see
//! `city::resolve`), so the two are used interchangeably throughout. World coordinates are those of the layout: `x`
//! left to right, `z` back to front, `y` up, city centred on the origin. three-d reports pointer positions in
//! physical pixels with the origin at the bottom left; egui works in logical points with the origin at the top
//! left, hence the recurring `(viewport.height - y) / dpr` conversions.

mod look;
mod material;
mod png;
mod replay;
mod scene;
mod source;
mod walk;

use crate::city::CityModel;
use crate::github::GitHubInfo;
use look::{ColorMetric, HeightMetric, Scale, Theme};
use replay::Replay;
use scene::{Hit, Scene};
use source::SourceView;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::mpsc;
use std::sync::Arc;
use three_d::egui::{self, Align2, Color32, CornerRadius, FontId, Frame, Margin, RichText, Stroke, Vec2};
use three_d::*;

/// Re-analyses the source tree and returns a fresh model. Called on the watcher's thread, hence `Send`.
pub type Rebuild = Box<dyn Fn() -> Arc<CityModel> + Send>;

/// Opens the window and blocks until it is closed.
///
/// With `watch`, file changes under the root trigger `rebuild` on the watcher's background thread (debounced in
/// [`crate::watch::watch`]). The new model is sent over an mpsc channel and picked up at the start of the next frame,
/// so the GL context and all app state are only ever touched from the render thread. If the watcher cannot be
/// started the viewer still opens, just without live reloading.
pub fn run(model: Arc<CityModel>, watch: Option<(PathBuf, Rebuild)>) -> Result<(), String> {
    // Fail early with a helpful message instead of the windowing backend's error on a headless Linux machine.
    if cfg!(target_os = "linux") && std::env::var_os("DISPLAY").is_none() && std::env::var_os("WAYLAND_DISPLAY").is_none() {
        return Err("no graphical display found (DISPLAY / WAYLAND_DISPLAY unset). Run without --gui to use the browser viewer.".into());
    }
    // The window and event loop are set up by hand rather than with three-d's `Window`, whose render loop keeps the
    // winit window to itself and drops device events: walk mode needs to grab and hide the cursor and to read raw
    // mouse motion (see [`Capture`]). This follows three-d's own `Window::new`/`render_loop` closely, using the
    // building blocks it provides for that purpose (`WindowedContext`, `FrameInputGenerator`).
    use winit::event::{DeviceEvent, Event as WinitEvent, WindowEvent};
    let event_loop = winit::event_loop::EventLoop::new();
    let window = winit::window::WindowBuilder::new()
        .with_title(format!("{} · CodeCity", model.name))
        .with_min_inner_size(winit::dpi::LogicalSize::new(2.0, 2.0))
        .with_inner_size(winit::dpi::LogicalSize::new(1440.0, 900.0))
        .build(&event_loop)
        .map_err(|e| format!("cannot open a window: {e}"))?;
    window.focus_window();
    // 4× multisampling if the driver offers it, else none (as three-d's Window does).
    let mut surface = SurfaceSettings::default();
    let gl = WindowedContext::from_winit_window(&window, surface)
        .or_else(|_| {
            surface.multisamples = 0;
            WindowedContext::from_winit_window(&window, surface)
        })
        .map_err(|e| format!("cannot open a window: {e}"))?;
    let context: Context = (*gl).clone();
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
                eprintln!("cannot watch: {}", crate::printable(&e.to_string()));
                None
            }
        },
        None => None,
    };
    let mut app = App::new(&context, model);
    let mut gui = GUI::new(&context);
    let mut input = FrameInputGenerator::from_winit_window(&window);
    let mut capture = Capture::default();
    // Never returns: the process exits when the loop ends.
    event_loop.run(move |event, _, control_flow| match event {
        WinitEvent::MainEventsCleared => window.request_redraw(),
        WinitEvent::RedrawRequested(_) => {
            // The watcher stops when dropped; borrowing it here moves it into the closure, which lives as long as
            // the window.
            let _keep_watching = &watcher;
            // Drain the channel without blocking. If several rebuilds queued up, each is applied in turn and only
            // the last one stays.
            while let Ok(next) = rx.try_recv() {
                app.set_model(next, true);
            }
            let mut frame = input.generate(&context);
            app.frame(&mut frame, &mut gui);
            // Grab or release the cursor to match the mode the frame ended in.
            capture.sync(&window, &mut app.raw, app.walk.is_some());
            gl.swap_buffers().ok();
            // Redraw continuously, as three-d's default loop does.
            *control_flow = winit::event_loop::ControlFlow::Poll;
            window.request_redraw();
        }
        WinitEvent::WindowEvent { ref event, .. } => {
            input.handle_winit_window_event(event);
            app.raw.window_event(event, app.walk.is_some());
            match event {
                WindowEvent::Resized(size) => gl.resize(*size),
                WindowEvent::ScaleFactorChanged { new_inner_size, .. } => gl.resize(**new_inner_size),
                WindowEvent::CloseRequested => *control_flow = winit::event_loop::ControlFlow::Exit,
                _ => {}
            }
        }
        // Raw (unaccelerated, unclamped) mouse motion: keeps coming while the cursor is locked or held at the centre.
        WinitEvent::DeviceEvent { event: DeviceEvent::MouseMotion { delta }, .. } => {
            if app.walk.is_some() {
                app.raw.mouse.0 += delta.0 as f32;
                app.raw.mouse.1 += delta.1 as f32;
            }
        }
        _ => {}
    })
}

// ------------------------------------------------------------------ walk-mode input

/// A key that matters in walk mode.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
enum WalkKey {
    Forward,
    Back,
    Left,
    Right,
    Run,
    Jump,
    /// G: enter or leave walk mode.
    Toggle,
    /// Esc: leave walk mode.
    Exit,
    /// F: leave, then frame the city.
    Frame,
    /// /: leave, then focus the search field.
    Search,
    /// N: day/night still works while walking.
    Theme,
}

impl WalkKey {
    /// Maps a winit key event to a walk key.
    ///
    /// W/A/S/D and G go by physical position (scancode), so they sit in the same place on AZERTY or Dvorak keyboards:
    /// winit 0.28 reports evdev codes on Linux (X11 and Wayland) and set-1 scancodes on Windows, which agree for
    /// these keys, and macOS virtual key codes on macOS. Everything else goes by the key's meaning.
    fn of(input: &winit::event::KeyboardInput) -> Option<WalkKey> {
        use winit::event::VirtualKeyCode as V;
        // (W, A, S, D, G)
        let codes: [u32; 5] = if cfg!(target_os = "macos") { [13, 0, 1, 2, 5] } else { [0x11, 0x1E, 0x1F, 0x20, 0x22] };
        let physical = [WalkKey::Forward, WalkKey::Left, WalkKey::Back, WalkKey::Right, WalkKey::Toggle];
        if let Some(n) = codes.iter().position(|&c| c == input.scancode) {
            return Some(physical[n]);
        }
        Some(match input.virtual_keycode? {
            V::Up => WalkKey::Forward,
            V::Down => WalkKey::Back,
            V::Left => WalkKey::Left,
            V::Right => WalkKey::Right,
            V::LShift | V::RShift => WalkKey::Run,
            V::Space => WalkKey::Jump,
            V::Escape => WalkKey::Exit,
            V::F => WalkKey::Frame,
            V::Slash | V::NumpadDivide => WalkKey::Search,
            V::N => WalkKey::Theme,
            _ => return None,
        })
    }
}

/// Walk-mode input collected straight from winit, between frames.
///
/// three-d's events are layout-based, carry no focus changes and no raw mouse motion, so walk mode reads winit's
/// events itself. While walking, the three-d events are withheld from egui and the orbit controls altogether (see
/// [`App::frame`]), so egui can neither steal keys nor react to clicks with the hidden cursor.
#[derive(Default)]
struct RawInput {
    /// Keys currently held down.
    held: HashSet<WalkKey>,
    /// Keys pressed since the last frame (key repeat ignored), in order.
    pressed: Vec<WalkKey>,
    /// Mouse motion since the last frame, in pixels (right and down positive).
    mouse: (f32, f32),
    /// Left clicks since the last frame.
    clicks: u32,
    /// The window lost focus since the last frame.
    focus_lost: bool,
}

impl RawInput {
    fn window_event(&mut self, event: &winit::event::WindowEvent, walking: bool) {
        use winit::event::{ElementState, WindowEvent};
        match event {
            WindowEvent::KeyboardInput { input, .. } => {
                let Some(key) = WalkKey::of(input) else { return };
                if input.state == ElementState::Pressed {
                    // insert() is false for a key already held, i.e. an auto-repeat.
                    if self.held.insert(key) {
                        self.pressed.push(key);
                    }
                } else {
                    self.held.remove(&key);
                }
            }
            WindowEvent::MouseInput { state: ElementState::Pressed, button: winit::event::MouseButton::Left, .. } if walking => self.clicks += 1,
            WindowEvent::Focused(false) => {
                self.focus_lost = true;
                // Key releases are not delivered to an unfocused window, so forget what was held.
                self.held.clear();
            }
            _ => {}
        }
    }

    /// The movement keys held right now.
    fn movement(&self) -> walk::Input {
        let h = |k| self.held.contains(&k);
        walk::Input { forward: h(WalkKey::Forward), back: h(WalkKey::Back), left: h(WalkKey::Left), right: h(WalkKey::Right), run: h(WalkKey::Run), jump: h(WalkKey::Jump) }
    }
}

/// Captures and hides the cursor while walking; the look direction always comes from raw device motion.
///
/// winit 0.28 offers two grab modes: `Locked` (the cursor stays put; Wayland, macOS) and `Confined` (it stays inside
/// the window; X11, Windows). `Locked` is used where available. `Confined` is not: on X11 the pointer grab behind it
/// makes the X server deliver every raw motion event twice, which doubled the mouse sensitivity. Without a lock the
/// hidden cursor is instead moved back to the window centre every frame, so it never reaches a screen edge or leaves
/// the window (moving it there produces no raw motion).
#[derive(Default)]
struct Capture {
    active: bool,
    /// The cursor could not be locked: it is put back at the window centre every frame.
    pin: bool,
}

impl Capture {
    fn sync(&mut self, window: &winit::window::Window, raw: &mut RawInput, walking: bool) {
        use winit::window::CursorGrabMode;
        if walking && !self.active {
            self.pin = window.set_cursor_grab(CursorGrabMode::Locked).is_err();
            window.set_cursor_visible(false);
            raw.mouse = (0.0, 0.0);
            self.active = true;
        } else if !walking && self.active {
            window.set_cursor_grab(CursorGrabMode::None).ok();
            window.set_cursor_visible(true);
            self.active = false;
        }
        if self.active && self.pin {
            let size = window.inner_size();
            window.set_cursor_position(winit::dpi::PhysicalPosition::new(size.width / 2, size.height / 2)).ok();
        }
    }
}

// ------------------------------------------------------------------ preferences

/// View settings that survive restarts, stored as JSON in `$XDG_CONFIG_HOME/codecity/desktop.json` (or
/// `~/.config/codecity/desktop.json`). They are saved whenever the user changes one.
struct Prefs {
    height: HeightMetric,
    /// The colour metric the user last picked. `None` means "never chosen": [`App::set_model`] then picks a default
    /// that suits the model (churn with git history, complexity without).
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

    /// Reads the settings file. A missing or malformed file, or a missing or unknown key, silently falls back to the
    /// default for that setting, so old or hand-edited files never stop the viewer from starting.
    fn load() -> Prefs {
        // A missing/unparsable file gives Value::Null, and indexing Null yields Null, so every lookup below falls
        // through to its default.
        let v: serde_json::Value = std::fs::read_to_string(Self::path()).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        let s = |k: &str| v[k].as_str().unwrap_or("").to_string();
        let b = |k: &str, d: bool| v[k].as_bool().unwrap_or(d);
        Prefs {
            // Metrics are stored by their Debug name ("Nom", "Commits", …), matching what save() writes.
            height: HeightMetric::ALL.into_iter().find(|m| format!("{m:?}") == s("height")).unwrap_or(HeightMetric::Nom),
            color: ColorMetric::ALL.into_iter().find(|m| format!("{m:?}") == s("color")),
            theme: if s("theme") == "night" { Theme::Night } else { Theme::Day },
            show_tests: b("tests", true),
            labels: b("labels", true),
            beacons: b("beacons", true),
            shadows: b("shadows", true),
        }
    }

    /// Writes the settings file, creating its directory if needed. Errors are ignored: losing a view preference is not
    /// worth interrupting the user for.
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

/// An orbit camera: the eye sits on a sphere of radius `distance` around `target` and always looks at it.
///
/// `yaw` is the angle around the vertical axis (radians; 0 puts the eye on the `+z` side, i.e. in front of the
/// city), `pitch` the elevation above the ground plane (0 is level, π/2 straight down from above). Mouse handling
/// keeps pitch in `0.07..=1.5`: never quite level with the ground and never exactly overhead, where the view
/// direction would be parallel to the up vector and the view matrix degenerate.
#[derive(Clone, Copy)]
struct Orbit {
    target: Vec3,
    yaw: f32,
    pitch: f32,
    distance: f32,
}

impl Orbit {
    /// Eye position: spherical to Cartesian coordinates around the target.
    fn position(&self) -> Vec3 {
        self.target + vec3(self.pitch.cos() * self.yaw.sin(), self.pitch.sin(), self.pitch.cos() * self.yaw.cos()) * self.distance
    }
}

/// A camera move in progress. The target and the distance are animated, and the pitch when a near-level view is tilted
/// up; yaw stays where it is, so a flight zooms and slides but never swings the view around. Times are in seconds of
/// [`App::time`].
struct Flight {
    from: Orbit,
    to_target: Vec3,
    to_distance: f32,
    to_pitch: f32,
    start: f64,
    duration: f64,
}

/// Buildings growing or shrinking to new heights, one entry per building (indexed like `model.buildings`).
struct Animation {
    from: Vec<f32>,
    to: Vec<f32>,
    start: f64,
    duration: f64,
}

/// What the interface and the mouse asked for during one frame; carried out together by [`App::apply`].
///
/// The egui closures borrow `self` while drawing, so they cannot call methods like [`App::select`] that need the
/// whole app mutably. Collecting requests here also means several triggers in one frame (e.g. a combo box and the
/// test checkbox both asking for a recolour) cost a single update.
#[derive(Default)]
struct Actions {
    /// `Some(Some(i))` selects building `i`, `Some(None)` clears the selection, `None` leaves it alone.
    select: Option<Option<usize>>,
    /// Fly the camera to the newly selected building.
    fly_selected: bool,
    /// Recompute colours (and save prefs, as the colour metric may have changed).
    recolor: bool,
    /// Recompute heights (and save prefs).
    reheight: bool,
    /// Toggle day/night.
    theme: bool,
    frame_all: bool,
    snapshot: bool,
    open_source: Option<usize>,
    open_url: Option<String>,
    /// Enter or leave walk mode (the Walk button).
    walk: bool,
    /// Enter or leave the history replay (the Replay and Now buttons, R, Esc).
    replay: bool,
}

/// First-person walk mode in progress (see [`walk`]).
struct Walking {
    walker: walk::Walker,
    /// The orbit camera when walking started, restored exactly on leaving.
    saved: Orbit,
    /// When walking started, in [`App::time`] seconds, for fading out the key hint.
    since: f64,
}

// ------------------------------------------------------------------ the app

/// The viewer's state. Everything here is owned by the render thread; see the module docs for the frame sequence.
struct App {
    model: Arc<CityModel>,
    scene: Scene,
    /// three-d camera, recomputed from `orbit` every frame.
    camera: Camera,
    orbit: Orbit,
    flight: Option<Flight>,
    prefs: Prefs,
    /// The colour metric in effect. Can differ from `prefs.color` when the preferred one is unavailable for this
    /// model (e.g. churn without git history).
    color_metric: ColorMetric,
    selected: Option<usize>,
    /// Building under the pointer, brightened in [`App::recolor`].
    hovered: Option<usize>,
    /// District under the pointer (when no building is), for the tooltip and double-click zoom.
    hovered_district: Option<usize>,
    /// Pointer in egui coordinates (logical points, origin top left), or `None` when it is over a panel or outside
    /// the window, which hides the tooltip.
    pointer: Option<(f32, f32)>,
    /// Where the left button went down (physical pixels), to tell a click from an orbit drag on release.
    press: Option<(f32, f32)>,
    /// Time and district of the last click on a district, for double-click detection.
    last_click: (f64, Option<usize>),
    search: String,
    search_focused: bool,
    /// The search text or the test filter changed: re-run the search at the end of [`App::ui`].
    search_dirty: bool,
    /// pointer over an egui panel this frame: three-d only marks events handled while egui drags something
    over_gui: bool,
    /// Indices of every visible type matching the search (`None` when no search is active); the rest are dimmed.
    matches: Option<HashSet<usize>>,
    /// The best few matches, in rank order, for the dropdown under the search field.
    results: Vec<usize>,
    /// Current, possibly mid-animation, height of every building. 0 hides it (filtered-out test code, gone types).
    heights: Vec<f32>,
    /// Footprint side of every building: its lot's side, except during a replay, where it follows the snapshots.
    sides: Vec<f32>,
    /// Which districts are drawn (indexed like `model.districts`): all of them, except during a replay.
    district_shown: Vec<bool>,
    /// The history replay, when active.
    replay: Option<Replay>,
    animation: Option<Animation>,
    /// Final colour of every building, after search/selection dimming and hover highlight.
    colors: Vec<Srgba>,
    /// Value range of the colour metric, also drawn as the legend.
    scale: Scale,
    /// Heights or colours changed: push them to the GPU before the next render.
    buildings_dirty: bool,
    /// Heights changed (a subset of `buildings_dirty`): the shadow map must be re-rendered too.
    geometry_dirty: bool,
    /// District labels: text, the world position of its anchor (front edge, on top of the platform) and the district's
    /// index.
    labels: Vec<(String, Vec3, usize)>,
    source: Option<SourceView>,
    /// Message and the time (in `time` seconds) until which it is shown.
    toast: Option<(String, f64)>,
    /// Seconds since the window opened; all animations are timed against it.
    time: f64,
    /// Theme the egui visuals were last set for, so they are only rebuilt when the theme changes.
    visuals_theme: Option<Theme>,
    /// Walk mode, when active.
    walk: Option<Walking>,
    /// Walk-mode input read directly from winit (filled by the event loop in [`run`]).
    raw: RawInput,
    /// A text field had keyboard focus at the end of the last [`App::ui`]: G then types instead of toggling.
    typing: bool,
    /// Focus the search field in the next [`App::ui`] (after leaving walk mode with /).
    focus_search: bool,
}

impl App {
    fn new(context: &Context, model: Arc<CityModel>) -> App {
        let prefs = Prefs::load();
        let scene = Scene::new(context);
        // Placeholder viewport, position and far plane: frame() sets the real ones before the first render.
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
            sides: vec![],
            district_shown: vec![],
            replay: None,
            animation: None,
            colors: vec![],
            buildings_dirty: true,
            geometry_dirty: true,
            labels: vec![],
            source: None,
            toast: None,
            time: 0.0,
            visuals_theme: None,
            walk: None,
            raw: RawInput::default(),
            typing: false,
            focus_search: false,
        };
        // set_model does the real initialisation (heights, colours, labels, camera); the fields above are just empty.
        app.set_model(model, false);
        app
    }

    /// Whether building `i` is shown: test code can be hidden with the "Test code" checkbox, and a type that exists
    /// only in the past (a gone type of a history city) only appears during a replay. During a replay, a building is
    /// shown when it exists in the current snapshot (`round(p)`): this is what search results and arcs include.
    fn visible(&self, i: usize) -> bool {
        let b = &self.model.buildings[i];
        let tests_ok = self.prefs.show_tests || !self.model.types()[b.index].test;
        match &self.replay {
            Some(r) => tests_ok && b.hist.as_ref().is_some_and(|h| h.exists(r.snapshot())),
            None => tests_ok && !b.gone,
        }
    }

    /// Whether building `i` is part of the present-day city as displayed: [`App::visible`] outside a replay.
    fn present(&self, i: usize) -> bool {
        let b = &self.model.buildings[i];
        !b.gone && (self.prefs.show_tests || !self.model.types()[b.index].test)
    }

    /// Switches to a new model: on start-up (`live == false`) or after a rebuild by the file watcher (`live == true`).
    ///
    /// A live update tries not to disturb the user: the camera stays put, buildings animate from their old heights
    /// to the new ones rather than rising from the ground, the selection is restored by fully qualified name (indices
    /// are not stable across rebuilds), and an active search is re-run. On start-up the city rises from the ground
    /// and the camera frames the whole city. Old heights are matched by fully qualified name, so types added in the
    /// middle grow from the ground and the others animate from their own old height.
    fn set_model(&mut self, next: Arc<CityModel>, live: bool) {
        // Remember the selection by name before the indices change meaning.
        let previous = self.selected.map(|i| self.model.types()[i].fqn.clone());
        let old: HashMap<String, f32> = if live {
            let types = self.model.types();
            self.model.buildings.iter().zip(&self.heights).map(|(b, &h)| (types[b.index].fqn.clone(), h)).collect()
        } else {
            HashMap::new()
        };
        self.model = next;
        self.selected = None;
        self.hovered = None;
        self.replay = None;
        self.sides = self.model.buildings.iter().map(|b| b.lot.side as f32).collect();
        self.district_shown = self.present_districts();
        // The saved metric if this model supports it, else churn when there is git history, else complexity.
        self.color_metric = match self.prefs.color {
            Some(c) if c.unavailable(&self.model).is_none() => c,
            _ => if self.model.has_git() { ColorMetric::Commits } else { ColorMetric::Cx },
        };
        let n = self.model.buildings.len();
        let types = self.model.types();
        self.heights = self.model.buildings.iter().map(|b| old.get(&types[b.index].fqn).copied().unwrap_or(0.0)).collect();
        self.geometry_dirty = true;
        self.scene.set_model(&self.model, &self.prefs.theme.palette());
        self.scene.set_district_mask(&self.model, &self.prefs.theme.palette(), Some(self.district_shown.clone()));
        self.scene.apply_palette(&self.model, &self.prefs.theme.palette());
        self.scene.shadows = self.prefs.shadows;
        self.build_labels();
        // Order matters: reheight needs scene.radius (set by scene.set_model) and recolor sets the scale the
        // selection below dims against.
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

    /// Chooses which districts get a floating name label.
    ///
    /// Labelling every district would bury the city in text, so only districts at nesting depth 1 and 2 (modules and
    /// top-level packages; depth 0 is the city itself) that contain at least one type are candidates. The 24 largest
    /// by area are considered, biggest first, and a label is skipped if its anchor is within 8 units sideways and
    /// 3 units in depth of one already placed, since the two would overlap on screen. At most 16 labels are kept.
    ///
    /// Each label is anchored at the middle of the district's front edge (`z + d/2`, as `z` runs back to front), just
    /// above its platform: a district at depth `n` has its top at `(n + 1) * PLATFORM`, as in the layout.
    fn build_labels(&mut self) {
        let mut candidates: Vec<_> = self.model.districts.iter().enumerate().filter(|(_, d)| (1..=2).contains(&d.depth) && d.types > 0).collect();
        candidates.sort_by(|(_, a), (_, b)| (b.w * b.d).total_cmp(&(a.w * a.d)));
        let mut anchors: Vec<(f64, f64)> = vec![];
        self.labels.clear();
        for (index, d) in candidates.into_iter().take(24) {
            let front = d.z + d.d / 2.0;
            if anchors.len() >= 16 || anchors.iter().any(|(x, z)| (x - d.x).abs() < 8.0 && (z - front).abs() < 3.0) {
                continue;
            }
            anchors.push((d.x, front));
            let y = (d.depth + 1) as f64 * crate::layout::PLATFORM + 0.2;
            self.labels.push((d.label.clone(), vec3(d.x as f32, y as f32, front as f32), index));
        }
    }

    /// Computes every building's height for the current height metric and starts animating towards it.
    ///
    /// Heights are linear in the metric, scaled so that the 95th-percentile visible building is 20% of the city's
    /// radius tall, kept between 8 and 16 units. Scaling to a percentile rather than the maximum keeps one giant
    /// class from flattening everything else; the outliers above it are capped at 75% of the radius instead. Every
    /// visible building gets a 0.35 base so that types with a metric of 0 still show up. Hidden test types (and the gone
    /// types of a history city) get 0,
    /// which [`Scene::set_buildings`] treats as "not drawn".
    ///
    /// `rise` starts from the ground (initial load); otherwise buildings morph from their current heights.
    ///
    /// During a replay this only re-measures the present-day scale the replay draws with (see [`App::replay_geometry`]).
    fn reheight(&mut self, rise: bool) {
        let metric = self.prefs.height;
        let types = self.model.types();
        let scale = self.height_scale();
        if let Some(r) = &mut self.replay {
            r.scale = scale;
            r.drawn = None;
            return;
        }
        let r = self.scene.radius as f64;
        let to: Vec<f32> = (0..types.len())
            .map(|i| if self.visible(i) { (0.35 + metric.value(&types[i]) * scale).min(r * 0.75) as f32 } else { 0.0 })
            .collect();
        // A morph starts from the current, possibly mid-animation, heights, so interrupting an animation is smooth.
        let from = if rise { vec![0.0; to.len()] } else { self.heights.clone() };
        self.animation = Some(Animation { from, to, start: self.time, duration: if rise { 1.4 } else { 0.65 } });
    }

    /// Height per unit of the height metric, for the present-day city (see [`App::reheight`]): the 95th-percentile
    /// visible building is 20% of the city's radius tall, kept between 8 and 16 units. A replay keeps this scale.
    fn height_scale(&self) -> f64 {
        let metric = self.prefs.height;
        let types = self.model.types();
        let mut values: Vec<f64> = (0..types.len()).filter(|&i| self.present(i)).map(|i| metric.value(&types[i])).collect();
        values.sort_by(f64::total_cmp);
        // Nearest-rank percentile; at least 1 so an all-zero metric does not divide by zero.
        let p95 = if values.is_empty() { 1.0 } else { values[((0.95 * (values.len() - 1) as f64) as usize).min(values.len() - 1)] }.max(1.0);
        (self.scene.radius as f64 * 0.2).clamp(8.0, 16.0) / p95
    }

    /// Recomputes every building's colour from the colour metric, then layers on the interaction state:
    ///
    /// - With a search active, buildings that do not match are mixed 80% towards the palette's dim colour.
    /// - With a selection, everything except the selected building and its direct neighbours (what it uses and what
    ///   uses it) is dimmed the same way, so the dependency arcs stand out.
    /// - The hovered building is lightened 30% towards white (unless it is the selected one).
    ///
    /// Called whenever any of these change, including on every hover change; the result reaches the GPU in the next
    /// frame via `buildings_dirty`.
    ///
    /// During a replay the colours are those of the snapshot `round(p)` on the present-day scale (see
    /// [`replay::color_value`]).
    fn recolor(&mut self) {
        let m = &self.model;
        let at = self.replay.as_ref().map(|r| r.snapshot()).zip(m.history.as_ref());
        let metric = if at.is_some() { replay::color_metric(self.color_metric) } else { self.color_metric };
        // The scale (value range) depends only on the metric and the model, but recomputing it here is cheap and
        // keeps it in step after a model or metric change.
        self.scale = Scale::of(metric, m);
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
                let t = &m.types()[b.index];
                let mut c = match (at, &b.hist) {
                    (Some((k, snapshots)), Some(h)) => self.scale.color_of(replay::color_value(metric, h, k, &snapshots[k]), t.kind),
                    _ => self.scale.color(t, b),
                };
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

    /// Selects building `i` (or clears the selection) and rebuilds what depends on it: the dependency arcs, the
    /// translucent marker box around the building, and the colours. With `fly`, the camera also flies to it.
    ///
    /// Arcs and marker are built for the *target* heights, so when called mid-animation they already fit the
    /// buildings' final shape; [`App::frame`] calls this again once a height animation ends.
    fn select(&mut self, i: Option<usize>, fly: bool) {
        self.selected = i;
        let night = self.prefs.theme == Theme::Night;
        let heights = self.target_heights();
        // Collected up front because show_arcs borrows the scene mutably and so cannot call self.visible.
        let visible: Vec<bool> = (0..self.model.buildings.len()).map(|j| self.visible(j)).collect();
        self.scene.show_arcs(&self.model, i, &heights, |j| visible[j], night);
        self.scene.set_marker(&self.model, i, &heights, &self.sides, night);
        self.recolor();
        if let (Some(i), true) = (i, fly) {
            self.fly_to_building(i);
        }
    }

    /// The heights buildings are heading for: the running animation's end state, or the current heights if none.
    fn target_heights(&self) -> Vec<f32> {
        self.animation.as_ref().map(|a| a.to.clone()).unwrap_or_else(|| self.heights.clone())
    }

    /// Applies the current day/night palette to the scene. Re-selecting rebuilds arcs and marker, whose colours depend
    /// on the theme, and recolours the buildings with the new dim colour.
    fn apply_theme(&mut self) {
        let palette = self.prefs.theme.palette();
        self.scene.apply_palette(&self.model, &palette);
        self.select(self.selected, false);
    }

    /// Points the camera at the whole city from the default three-quarter view (yaw 0.6, pitch 0.62 rad, ≈ 36° above
    /// the horizon). `scene.radius` is half the city's diagonal (at least 20), so at 2.6 radii the whole city fits in
    /// the 42° field of view. When animated, only target and distance fly; the current viewing angle is kept (see
    /// [`Flight`]).
    fn frame_all(&mut self, animate: bool) {
        let r = self.scene.radius;
        let target = Orbit { target: Vec3::zero(), yaw: 0.6, pitch: 0.62, distance: r * 2.6 };
        if animate {
            self.fly(target.target, target.distance);
        } else {
            self.orbit = target;
        }
    }

    /// Starts a 0.9 s camera flight to a new target and distance.
    fn fly(&mut self, to_target: Vec3, to_distance: f32) {
        // Tilt a near-level view up to at least 0.35 rad (≈ 20°) during the flight so it looks down onto the
        // destination.
        let to_pitch = self.orbit.pitch.max(0.35);
        self.flight = Some(Flight { from: self.orbit, to_target, to_distance, to_pitch, start: self.time, duration: 0.9 });
    }

    /// Flies to a building, aiming at the middle of its final height. The distance is enough to see a tall building
    /// whole (1.8 × height) or a wide one with some neighbourhood (7 × side), and never closer than 22 units.
    fn fly_to_building(&mut self, i: usize) {
        let b = &self.model.buildings[i];
        let h = self.target_heights()[i];
        // lot.y is the top of the district platform the building stands on.
        let t = vec3(b.lot.x as f32, b.lot.y as f32 + h * 0.5, b.lot.z as f32);
        self.fly(t, (h * 1.8).max(b.lot.side as f32 * 7.0).max(22.0));
    }

    /// Flies to a district (on double-click), aiming at the centre of its platform's top surface and backing off
    /// far enough to see the whole platform.
    fn fly_to_district(&mut self, d: usize) {
        let d = &self.model.districts[d];
        let t = vec3(d.x as f32, (d.depth + 1) as f32 * crate::layout::PLATFORM as f32, d.z as f32);
        self.fly(t, (d.w.max(d.d) * 1.25 + 10.0) as f32);
    }

    /// Searches the visible types for the query (case-insensitive) and updates `matches`/`results` and the colours.
    ///
    /// Ranking, best first: exact simple name (0), name prefix (1), name substring (2), match anywhere in the fully
    /// qualified name (3); within a rank, shorter names come first. The sort is
    /// stable, so equal entries keep model order. Every match is highlighted in the city; only the top 12 are listed.
    /// Queries shorter than two characters clear the search.
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

    /// Shows a short message at the bottom of the window for 2.6 s, replacing any current one.
    fn toast(&mut self, text: String) {
        self.toast = Some((text, self.time + 2.6));
    }

    /// Opens the source window on the file declaring type `i`, scrolled to and highlighting the type's lines.
    ///
    /// The file is read from disk now, so it shows the current contents even if they changed since the analysis. The
    /// rules are those of the browser viewer's `api/source`: only a regular file is read (a symbolic link or FIFO
    /// swapped in for it is not followed or opened), files over 4 MiB are not loaded, invalid UTF-8 is replaced
    /// rather than rejected, and a read error is shown as the window's only line.
    fn open_source(&mut self, i: usize) {
        let t = &self.model.types()[i];
        let b = &self.model.buildings[i];
        let unreadable = || format!("// unable to read {}", b.file);
        let code = match std::fs::symlink_metadata(&t.file) {
            Ok(m) if !m.is_file() => unreadable(),
            Ok(m) if m.len() > 4 * 1024 * 1024 => "// file too large to display".to_string(),
            Ok(_) => std::fs::read(&t.file).map(|bytes| String::from_utf8_lossy(&bytes).into_owned()).unwrap_or_else(|_| unreadable()),
            Err(_) => unreadable(),
        };
        self.source = Some(SourceView {
            title: format!("{}:{}", b.file, t.line),
            lines: source::highlight(&code, t.language),
            first: t.line as usize,
            last: t.end_line as usize,
            scroll_pending: true,
            open: true,
        });
    }

    /// GitHub web URL for type `i`'s file: `kind` is the URL segment, `blob` (file view), `blame` or `commits`
    /// (history), giving `https://<host>/<owner>/<repo>/<kind>/<ref>/<prefix><file>`.
    ///
    /// `gh.prefix` is the analysed root's path inside the repository with a trailing slash (empty when the root is
    /// the repository's top level),
    /// since `Building::file` is relative to the analysed root. Each path segment is percent-encoded separately so
    /// the slashes survive. File and blame views get a `#Lstart-Lend` anchor that highlights the type's lines; the
    /// history page has no line anchors.
    fn github_link(gh: &GitHubInfo, kind: &str, model: &CityModel, i: usize) -> String {
        let t = &model.types()[i];
        let enc = |p: &str| p.split('/').map(url_encode).collect::<Vec<_>>().join("/");
        let path = enc(&format!("{}{}", gh.prefix, model.buildings[i].file));
        let url = format!("{}/{}/{}/{}", gh.repo.web_url(), kind, enc(&gh.git_ref), path);
        if kind == "commits" { url } else { format!("{url}#L{}-L{}", t.line, t.end_line) }
    }

    // -------------------------------------------------------------- per frame

    /// One frame: interface, input, actions, animations, then rendering (see the module docs for why in this order).
    ///
    /// The window redraws continuously; nothing here waits for input. All times are in seconds, converted from
    /// three-d's milliseconds.
    fn frame(&mut self, frame: &mut FrameInput, gui: &mut GUI) -> FrameOutput {
        let dt = (frame.elapsed_time / 1000.0) as f32;
        self.time = frame.accumulated_time / 1000.0;
        let viewport = frame.viewport;
        let dpr = frame.device_pixel_ratio;
        let mut actions = Actions::default();

        // While walking, input comes from `raw` instead: egui and the orbit controls get no presses, clicks, motion
        // or text, so they can neither steal the movement keys nor act on clicks of the hidden cursor. Releases and
        // modifier changes still go through, so egui does not think a key is stuck when walking ends.
        let walking = self.walk.is_some();
        if walking {
            frame.events.retain(|e| matches!(e, Event::KeyRelease { .. } | Event::ModifiersChange { .. } | Event::MouseLeave));
        }
        // Enter or leave walk mode before the interface is built, so it already shows the new mode in this frame.
        self.walk_keys(&mut actions);
        // egui sees the events first and marks those it consumes as handled; handle_events skips those.
        gui.update(&mut frame.events, frame.accumulated_time, viewport, dpr, |ui| {
            self.ui(ui, viewport, dpr, &mut actions);
        });
        if !walking {
            self.handle_events(&frame.events, dpr, viewport, &mut actions);
        }
        self.apply(&actions);
        self.update_replay(dt as f64);

        // height animation
        if let Some(a) = &self.animation {
            let k = ((self.time - a.start) / a.duration).clamp(0.0, 1.0) as f32;
            // Ease-out cubic: fast at first, settling gently.
            let e = 1.0 - (1.0 - k).powi(3);
            self.heights = a.from.iter().zip(&a.to).map(|(f, t)| f + (t - f) * e).collect();
            self.buildings_dirty = true;
            self.geometry_dirty = true;
            if k >= 1.0 {
                // Done: re-select so arcs and marker are rebuilt for the final heights.
                self.animation = None;
                let s = self.selected;
                self.select(s, false);
            }
        }
        if let Some(f) = &self.flight {
            let k = ((self.time - f.start) / f.duration).clamp(0.0, 1.0) as f32;
            // Ease-in-out cubic: the camera accelerates away and decelerates into place.
            let e = if k < 0.5 { 4.0 * k * k * k } else { 1.0 - (-2.0 * k + 2.0).powi(3) / 2.0 };
            self.orbit.target = f.from.target + (f.to_target - f.from.target) * e;
            self.orbit.distance = f.from.distance + (f.to_distance - f.from.distance) * e;
            self.orbit.pitch = f.from.pitch + (f.to_pitch - f.from.pitch) * e;
            if k >= 1.0 {
                self.flight = None;
            }
        }
        // Dependency-arc particles and PR beacons move every frame.
        // PR beacons are a present-day overlay: off during a replay.
        self.scene.animate(&self.model, &self.heights, dt, self.time as f32, self.prefs.beacons && self.replay.is_none());

        self.camera.set_viewport(viewport);
        let r = self.scene.radius;
        // 42° vertical field of view; the far plane scales with the city (the ground plane reaches 7 radii out and
        // the camera can zoom out to 6); the near plane is fixed at 0.3.
        self.camera.set_perspective_projection(degrees(42.0), 0.3, r * 30.0);
        if let Some(w) = &mut self.walk {
            // Mouse look, then one physics step against the city at its current (possibly animating) heights.
            let (dx, dy) = std::mem::take(&mut self.raw.mouse);
            w.walker.look(dx, dy);
            let world = walk::World::from_model(&self.model, &self.heights, &self.sides, &self.district_shown);
            w.walker.step(dt, self.raw.movement(), &world);
            let ([ex, ey, ez], [vx, vy, vz]) = (w.walker.eye(), w.walker.direction());
            let eye = vec3(ex, ey, ez);
            self.camera.set_perspective_projection(degrees(walk::FOV), walk::NEAR, r * 30.0);
            self.camera.set_view(eye, eye + vec3(vx, vy, vz), Vec3::unit_y());
            // Sharp shadows at street level: fit the shadow map to the area around the player, centred 12 units
            // ahead along the horizontal view direction (so it reaches 8 behind and 32 ahead), where most of what is
            // seen lies.
            let ahead = vec3(-w.walker.yaw.sin(), 0.0, -w.walker.yaw.cos()) * 12.0;
            self.scene.set_shadow_focus(Some(vec3(ex, 0.0, ez) + ahead));
        } else {
            self.camera.set_view(self.orbit.position(), self.orbit.target, Vec3::unit_y());
            self.scene.set_shadow_focus(None);
        }
        if self.walk.is_some() {
            self.walk_target(viewport);
        }
        // Rewrites all building instances (marking the shadow map stale only if heights changed); done at most once
        // per frame however many changes asked for it.
        if self.buildings_dirty {
            self.scene.set_buildings(&self.model, &self.heights, &self.sides, &self.colors, self.geometry_dirty);
            self.buildings_dirty = false;
            self.geometry_dirty = false;
        }

        let bg = self.prefs.theme.palette().background;
        let screen = frame.screen();
        screen.clear(ClearState::color_and_depth(bg.r as f32 / 255.0, bg.g as f32 / 255.0, bg.b as f32 / 255.0, 1.0, 1.0));
        self.scene.render(&screen, &self.camera);
        // Read back before egui is drawn, so the snapshot shows only the city, without panels.
        if actions.snapshot {
            self.snapshot(&screen, viewport);
        }
        screen.write(|| gui.render()).ok();
        FrameOutput::default()
    }

    /// Carries out what the interface and mouse asked for this frame. Selection goes first so that a recolour in the
    /// same frame already sees the new selection.
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
        if a.walk {
            if self.walk.is_some() {
                self.exit_walk();
            } else {
                self.enter_walk();
            }
        }
        if a.replay {
            if self.replay.is_some() {
                self.exit_replay();
            } else {
                self.enter_replay();
            }
        }
        if a.frame_all {
            self.frame_all(true);
        }
        if let Some(i) = a.open_source {
            self.open_source(i);
        }
        if let Some(url) = &a.open_url {
            // Only https:// links go to the browser (see look::is_web_link): PR URLs come from the GitHub API.
            if !look::is_web_link(url) {
                self.toast("Not opening a link that is not https://".into());
            } else if let Err(e) = webbrowser::open(url) {
                self.toast(format!("Could not open browser: {e}"));
            }
        }
    }

    // -------------------------------------------------------------- history replay

    /// The districts of the present-day view: all of them, except in a history city those whose `types` is 0, which
    /// hold only gone types (and so would be empty platforms today).
    fn present_districts(&self) -> Vec<bool> {
        let history = self.model.history.is_some();
        self.model.districts.iter().map(|d| !history || d.types > 0).collect()
    }

    /// Starts the replay at the first snapshot, playing (the Replay button or R). The present-day height scale is
    /// measured now and kept, so the city grows rather than being renormalised at every snapshot. The next
    /// [`App::update_replay`] draws the first snapshot.
    fn enter_replay(&mut self) {
        let Some(snapshots) = &self.model.history else { return };
        if self.replay.is_some() || snapshots.is_empty() {
            return;
        }
        let replay = Replay::new(snapshots.len(), self.height_scale());
        self.replay = Some(replay);
        self.animation = None;
    }

    /// Leaves the replay (the Now button, R or Esc) and puts the present-day city back as it was: present footprints,
    /// districts and colours at once, heights animating back as after a height-metric change, and the search and arcs for today's buildings. A
    /// gone type cannot stay selected, as it is not part of today's city.
    fn exit_replay(&mut self) {
        if self.replay.take().is_none() {
            return;
        }
        self.sides = self.model.buildings.iter().map(|b| b.lot.side as f32).collect();
        self.district_shown = self.present_districts();
        self.scene.set_district_mask(&self.model, &self.prefs.theme.palette(), Some(self.district_shown.clone()));
        if self.selected.is_some_and(|i| self.model.buildings[i].gone) {
            self.selected = None;
        }
        // Heights morph back like after a height-metric change; everything else has switched at once.
        self.reheight(false);
        if !self.search.is_empty() {
            self.run_search();
        }
        let s = self.selected;
        self.select(s, false);
    }

    /// Advances playback by `dt` seconds and brings the scene up to date: geometry whenever the position moved, and
    /// whatever depends on the nearest snapshot (colours, districts, search, arcs) whenever that changed.
    fn update_replay(&mut self, dt: f64) {
        let Some(r) = &mut self.replay else { return };
        r.advance(dt);
        let (p, k) = (r.p, r.snapshot());
        let redraw = r.drawn != Some(p);
        let reshow = r.shown != Some(k);
        r.drawn = Some(p);
        r.shown = Some(k);
        if redraw {
            self.replay_geometry();
        }
        if reshow {
            self.district_shown = self.model.district_hist.iter().map(|alive| alive[k]).collect();
            self.scene.set_district_mask(&self.model, &self.prefs.theme.palette(), Some(self.district_shown.clone()));
            if !self.search.is_empty() {
                self.run_search();
            }
            // Rebuilds the arcs (to the buildings that exist now) and recolours.
            let s = self.selected;
            self.select(s, false);
        }
    }

    /// Heights and footprint sides at the replay position (see [`replay::metric_at`] and [`replay::side_at`]): the
    /// height metric interpolated between the snapshots on either side, on the present-day scale with the usual 0.35
    /// base and cap; hidden where the type exists in neither snapshot, or is filtered-out test code.
    fn replay_geometry(&mut self) {
        let Some(r) = &self.replay else { return };
        let at = r.frame();
        let cap = self.scene.radius as f64 * 0.75;
        let metric = self.prefs.height;
        let types = self.model.types();
        for (i, b) in self.model.buildings.iter().enumerate() {
            let tests_ok = self.prefs.show_tests || !types[b.index].test;
            let shape = b.hist.as_ref().filter(|_| tests_ok).and_then(|h| replay::metric_at(h, metric, at).map(|v| (v, replay::side_at(h, at))));
            (self.heights[i], self.sides[i]) = match shape {
                Some((v, side)) => ((0.35 + v * r.scale).min(cap) as f32, side as f32),
                None => (0.0, 0.0),
            };
        }
        self.animation = None;
        self.buildings_dirty = true;
        self.geometry_dirty = true;
        let night = self.prefs.theme == Theme::Night;
        self.scene.set_marker(&self.model, self.selected, &self.heights, &self.sides, night);
    }

    // -------------------------------------------------------------- walk mode

    /// Starts walking. With a building selected, the player stands on the street in front of it (its `+z` side, just
    /// clear of the wall) and faces it; otherwise at the orbit target, facing the way the orbit camera looks. A spot
    /// inside a building is resolved by [`walk::Walker::spawn`]. The orbit camera is kept for [`App::exit_walk`].
    fn enter_walk(&mut self) {
        if self.walk.is_some() {
            return;
        }
        self.flight = None;
        let world = walk::World::from_model(&self.model, &self.heights, &self.sides, &self.district_shown);
        let (x, z, yaw) = match self.selected {
            Some(i) => {
                let lot = &self.model.buildings[i].lot;
                (lot.x as f32, lot.z as f32 + lot.side as f32 / 2.0 + walk::RADIUS + 0.2, 0.0)
            }
            // The orbit yaw uses the walker's convention (see walk), so the view direction carries over.
            None => (self.orbit.target.x, self.orbit.target.z, self.orbit.yaw),
        };
        self.walk = Some(Walking { walker: walk::Walker::spawn(&world, x, z, yaw), saved: self.orbit, since: self.time });
        // Motion and clicks from before walking started must not turn the view or select anything.
        self.raw.mouse = (0.0, 0.0);
        self.raw.clicks = 0;
        self.press = None;
        self.pointer = None;
        self.hovered_district = None;
    }

    /// Stops walking and puts the orbit camera back exactly where it was. The selection stays.
    fn exit_walk(&mut self) {
        if let Some(w) = self.walk.take() {
            self.orbit = w.saved;
            self.flight = None;
            self.hovered_district = None;
            if self.hovered.take().is_some() {
                self.recolor();
            }
        }
    }

    /// Acts on the keys pressed since the last frame (read from winit, see [`RawInput`]).
    ///
    /// G toggles walk mode unless a text field has focus. While walking: Esc leaves; F and / leave and then do their
    /// usual thing (frame the city, focus the search); N still switches day/night. Losing window focus leaves too.
    /// Outside walk mode Esc, F, / and N are handled by egui in [`App::ui`] as before.
    fn walk_keys(&mut self, actions: &mut Actions) {
        let pressed = std::mem::take(&mut self.raw.pressed);
        if std::mem::take(&mut self.raw.focus_lost) {
            self.exit_walk();
        }
        for key in pressed {
            let walking = self.walk.is_some();
            match key {
                WalkKey::Toggle if walking => self.exit_walk(),
                WalkKey::Toggle if !self.typing => self.enter_walk(),
                WalkKey::Exit if walking => self.exit_walk(),
                WalkKey::Frame if walking => {
                    self.exit_walk();
                    actions.frame_all = true;
                }
                WalkKey::Search if walking => {
                    self.exit_walk();
                    self.focus_search = true;
                }
                WalkKey::Theme if walking => actions.theme = true,
                _ => {}
            }
        }
    }

    /// While walking, the building under the crosshair (the window centre) is hovered: highlighted, with its tooltip
    /// just below the crosshair. A left click selects it like a normal click, or clears the selection over empty
    /// space or a district; the camera does not fly anywhere and the cursor stays captured.
    fn walk_target(&mut self, viewport: Viewport) {
        let centre = PhysicalPoint { x: viewport.width as f32 / 2.0, y: viewport.height as f32 / 2.0 };
        let (b, d) = match self.scene.pick(&self.camera, centre) {
            Some(Hit::Building(i)) => (Some(i), None),
            Some(Hit::District(d)) => (None, Some(d)),
            None => (None, None),
        };
        self.hovered_district = d;
        if b != self.hovered {
            self.hovered = b;
            self.recolor();
        }
        if std::mem::take(&mut self.raw.clicks) > 0 && (b.is_some() || self.selected.is_some()) {
            self.select(b, false);
        }
    }

    /// Mouse input on the 3D view.
    ///
    /// - Left drag orbits (yaw/pitch), right or middle drag pans across the ground, the wheel zooms.
    /// - A left press and release less than 5 logical pixels apart is a click: on a building it selects and flies
    ///   there; on empty ground or a district it clears the selection; two clicks on the same district within 0.4 s
    ///   zoom to it.
    /// - Plain movement picks the building or district under the pointer for hover highlighting and the tooltip.
    ///
    /// Any manual camera input cancels a running flight, so the user is never fighting an animation.
    ///
    /// Events over an egui panel are ignored: three-d only flags an event as handled while egui is actively using
    /// the pointer, so a click on a panel's background would otherwise also hit the city behind it (see `over_gui`).
    fn handle_events(&mut self, events: &[Event], dpr: f32, viewport: Viewport, actions: &mut Actions) {
        let over_gui = self.over_gui;
        for event in events {
            let handled = match event {
                Event::MousePress { handled, .. } | Event::MouseRelease { handled, .. } | Event::MouseWheel { handled, .. } => *handled,
                _ => false,
            };
            // Over a panel, drop presses, releases and wheel events egui did not claim. Motion still goes through,
            // so a camera drag that started over the city keeps working when the pointer crosses a panel.
            if over_gui && !handled && !matches!(event, Event::MouseMotion { .. } | Event::MouseLeave) {
                // A release over a panel ends any pending click, so it cannot fire later.
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
                    // Positions are in physical pixels, so the 5-point click tolerance is scaled by the DPI factor.
                    let clicked = self.press.is_some_and(|(x, y)| (x - position.x).hypot(y - position.y) < 5.0 * dpr);
                    self.press = None;
                    if clicked {
                        // GPU picking: the nearest building or district platform under the pixel, if any.
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
                    // Hovering over a panel hides tooltip and highlight; a drag in progress (button held) is not
                    // blocked, see above.
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
                            // 0.006 rad per pixel. Pitch stays between just above the ground and just short of
                            // straight down (π/2 ≈ 1.571), see Orbit.
                            self.orbit.yaw -= delta.0 * 0.006;
                            self.orbit.pitch = (self.orbit.pitch + delta.1 * 0.006).clamp(0.07, 1.5);
                        }
                        Some(MouseButton::Right) | Some(MouseButton::Middle) => {
                            self.flight = None;
                            // Pan speed proportional to distance, so a drag moves the view by a similar fraction of
                            // the screen whether zoomed in or out.
                            let k = self.orbit.distance * 0.0016;
                            // The camera's right and forward directions projected onto the ground plane: the eye
                            // sits at (sin yaw, ·, cos yaw) from the target and looks back towards it. Panning moves
                            // only the target, horizontally, so the view never tilts.
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
                    // Exponential zoom: each wheel step changes the distance by the same factor, which feels
                    // uniform at any distance. Limited to 4 units in and 6 city radii out.
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

    /// Saves the rendered city (without the interface) as a PNG named `<city>-codecity-<epoch seconds>.png` in
    /// `~/Pictures` if that exists, otherwise in the current directory. Uses the full physical resolution.
    fn snapshot(&mut self, screen: &RenderTarget, viewport: Viewport) {
        let pixels: Vec<[u8; 4]> = screen.read_color(); // rows already run top to bottom
        let (w, h) = (viewport.width, viewport.height);
        // The image is always fully opaque, whatever alpha the framebuffer holds.
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

    /// Builds the whole interface for this frame: floating cards over the 3D view (labels, top bar, controls,
    /// inspector, tooltip, source window, hint and toast) plus the keyboard shortcuts.
    ///
    /// egui is immediate-mode: all of this is rebuilt every frame from the current state. Panels are free-floating
    /// `Area`s rather than side panels, so the city fills the whole window behind them.
    fn ui(&mut self, ui: &mut egui::Ui, viewport: Viewport, dpr: f32, actions: &mut Actions) {
        let ctx = ui.ctx().clone();
        let night = self.prefs.theme == Theme::Night;
        // Recorded here, before handle_events runs for this frame, so it can ignore clicks that land on a panel.
        self.over_gui = ctx.is_pointer_over_egui() || ctx.egui_is_using_pointer();
        // set_visuals replaces egui's whole style, so only do it when the theme actually changed.
        if self.visuals_theme != Some(self.prefs.theme) {
            ctx.set_visuals(visuals(night));
            self.visuals_theme = Some(self.prefs.theme);
        }
        // Window size in egui's logical points.
        let screen = Vec2::new(viewport.width as f32 / dpr, viewport.height as f32 / dpr);
        self.draw_labels(&ctx, viewport, dpr, night);
        self.top_bar(&ctx, screen, actions);
        self.controls(&ctx, actions);
        self.inspector(&ctx, screen, actions);
        self.tooltip(&ctx, screen);
        self.source_window(&ctx);

        // The key hint: the orbit controls, or while walking the walk controls, fading out over 0.5 s after 6 s.
        let (hint, opacity) = match &self.walk {
            Some(w) => ("WASD move · Mouse look · Shift run · Space jump · Click select · G/Esc exit", (1.0 - (self.time - w.since - 6.0) / 0.5).clamp(0.0, 1.0) as f32),
            None => ("drag to orbit · right-drag to pan · scroll to zoom · click a building · / search · F frame · N night · G walk · Esc clear", 1.0),
        };
        // During a replay the timeline bar takes the bottom centre: the orbit hint gives way, the walk hint and the
        // toast move up above the bar.
        let replaying = self.replay.is_some();
        if replaying {
            self.timeline(&ctx, actions);
        }
        let lift = if replaying { 50.0 } else { 0.0 };
        if opacity > 0.0 && (self.walk.is_some() || !replaying) {
            egui::Area::new("hint".into()).anchor(Align2::CENTER_BOTTOM, Vec2::new(0.0, -14.0 - lift)).interactable(false).show(&ctx, |ui| {
                ui.multiply_opacity(opacity);
                card(night).corner_radius(CornerRadius::same(255)).inner_margin(Margin::symmetric(12, 6)).show(ui, |ui| {
                    ui.label(RichText::new(hint).small().weak());
                });
            });
        }
        if self.walk.is_some() {
            // Crosshair: two thin 14-point lines crossing at the window centre, dark by day and light at night.
            let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("crosshair")));
            let c = egui::pos2(screen.x / 2.0, screen.y / 2.0);
            let stroke = Stroke::new(1.5_f32, if night { Color32::from_rgba_unmultiplied(241, 245, 249, 220) } else { Color32::from_rgba_unmultiplied(15, 23, 42, 220) });
            painter.line_segment([c - Vec2::new(7.0, 0.0), c + Vec2::new(7.0, 0.0)], stroke);
            painter.line_segment([c - Vec2::new(0.0, 7.0), c + Vec2::new(0.0, 7.0)], stroke);
            // No text field may keep keyboard focus while walking (keys go to walk mode, see App::frame).
            ctx.memory_mut(|m| m.surrender_focus(egui::Id::new("search")));
        }
        // The toast expires lazily: it is dropped by the first frame drawn after its deadline.
        if let Some((text, until)) = self.toast.clone() {
            if self.time < until {
                egui::Area::new("toast".into()).anchor(Align2::CENTER_BOTTOM, Vec2::new(0.0, -58.0 - lift)).interactable(false).show(&ctx, |ui| {
                    card(night).corner_radius(CornerRadius::same(255)).inner_margin(Margin::symmetric(14, 8)).show(ui, |ui| ui.strong(text));
                });
            } else {
                self.toast = None;
            }
        }

        // keyboard shortcuts (unless typing)
        let typing = ctx.egui_wants_keyboard_input();
        // Remembered for walk_keys, which runs after the interface: G typed into the search field must not toggle.
        self.typing = typing;
        ctx.input(|i| {
            // Escape always works, even in the search field, and peels back one layer at a time: source window,
            // then selection, then search.
            // During a replay, leaving it comes right after the source window (walk mode, which comes first, takes
            // its own keys) and before the selection and the search.
            if i.key_pressed(egui::Key::Escape) {
                if self.source.as_ref().is_some_and(|s| s.open) {
                    self.source = None;
                } else if self.replay.is_some() {
                    actions.replay = true;
                } else if self.selected.is_some() {
                    actions.select = Some(None);
                } else if !self.search.is_empty() {
                    self.search.clear();
                    self.run_search_later();
                }
            }
            if !typing {
                // R toggles the replay (a city built with --history). While replaying: P plays or pauses, comma and
                // period step a snapshot back or forward, Home and End jump to the first or last one.
                if i.key_pressed(egui::Key::R) && self.model.history.is_some() {
                    actions.replay = true;
                }
                if let Some(r) = &mut self.replay {
                    if i.key_pressed(egui::Key::P) {
                        r.toggle();
                    }
                    if i.key_pressed(egui::Key::Comma) {
                        r.step(-1);
                    }
                    if i.key_pressed(egui::Key::Period) {
                        r.step(1);
                    }
                    if i.key_pressed(egui::Key::Home) {
                        r.seek(0);
                    }
                    if i.key_pressed(egui::Key::End) {
                        r.seek(r.count - 1);
                    }
                }
                if i.key_pressed(egui::Key::F) {
                    actions.frame_all = true;
                }
                if i.key_pressed(egui::Key::N) {
                    actions.theme = true;
                }
            }
        });
        // Outside ctx.input: memory_mut cannot be called while the input lock is held.
        if std::mem::take(&mut self.focus_search) || (!typing && ctx.input(|i| i.key_pressed(egui::Key::Slash))) {
            ctx.memory_mut(|m| m.request_focus(egui::Id::new("search")));
        }
        // Searches requested anywhere above run once, now that all widgets have had their say.
        if self.search_dirty {
            self.search_dirty = false;
            self.run_search();
        }
    }

    /// The replay's timeline bar at the bottom centre: play/pause, a slider over the snapshots, the date, short commit
    /// id, subject and author of the nearest snapshot, the speed (cycling 1×, 2×, 4×, 0.5×) and Now, which leaves the
    /// replay. Dragging the slider moves the position and pauses.
    fn timeline(&mut self, ctx: &egui::Context, actions: &mut Actions) {
        let night = self.prefs.theme == Theme::Night;
        let model = self.model.clone();
        let (Some(snapshots), Some(r)) = (&model.history, &mut self.replay) else { return };
        egui::Area::new("timeline".into()).anchor(Align2::CENTER_BOTTOM, Vec2::new(0.0, -14.0)).show(ctx, |ui| {
            card(night).corner_radius(CornerRadius::same(255)).inner_margin(Margin::symmetric(14, 8)).show(ui, |ui| {
                ui.horizontal(|ui| {
                    // ▶ when paused, ❚❚ when playing. egui's fonts have no ❚, so the two bars are painted onto an
                    // empty button in the text colour instead.
                    let play = ui.add_sized([30.0, 24.0], egui::Button::new(if r.playing { "" } else { "▶" }));
                    if r.playing {
                        let c = play.rect.center();
                        let color = ui.visuals().widgets.style(&play).fg_stroke.color;
                        for dx in [-3.5, 3.5] {
                            ui.painter().rect_filled(egui::Rect::from_center_size(c + Vec2::new(dx, 0.0), Vec2::new(3.0, 11.0)), CornerRadius::same(1), color);
                        }
                    }
                    if play.on_hover_text("Play / pause (P)").clicked() {
                        r.toggle();
                    }
                    ui.spacing_mut().slider_width = 260.0;
                    // A single snapshot leaves nothing to slide over.
                    let end = (r.count - 1) as f64;
                    let slider = ui.add_enabled(r.count > 1, egui::Slider::new(&mut r.p, 0.0..=end.max(1.0)).show_value(false));
                    if slider.changed() || slider.dragged() {
                        r.playing = false;
                    }
                    ui.add_sized([440.0, 20.0], egui::Label::new(RichText::new(replay::caption(&snapshots[r.snapshot()])).small()).truncate());
                    if ui.add_sized([44.0, 24.0], egui::Button::new(r.speed_label())).on_hover_text("Playback speed").clicked() {
                        r.next_speed();
                    }
                    if ui.button("Now").on_hover_text("Back to today (R / Esc)").clicked() {
                        actions.replay = true;
                    }
                });
            });
        });
    }

    /// Marks the search for re-running at the end of [`App::ui`].
    fn run_search_later(&mut self) {
        self.search_dirty = true;
    }

    /// Draws the district labels chosen by [`App::build_labels`] as pills at their projected screen positions.
    ///
    /// They go on egui's background layer, so every panel is drawn over them. Labels are re-projected each frame
    /// and do not scale with distance.
    fn draw_labels(&self, ctx: &egui::Context, viewport: Viewport, dpr: f32, night: bool) {
        // Hidden while walking: at street level they would show through the walls.
        if !self.prefs.labels || self.walk.is_some() {
            return;
        }
        let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Background, egui::Id::new("labels")));
        let cam = self.camera.position();
        let dir = self.camera.view_direction();
        let font = FontId::proportional(11.0);
        for (text, pos, d) in &self.labels {
            // A district without buildings yet (during a replay) has no label either.
            if !self.district_shown[*d] {
                continue;
            }
            // Behind the camera: the projection would mirror it back onto the screen, so skip it.
            if (*pos - cam).dot(dir) <= 0.0 {
                continue;
            }
            // three-d physical pixels (origin bottom left) to egui points (origin top left).
            let p = self.camera.pixel_at_position(*pos);
            let at = egui::pos2(p.x / dpr, (viewport.height as f32 - p.y) / dpr);
            let galley = painter.layout_no_wrap(text.clone(), font.clone(), if night { Color32::from_rgb(226, 232, 240) } else { Color32::from_rgb(15, 23, 42) });
            // The pill sits just above the anchor point, padded 8 points left/right and 3 above/below the text.
            let rect = egui::Rect::from_center_size(at - Vec2::new(0.0, galley.size().y / 2.0 + 4.0), galley.size() + Vec2::new(16.0, 6.0));
            let fill = if night { Color32::from_rgba_unmultiplied(13, 19, 33, 215) } else { Color32::from_rgba_unmultiplied(255, 255, 255, 215) };
            painter.rect(rect, CornerRadius::same(255), fill, Stroke::new(1.0_f32, Color32::from_black_alpha(25)), egui::StrokeKind::Inside);
            // PLACEHOLDER means "use the colour the galley was laid out with".
            painter.galley(rect.center() - galley.size() / 2.0, galley, Color32::PLACEHOLDER);
        }
    }

    /// The bar across the top: logo and project name, the search field with its results dropdown, and the stats
    /// chips (GitHub repository, languages, sizes, parse errors) on the right. Enter in the search field selects the
    /// best match.
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
                    // A single-line TextEdit gives up focus on Enter, so this is "Enter pressed in the field".
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
                    // "+" marks a capped PR list: pulls_source is "all open PRs" only when every open PR was
                    // fetched (see github.rs).
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
                        // Integer percentage rounded to nearest; a single language is shown without a number.
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
        // results dropdown under the search field, shown while a search is active and the field (or the
        // "search_results" id) has focus.
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

    /// The left-hand card: height and colour metric pickers, the colour legend, display toggles and the
    /// Day/Night, Frame and Snapshot buttons. Settings that affect the scene go through `actions`; pure display
    /// toggles (labels, beacons, shadows) take effect directly and are saved straight away.
    fn controls(&mut self, ctx: &egui::Context, actions: &mut Actions) {
        let night = self.prefs.theme == Theme::Night;
        let replaying = self.replay.is_some();
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
                // During a replay, open PRs (which only exist today) show as churn.
                let shown = if replaying { replay::color_metric(self.color_metric) } else { self.color_metric };
                egui::ComboBox::from_id_salt("colour").width(210.0).selected_text(shown.label()).show_ui(ui, |ui| {
                    for m in ColorMetric::ALL {
                        // Metrics without data (no git history, no GitHub PRs) are listed but disabled, with the
                        // reason appended to the label; so are open PRs during a replay.
                        let why = if replaying && m == ColorMetric::Prs { Some(" (today only)") } else { m.unavailable(&self.model) };
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
                // Hiding tests changes which buildings exist: heights (hidden = 0), colours, the search results and
                // possibly the selection. The reheight action also saves the preference.
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
                // Only offered when there are pull requests to show.
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
                    // Three equal buttons filling the 210-point card width, minus the two gaps between them.
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
                // Full width below the three; drawn selected while walking.
                let walk = egui::Button::new(if self.walk.is_some() { "Walking  (G / Esc to stop)" } else { "Walk  (G)" }).selected(self.walk.is_some());
                if ui.add_sized([210.0, 28.0], walk).on_hover_text("Walk through the city in first person").clicked() {
                    actions.walk = true;
                }
                // Only for a city built with --history; drawn selected while replaying.
                if self.model.history.is_some() {
                    let label = if replaying { "Replaying  (R / Esc to stop)" } else { "Replay  (R)" };
                    if ui.add_sized([210.0, 28.0], egui::Button::new(label).selected(replaying)).on_hover_text("Watch the city grow commit by commit").clicked() {
                        actions.replay = true;
                    }
                }
            });
        });
    }

    /// The colour legend under the colour picker.
    ///
    /// For "Type kind" it is a two-column list of the kinds that actually occur in this model. For every other
    /// metric it is the colour ramp with the scale's low and high values (the high one marked "+", as the scale is
    /// clipped there) and the metric's descriptive words. Recency is inverted on the ramp (see `Scale::color`), so its
    /// values are swapped: the oldest age is on the left under "dormant", the newest on the right.
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
        // The gradient is drawn as 64 thin rectangles; each is widened by half a point so no seams show between them.
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

    /// The right-hand card for the selected type: name, package and badges; a grid of metrics (plus git history when
    /// available); file location with a Source button; GitHub links and open PRs touching the file; the most complex
    /// methods; and clickable lists of the types it uses and is used by (hidden test types left out).
    fn inspector(&mut self, ctx: &egui::Context, screen: Vec2, actions: &mut Actions) {
        let Some(i) = self.selected else { return };
        let night = self.prefs.theme == Theme::Night;
        let model = self.model.clone();
        let t = &model.types()[i];
        let b = &model.buildings[i];
        egui::Area::new("inspector".into()).anchor(Align2::RIGHT_TOP, Vec2::new(-16.0, 92.0)).show(ctx, |ui| {
            card(night).inner_margin(Margin::same(16)).show(ui, |ui| {
                ui.set_width(330.0);
                // Window height minus 170 points (the card starts 92 points down), but never below 200.
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
                    // A gone type, and during a replay the snapshot's own numbers above the present-day ones.
                    if b.gone {
                        ui.add_space(4.0);
                        ui.label(RichText::new("Removed before today").small().strong().weak());
                    }
                    if let (Some(r), Some(h), Some(snapshots)) = (&self.replay, &b.hist, &model.history) {
                        let k = r.snapshot();
                        ui.add_space(4.0);
                        ui.label(RichText::new(format!("Replaying — values at {}", replay::local_date(snapshots[k].time))).small().strong());
                        let v = |x: i32| if h.exists(k) { look::fmt(x as f64) } else { "—".into() };
                        egui::Grid::new("replay-metrics").num_columns(4).spacing([6.0, 4.0]).show(ui, |ui| {
                            for (value, label) in [(v(h.nom[k]), "methods"), (v(h.noa[k]), "attributes"), (v(h.loc[k]), "LOC"), (v(h.cx[k]), "complexity")] {
                                ui.vertical(|ui| {
                                    ui.label(RichText::new(value).strong());
                                    ui.label(RichText::new(label).small().weak());
                                });
                            }
                        });
                    }
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
                        // b.methods is already sorted by complexity, then size (and capped) in city.rs; the header
                        // counts all of the type's methods.
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
                            // Bar length is relative to the type's most complex method; its colour is relative to
                            // at least 10, so a type whose methods are all simple stays at the low end of the ramp.
                            let fill = egui::Rect::from_min_size(rect.min, Vec2::new(rect.width() * m.complexity as f32 / max_cx as f32, 3.0));
                            ui.painter().rect_filled(fill, CornerRadius::same(2), look::egui_rgb(look::ramp(m.complexity as f64 / max_cx.max(10) as f64)));
                        }
                        // Counted on all of the type's methods like the header, not on the capped b.methods.
                        if t.methods.len() > 15 {
                            ui.label(RichText::new(format!("+ {} more", t.methods.len() - 15)).small().weak());
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

    /// The hover card next to the pointer: a building's name, kind, package and key numbers, or for a district its
    /// label, id and type count. The city platform itself (depth 0) gets no tooltip.
    ///
    /// While walking it shows the building or district under the crosshair, centred just below it.
    fn tooltip(&self, ctx: &egui::Context, screen: Vec2) {
        // Anchor point and which corner of the card sits on it.
        let (at, pivot) = match self.pointer {
            _ if self.walk.is_some() => (egui::pos2(screen.x / 2.0, screen.y / 2.0 + 18.0), Align2::CENTER_TOP),
            Some((x, y)) => (egui::pos2(x + 14.0, y + 14.0), Align2::LEFT_TOP),
            None => return,
        };
        let text = if let Some(i) = self.hovered {
            let t = &self.model.types()[i];
            let b = &self.model.buildings[i];
            let mut row = format!("{} methods · {} attrs · {} loc · cx {}", t.nom(), t.noa, look::fmt(t.loc as f64), t.complexity());
            if let Some(h) = b.history {
                row.push_str(&format!(" · {} commits", h.commits));
            }
            // During a replay: the snapshot's numbers instead, and no PRs (they belong to today).
            if let (Some(r), Some(h), Some(snapshots)) = (&self.replay, &b.hist, &self.model.history) {
                let k = r.snapshot();
                let date = replay::local_date(snapshots[k].time);
                row = if h.exists(k) {
                    format!("{} methods · {} attrs · {} loc · cx {} · {} commits · at {date}", h.nom[k], h.noa[k], look::fmt(h.loc[k] as f64), h.cx[k], h.commits[k])
                } else {
                    format!("not in this snapshot · at {date}")
                };
            } else if !b.prs.is_empty() {
                row.push_str(&format!(" · {} open PR{}", b.prs.len(), if b.prs.len() > 1 { "s" } else { "" }));
            }
            Some((format!("{}  ·  {}", t.name, t.kind), t.package.clone(), row))
        } else if let Some(d) = self.hovered_district {
            let d = &self.model.districts[d];
            // Module district ids carry a "module:" prefix in the layout to keep them apart from packages; it is
            // not meant for display.
            (d.depth > 0).then(|| (d.label.clone(), d.id.trim_start_matches("module:").to_string(), format!("{} types{}", d.types, if self.walk.is_some() { "" } else { " · double-click to zoom" })))
        } else {
            None
        };
        let Some((title, sub, row)) = text else { return };
        egui::Area::new("tooltip".into()).fixed_pos(at).pivot(pivot).order(egui::Order::Tooltip).interactable(false).show(ctx, |ui| {
            Frame::new().fill(Color32::from_rgba_unmultiplied(15, 23, 42, 235)).corner_radius(CornerRadius::same(10)).inner_margin(Margin::symmetric(10, 8)).show(ui, |ui| {
                // One line per row, never wrapped: a centred card (walk mode) is otherwise squeezed to the width
                // egui remembered for it.
                ui.style_mut().wrap_mode = Some(egui::TextWrapMode::Extend);
                ui.label(RichText::new(title).strong().color(Color32::WHITE));
                ui.label(RichText::new(sub).monospace().small().color(Color32::from_rgb(148, 163, 184)));
                ui.label(RichText::new(row).small().color(Color32::from_rgb(203, 213, 225)));
            });
        });
    }

    /// The syntax-highlighted source window opened by [`App::open_source`].
    ///
    /// Rows have a fixed height so `show_rows` only lays out the lines that are visible, which keeps large files
    /// fast. The selected type's lines get a blue tint and a bar in the gutter.
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
                // Scroll once, on opening, so the type's first line has three lines of context above it; afterwards
                // the user scrolls.
                if view.scroll_pending {
                    area = area.vertical_scroll_offset(((view.first.saturating_sub(4)) as f32) * row_height);
                    view.scroll_pending = false;
                }
                area.show_rows(ui, row_height, view.lines.len(), |ui, range| {
                    for n in range {
                        let job = source::layout(&view.lines[n], n + 1, digits, &font);
                        // `n` is 0-based, the type's range 1-based.
                        let in_type = n + 1 >= view.first && n + 1 <= view.last;
                        // At least 900 points wide so long lines can be scrolled to horizontally.
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
        // The window's close button cleared `open`: drop the view and its highlighted lines.
        if !open {
            self.source = None;
        } else if let Some(v) = &mut self.source {
            v.open = open;
        }
    }
}

// ------------------------------------------------------------------ widgets & helpers

/// egui's stock light or dark style with rounder corners, a blue selection colour and a translucent "faint"
/// background that the metric tiles and chips sit on.
fn visuals(night: bool) -> egui::Visuals {
    let mut v = if night { egui::Visuals::dark() } else { egui::Visuals::light() };
    v.window_corner_radius = CornerRadius::same(14);
    v.widgets.inactive.corner_radius = CornerRadius::same(8);
    v.widgets.hovered.corner_radius = CornerRadius::same(8);
    v.widgets.active.corner_radius = CornerRadius::same(8);
    // Selected items and selected text: the accent blue under egui's light selection text at night; a pale blue by day,
    // where egui draws selected text in dark blue.
    v.selection.bg_fill = if night { Color32::from_rgb(37, 99, 235) } else { Color32::from_rgb(191, 219, 254) };
    v.faint_bg_color = if night { Color32::from_rgba_unmultiplied(148, 163, 184, 22) } else { Color32::from_rgba_unmultiplied(15, 23, 42, 12) };
    v
}

/// The frame every floating panel uses: nearly opaque fill, hairline border, rounded corners and a soft drop shadow
/// so it stands out against the city.
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

/// A section header in the inspector: title on the left, item count on the right.
fn section(ui: &mut egui::Ui, title: &str, count: usize) {
    ui.add_space(10.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new(title).small().strong().weak());
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| ui.label(RichText::new(count.to_string()).small().weak()));
    });
}

/// A pill-shaped stat in the top bar: bold value then label, e.g. "1,234 types". An empty value shows only the label.
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

/// The CodeCity logo: four coloured bars, like a skyline, drawn in a 30-point square.
fn logo(ui: &mut egui::Ui) {
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(30.0), egui::Sense::hover());
    // (x, y, width, height, colour) on a 32-unit grid, scaled to 30 points below.
    let bars = [(5.0, 16.0, 5.0, 11.0, 0x38bdf8), (11.5, 7.0, 5.0, 20.0, 0xf97316), (18.0, 12.0, 4.0, 15.0, 0xfacc15), (23.5, 19.0, 4.0, 8.0, 0x34d399)];
    for (x, y, w, h, c) in bars {
        let r = egui::Rect::from_min_size(rect.min + Vec2::new(x * 30.0 / 32.0, y * 30.0 / 32.0), Vec2::new(w * 30.0 / 32.0, h * 30.0 / 32.0));
        ui.painter().rect_filled(r, CornerRadius::same(1), look::egui_rgb(look::rgb(c)));
    }
}

/// Percent-encodes one URL path segment: everything except RFC 3986 unreserved characters (`A–Z a–z 0–9 - _ . ~`) is
/// written as `%XX` per UTF-8 byte. `/` is encoded too, so callers encode segment by segment.
fn url_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// Seconds since the epoch for an ISO-8601 timestamp such as `2026-09-30T12:00:00Z` or `2026-09-30T14:00:00+02:00`.
///
/// Used for pull requests' `updated_at` from the GitHub API, which comes in UTC with a `Z` suffix. Fractional
/// seconds are accepted and truncated, and missing seconds count as 0. A `±hh:mm`, `±hhmm` or `±hh` offset is
/// subtracted to get UTC; a time without `Z` or offset is taken as UTC. Returns `None` if the date, the hours and
/// minutes or the offset do not parse.
fn parse_iso(s: &str) -> Option<i64> {
    let (date, time) = s.split_once('T')?;
    let mut d = date.split('-').map(|x| x.parse::<i64>());
    let (y, m, day) = (d.next()?.ok()?, d.next()?.ok()?, d.next()?.ok()?);
    // The time part contains no '+' or '-' of its own, so one of them starts the offset.
    let (time, offset) = match time.find(|c| c == '+' || c == '-') {
        Some(p) => {
            let (time, zone) = time.split_at(p);
            let digits = zone[1..].replace(':', "");
            let hours: i64 = digits.get(..2)?.parse().ok()?;
            let minutes: i64 = if digits.len() > 2 { digits[2..].parse().ok()? } else { 0 };
            (time, if zone.starts_with('-') { -1 } else { 1 } * (hours * 3600 + minutes * 60))
        }
        None => (time.trim_end_matches('Z'), 0),
    };
    let mut t = time.split(':').map(|x| x.parse::<f64>());
    let (hh, mm, ss) = (t.next()?.ok()?, t.next()?.ok()?, t.next().and_then(|x| x.ok()).unwrap_or(0.0));
    // days-from-civil (Howard Hinnant): proleptic Gregorian date to days since 1970-01-01. Shifting the year to start
    // in March puts the leap day at the end, so the day of the year (doy) follows from the month by a closed formula:
    // (153 * month + 2) / 5 counts the days of the months before it with March = 0. An era is a 400-year Gregorian
    // cycle of 146097 days, and 719468 is the day number of 1970-01-01 counted from 0000-03-01.
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    Some(days * 86400 + (hh * 3600.0 + mm * 60.0 + ss) as i64 - offset)
}

#[cfg(test)]
mod tests {
    #[test]
    fn parse_iso_offsets() {
        let utc = super::parse_iso("2026-09-30T12:00:00Z");
        assert_eq!(utc, Some(1_790_769_600));
        assert_eq!(super::parse_iso("2026-09-30T14:00:00+02:00"), utc);
        assert_eq!(super::parse_iso("2026-09-30T06:30:00.5-0530"), utc);
        assert_eq!(super::parse_iso("2026-09-30T12:00"), utc);
    }
}
