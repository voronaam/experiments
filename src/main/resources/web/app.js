// CodeCity viewer — renders the city model produced by the Java analyser.
//
// Data: the city JSON comes either from a <script id="city-data"> element (a page written by --export) or from
// GET api/city on the local server, which also pushes an `update` server-sent event after each watch-mode rebuild.
// Coordinates come straight from the layout: x/z on the ground with the city centred on the origin, y up.
//
// Rendering: every building is one instance of a single InstancedMesh (unit box, base at y = 0) and every district
// platform one instance of another, so thousands of classes cost two instanced draws. Instance i of the building
// mesh is building i of city.buildings: its matrix carries position, footprint and (animated) height, its instance
// colour the metric colour (dimmed or highlighted by search, selection and hover). Picking raycasts against those
// meshes and reads back hit.instanceId. Night mode patches the standard material's shader (makeBuildingMaterial) for
// glow and lit windows. The desktop viewer (desktop/CityView3D.java) draws the same scene with JavaFX.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { GTAOPass } from 'three/addons/postprocessing/GTAOPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { CSS2DRenderer, CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import * as Walk from './walk.js';

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = (n) => (n >= 10000 ? (n / 1000).toFixed(n >= 100000 ? 0 : 1) + 'k' : n.toLocaleString());

// ---------------------------------------------------------------- metrics & palettes

const RAMP = ['#2b6cb0', '#2a9fc0', '#34c3a5', '#8fd67a', '#e8e15b', '#f6b343', '#ef7d34', '#dc3f2e', '#a3123a'];
const KINDS = {
  class: '#6b8fc7', abstract: '#9a86d8', interface: '#2fb7a5', enum: '#e3a63b', record: '#d46fa8',
  annotation: '#8a9aaa', struct: '#4f86c6', trait: '#26a69a', union: '#c98a3a', type: '#b7a3e0', module: '#7c8ba1',
};
const LANGUAGES = { java: 'Java', typescript: 'TypeScript', rust: 'Rust' };
const DAY = 86400;
const nowSec = () => Date.now() / 1000;

const HEIGHT_METRICS = {
  nom: { label: 'Methods (NOM)', get: (b) => b.nom },
  loc: { label: 'Lines of code', get: (b) => b.loc },
  cx: { label: 'Complexity (WMC)', get: (b) => b.cx },
  noa: { label: 'Attributes (NOA)', get: (b) => b.noa },
};

const COLOR_METRICS = {
  commits: { label: 'Churn (commits)', git: true, get: (b) => b.commits ?? 0, lo: 'stable', hi: 'hot spot' },
  age: { label: 'Recency', git: true, get: (b) => (b.last ? Math.max(0, (nowSec() - b.last) / DAY) : Infinity), invert: true, lo: 'dormant', hi: 'just changed', unit: 'd' },
  authors: { label: 'Authors', git: true, get: (b) => b.authors ?? 0, lo: 'one owner', hi: 'many hands' },
  prs: { label: 'Open pull requests', github: true, get: (b) => b.prs?.length ?? 0, lo: 'none', hi: 'contested' },
  cx: { label: 'Complexity (WMC)', get: (b) => b.cx, lo: 'simple', hi: 'complex' },
  density: { label: 'Complexity / method', get: (b) => (b.nom ? b.cx / b.nom : 0), linear: true, lo: 'flat', hi: 'branchy' },
  loc: { label: 'Lines of code', get: (b) => b.loc, lo: 'small', hi: 'large' },
  kind: { label: 'Type kind', categorical: true },
};

const THEMES = {
  day: {
    bg: '#e8ecf1', ground: '#dfe5ec', fog: [2.2, 7],
    districts: ['#c3ccd7', '#cfd7e0', '#d9dfe7', '#e2e7ed', '#eaeef2', '#f0f3f6'],
    hemiSky: '#ffffff', hemiGround: '#aab4c2', hemi: 1.15, sun: '#fff4e5', sunI: 2.4, env: 0.45,
    glow: 0, windows: 0, bloom: false, dim: '#d5dbe3', exposure: 1.0,
  },
  night: {
    bg: '#04070d', ground: '#060b14', fog: [1.4, 5],
    districts: ['#0e1626', '#121c30', '#16223a', '#1a2844', '#1e2e4e', '#223458'],
    hemiSky: '#334670', hemiGround: '#020308', hemi: 0.45, sun: '#9db4ff', sunI: 0.5, env: 0.08,
    glow: 0.22, windows: 1.4, bloom: true, dim: '#0c1220', exposure: 1.05,
  },
};

// ---------------------------------------------------------------- state

const prefs = loadPrefs();
const state = {
  city: null,
  heightKey: prefs.heightKey || 'nom',
  colorKey: prefs.colorKey || null,
  theme: prefs.theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : 'day'),
  showTests: prefs.showTests ?? true,
  labels: prefs.labels ?? true,
  ao: prefs.ao ?? true,
  beacons: prefs.beacons ?? true,
  selected: -1,
  hovered: -1,
  matches: null, // Set of building indices matching the search, or null
  incoming: [],
};

function loadPrefs() {
  try { return JSON.parse(localStorage.getItem('codecity.prefs') || '{}'); } catch { return {}; }
}
function savePrefs() {
  try {
    const { heightKey, colorKey, theme, showTests, labels, ao, beacons } = state;
    localStorage.setItem('codecity.prefs', JSON.stringify({ heightKey, colorKey, theme, showTests, labels, ao, beacons }));
  } catch { /* storage unavailable */ }
}

// ---------------------------------------------------------------- renderer & scene

const canvas = $('#scene');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;

const labelRenderer = new CSS2DRenderer({ element: $('#labels') });
labelRenderer.setSize(innerWidth, innerHeight);

const scene = new THREE.Scene();
scene.fog = new THREE.Fog(0xffffff, 100, 1000);
const pmrem = new THREE.PMREMGenerator(renderer);
scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

const camera = new THREE.PerspectiveCamera(42, innerWidth / innerHeight, 0.3, 10000);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.maxPolarAngle = Math.PI * 0.47;
controls.screenSpacePanning = false;
controls.zoomToCursor = true;

const hemi = new THREE.HemisphereLight(0xffffff, 0x888888, 1);
scene.add(hemi);
const sun = new THREE.DirectionalLight(0xffffff, 2);
sun.castShadow = true;
sun.shadow.mapSize.set(4096, 4096);
sun.shadow.bias = -0.0004;
sun.shadow.normalBias = 0.02;
scene.add(sun, sun.target);

const ground = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshStandardMaterial({ roughness: 1 }));
ground.rotation.x = -Math.PI / 2;
ground.position.y = -0.01;
ground.receiveShadow = true;
scene.add(ground);

const composer = new EffectComposer(renderer);
composer.addPass(new RenderPass(scene, camera));
const gtao = new GTAOPass(scene, camera, innerWidth, innerHeight);
gtao.updateGtaoMaterial({ radius: 2.5, distanceExponent: 1.5, thickness: 2, scale: 1.2, samples: 16 });
gtao.blendIntensity = 0.85;
composer.addPass(gtao);
const bloom = new UnrealBloomPass(new THREE.Vector2(innerWidth, innerHeight), 0.75, 0.55, 0.62);
composer.addPass(bloom);
composer.addPass(new OutputPass());

// Unit box with its base at y = 0; every building and district is an instance of it.
const unitBox = new THREE.BoxGeometry(1, 1, 1);
unitBox.translate(0, 0.5, 0);

const buildingMaterial = makeBuildingMaterial();
const districtMaterial = new THREE.MeshStandardMaterial({ roughness: 0.92, metalness: 0 });
const cityGroup = new THREE.Group();
const arcGroup = new THREE.Group();
scene.add(cityGroup, arcGroup);

let buildingMesh = null;
let districtMesh = null;
let baseColors = [];
let heights = { cur: new Float32Array(0), from: new Float32Array(0), to: new Float32Array(0), t0: 0, dur: 0 };
let arcs = [];
let labelObjects = [];
let cityRadius = 50;

/**
 * Standard PBR material extended with two night-time effects driven from the instance data:
 * a soft glow in the building's own colour, and procedurally lit office windows on the facades.
 */
//
// How the patch works: onBeforeCompile receives three.js's generated shader source and splices code in after two of
// its #include chunks. The vertex shader passes on the box-local position and normal, the instance's scale (the
// length of each column of instanceMatrix, i.e. footprint and height) and a per-building seed (the instance's x/z
// position). The fragment shader then, after the emissive chunk:
//   - adds the instance colour times uGlow to the emitted light (night glow);
//   - on side faces only (|normal.y| < 0.5), lays a grid of 0.42 × 0.5 world-unit cells over the facade, using the
//     scale to turn local coordinates into world units so windows keep their size on any building; draws a window
//     in the middle of each cell; and lights a pseudo-random ~42% of them (hash of cell, seed and face), mostly warm,
//     about a quarter of them cool. The ground floor and a strip below the roof stay dark.
// The uniforms live in material.userData so applyTheme can change them without recompiling the shader.
function makeBuildingMaterial() {
  const material = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.6, metalness: 0.05 });
  const uniforms = { uGlow: { value: 0 }, uWindows: { value: 0 } };
  material.userData.uniforms = uniforms;
  material.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms);
    const varyings = 'varying vec3 vLocalPos;\nvarying vec3 vObjNormal;\nvarying vec3 vInstScale;\nvarying vec2 vSeed;\n';
    shader.vertexShader = varyings + shader.vertexShader.replace('#include <begin_vertex>', `#include <begin_vertex>
      vLocalPos = position;
      vObjNormal = normal;
      #ifdef USE_INSTANCING
        vInstScale = vec3(length(instanceMatrix[0].xyz), length(instanceMatrix[1].xyz), length(instanceMatrix[2].xyz));
        vSeed = instanceMatrix[3].xz;
      #else
        vInstScale = vec3(1.0);
        vSeed = vec2(0.0);
      #endif`);
    shader.fragmentShader = 'uniform float uGlow;\nuniform float uWindows;\n' + varyings +
      shader.fragmentShader.replace('#include <emissivemap_fragment>', `#include <emissivemap_fragment>
      #if defined( USE_INSTANCING_COLOR ) || defined( USE_COLOR )
        totalEmissiveRadiance += vColor.rgb * uGlow;
      #endif
      if (uWindows > 0.0 && abs(vObjNormal.y) < 0.5) {
        float h = vLocalPos.y * vInstScale.y;
        float u = abs(vObjNormal.x) > 0.5 ? (vLocalPos.z + 0.5) * vInstScale.z : (vLocalPos.x + 0.5) * vInstScale.x;
        vec2 cell = floor(vec2(u / 0.42, h / 0.5));
        vec2 f = fract(vec2(u / 0.42, h / 0.5));
        float win = step(0.22, f.x) * step(f.x, 0.78) * step(0.28, f.y) * step(f.y, 0.72);
        float rnd = fract(sin(dot(cell + vSeed * 1.37 + vObjNormal.xz * 17.0, vec2(12.9898, 78.233))) * 43758.5453);
        float lit = step(0.58, rnd) * step(0.45, h) * step(h, vInstScale.y - 0.25);
        vec3 warm = mix(vec3(1.0, 0.72, 0.38), vec3(0.75, 0.88, 1.0), step(0.9, rnd));
        totalEmissiveRadiance += warm * win * lit * uWindows * (0.6 + 0.4 * rnd);
      }`);
  };
  return material;
}

// ---------------------------------------------------------------- data

const embedded = document.getElementById('city-data');
const live = !embedded;

async function loadCity() {
  if (embedded) return JSON.parse(embedded.textContent);
  const res = await fetch('api/city', { cache: 'no-store' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function quantile(values, q) {
  const v = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (!v.length) return 0;
  return v[Math.min(v.length - 1, Math.floor(q * (v.length - 1)))];
}

function visible(b) {
  return state.showTests || !b.test;
}

// ---------------------------------------------------------------- building the city

function buildCity(city, { previousHeights = null } = {}) {
  const keepCamera = previousHeights !== null;
  const previousSelection = state.selected >= 0 ? state.city.buildings[state.selected]?.id : null;
  // Old heights by building id: indices change meaning when types are added or removed.
  const oldHeights = new Map();
  if (previousHeights) state.city.buildings.forEach((b, i) => oldHeights.set(b.id, previousHeights[i]));
  state.city = city;
  state.selected = -1;
  state.hovered = -1;
  baseColors = [];

  for (const child of [...cityGroup.children]) {
    cityGroup.remove(child);
    if (child.isInstancedMesh) child.dispose();
  }
  clearArcs();
  for (const l of labelObjects) l.removeFromParent();
  labelObjects = [];

  const P = city.platform;
  const tmp = new THREE.Object3D();

  // Districts: stacked platforms, one level per package nesting depth.
  // An InstancedMesh needs at least one instance slot; `count` then hides the unused one for an empty city.
  districtMesh = new THREE.InstancedMesh(unitBox, districtMaterial, Math.max(1, city.districts.length));
  districtMesh.count = city.districts.length;
  city.districts.forEach((d, i) => {
    tmp.position.set(d.x, d.depth * P, d.z);
    tmp.scale.set(d.w, P, d.d);
    tmp.updateMatrix();
    districtMesh.setMatrixAt(i, tmp.matrix);
    districtMesh.setColorAt(i, new THREE.Color(0xffffff));
  });
  districtMesh.receiveShadow = true;
  districtMesh.castShadow = true;
  cityGroup.add(districtMesh);

  // Buildings: matrices are written every frame while heights animate (DynamicDrawUsage), see writeBuildingMatrices.
  // Instance colours start white and are set by updateColors/paintBuildings.
  const n = city.buildings.length;
  buildingMesh = new THREE.InstancedMesh(unitBox, buildingMaterial, Math.max(1, n));
  buildingMesh.count = n;
  buildingMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  for (let i = 0; i < Math.max(1, n); i++) buildingMesh.setColorAt(i, new THREE.Color(0xffffff));
  buildingMesh.castShadow = true;
  buildingMesh.receiveShadow = true;
  cityGroup.add(buildingMesh);

  const incoming = city.buildings.map(() => []);
  city.buildings.forEach((b, i) => b.deps.forEach((j) => incoming[j].push(i)));
  state.incoming = incoming;

  heights = { cur: new Float32Array(n), from: new Float32Array(n), to: new Float32Array(n), t0: 0, dur: 0 };
  city.buildings.forEach((b, i) => { heights.cur[i] = oldHeights.get(b.id) ?? 0; });

  // Lights, shadows, fog and ground scale with the city.
  cityRadius = Math.max(20, Math.hypot(city.width, city.depth) / 2);
  const r = cityRadius;
  sun.position.set(r * 0.6, r * 1.4, r * 0.9);
  sun.target.position.set(0, 0, 0);
  const cam = sun.shadow.camera;
  cam.left = cam.bottom = -r * 1.1;
  cam.right = cam.top = r * 1.1;
  cam.near = 1;
  cam.far = r * 4;
  cam.updateProjectionMatrix();
  ground.scale.set(r * 14, r * 14, 1);
  camera.far = r * 30;
  camera.updateProjectionMatrix();
  controls.maxDistance = r * 6;
  gtao.updateGtaoMaterial({ radius: Math.min(4, Math.max(1.5, r / 60)) });

  buildLabels();
  buildBeacons();
  applyTheme();
  updateHeights(!keepCamera);
  updateColors();
  renderStats();
  if (!keepCamera) frameAll(false);

  if (previousSelection) {
    const idx = city.buildings.findIndex((b) => b.id === previousSelection);
    if (idx >= 0) select(idx, { fly: false });
  }
  if ($('#search').value) runSearch($('#search').value);
}

function buildLabels() {
  const city = state.city;
  const P = city.platform;
  const candidates = city.districts
    .map((d, i) => ({ d, i }))
    .filter(({ d }) => d.depth >= 1 && d.depth <= 2 && d.types > 0)
    .sort((a, b) => b.d.w * b.d.d - a.d.w * a.d.d)
    .slice(0, 24);
  const anchors = [];
  for (const { d } of candidates) {
    // A district whose only content is one sub-district shares its front edge; label just one of them.
    if (anchors.some((a) => Math.abs(a.x - d.x) < 8 && Math.abs(a.z - (d.z + d.d / 2)) < 3) || anchors.length >= 16) continue;
    anchors.push({ x: d.x, z: d.z + d.d / 2 });
    const el = document.createElement('div');
    el.className = 'district-label';
    el.textContent = d.name;
    const obj = new CSS2DObject(el);
    obj.position.set(d.x, (d.depth + 1) * P + 0.2, d.z + d.d / 2);
    obj.center.set(0.5, 0);
    cityGroup.add(obj);
    labelObjects.push(obj);
  }
  setLabelsVisible(state.labels);
}

// District labels are HTML on top of the canvas, so at street level they would show through the buildings; walk
// mode hides them regardless of the setting.
function setLabelsVisible(on) {
  for (const l of labelObjects) l.visible = on && !walk;
}

/** Recomputes target heights for the current metric and animates the skyline towards them. */
function updateHeights(rise = false) {
  const { buildings } = state.city;
  const metric = HEIGHT_METRICS[state.heightKey];
  const values = buildings.filter(visible).map(metric.get);
  const p95 = Math.max(1, quantile(values, 0.95));
  const scale = Math.min(16, Math.max(8, cityRadius * 0.2)) / p95;
  heights.from = rise ? new Float32Array(buildings.length) : heights.cur.slice();
  buildings.forEach((b, i) => {
    heights.to[i] = visible(b) ? Math.min(0.35 + metric.get(b) * scale, cityRadius * 0.75) : 0;
  });
  heights.t0 = performance.now();
  heights.dur = rise ? 1400 : 650;
}

// Advances the height animation (cubic ease-out). When it ends, the bounding volumes are recomputed: three.js culls
// and raycasts an InstancedMesh against them, so without this, grown buildings could be missed by picking.
function stepHeights(now) {
  if (!heights.dur) return false;
  const k = Math.min(1, (now - heights.t0) / heights.dur);
  const e = 1 - Math.pow(1 - k, 3);
  const { cur, from, to } = heights;
  for (let i = 0; i < cur.length; i++) cur[i] = from[i] + (to[i] - from[i]) * e;
  writeBuildingMatrices();
  if (k >= 1) {
    heights.dur = 0;
    buildingMesh.computeBoundingSphere();
    buildingMesh.computeBoundingBox();
    if (state.selected >= 0) { buildArcs(); placeSelectionMarker(); }
  }
  return true;
}

const mtx = new THREE.Matrix4();
const pos = new THREE.Vector3();
const scl = new THREE.Vector3();
const quat = new THREE.Quaternion();

// Writes every building's instance matrix from the current heights. A building with (almost) no height also gets a
// zero footprint, so hidden buildings vanish; the y scale never reaches exactly 0, which would make the matrix
// singular.
function writeBuildingMatrices() {
  const { buildings } = state.city;
  for (let i = 0; i < buildings.length; i++) {
    const b = buildings[i];
    const h = heights.cur[i];
    const s = h > 0.001 ? b.side : 0;
    mtx.compose(pos.set(b.x, b.y, b.z), quat, scl.set(s, Math.max(h, 0.0001), s));
    buildingMesh.setMatrixAt(i, mtx);
  }
  buildingMesh.instanceMatrix.needsUpdate = true;
}

function rampColor(t) {
  const x = Math.min(1, Math.max(0, t)) * (RAMP.length - 1);
  const i = Math.min(RAMP.length - 2, Math.floor(x));
  return new THREE.Color(RAMP[i]).lerp(new THREE.Color(RAMP[i + 1]), x - i);
}

/** Maps the colour metric onto every building, using a log scale clipped at the 98th percentile. */
function updateColors() {
  const { buildings } = state.city;
  const key = state.colorKey;
  const metric = COLOR_METRICS[key];
  if (metric.categorical) {
    baseColors = buildings.map((b) => new THREE.Color(KINDS[b.kind] || KINDS.class));
    renderLegend({ categorical: true });
  } else {
    const values = buildings.map(metric.get);
    const finite = values.filter(Number.isFinite);
    const hi = Math.max(metric.linear ? 1e-6 : 1, quantile(finite, 0.98));
    const lo = finite.reduce((m, v) => Math.min(m, v), hi);
    const norm = (v) => {
      if (!Number.isFinite(v)) return metric.invert ? 0 : 1;
      const t = metric.linear ? v / hi : Math.log1p(v) / Math.log1p(hi);
      return metric.invert ? 1 - t : t;
    };
    baseColors = values.map((v) => rampColor(norm(v)));
    renderLegend({ lo, hi, metric });
  }
  paintBuildings();
}

/** Applies base colours plus search dimming and selection emphasis. */
function paintBuildings() {
  if (!buildingMesh) return;
  const dim = new THREE.Color(THEMES[state.theme].dim);
  const white = new THREE.Color(0xffffff);
  const sel = state.selected;
  const related = new Set();
  if (sel >= 0) {
    related.add(sel);
    state.city.buildings[sel].deps.forEach((j) => related.add(j));
    state.incoming[sel].forEach((j) => related.add(j));
  }
  const c = new THREE.Color();
  baseColors.forEach((base, i) => {
    c.copy(base);
    const outOfSearch = state.matches && !state.matches.has(i);
    const outOfFocus = sel >= 0 && !related.has(i);
    if (outOfSearch || outOfFocus) c.lerp(dim, 0.8);
    if (i === state.hovered && i !== sel) c.lerp(white, 0.3);
    buildingMesh.setColorAt(i, c);
  });
  buildingMesh.instanceColor.needsUpdate = true;
}

function paintDistricts() {
  if (!districtMesh) return;
  const palette = THEMES[state.theme].districts;
  const c = new THREE.Color();
  state.city.districts.forEach((d, i) => {
    c.set(palette[Math.min(d.depth, palette.length - 1)]);
    districtMesh.setColorAt(i, c);
  });
  districtMesh.instanceColor.needsUpdate = true;
}

function applyTheme() {
  const t = THEMES[state.theme];
  document.documentElement.dataset.theme = state.theme;
  $('#btn-theme').textContent = state.theme === 'day' ? 'Night' : 'Day';
  scene.background = new THREE.Color(t.bg);
  scene.fog.color.set(t.bg);
  scene.fog.near = cityRadius * t.fog[0];
  scene.fog.far = cityRadius * t.fog[1];
  scene.environmentIntensity = t.env;
  hemi.color.set(t.hemiSky);
  hemi.groundColor.set(t.hemiGround);
  hemi.intensity = t.hemi;
  sun.color.set(t.sun);
  sun.intensity = t.sunI;
  ground.material.color.set(t.ground);
  buildingMaterial.userData.uniforms.uGlow.value = t.glow;
  buildingMaterial.userData.uniforms.uWindows.value = t.windows;
  buildingMaterial.roughness = state.theme === 'night' ? 0.4 : 0.6;
  bloom.enabled = t.bloom;
  renderer.toneMappingExposure = t.exposure;
  gtao.enabled = state.ao;
  if (state.city) {
    paintDistricts();
    paintBuildings();
    if (state.selected >= 0) buildArcs();
  }
}

// ---------------------------------------------------------------- HUD

function renderStats() {
  const { stats, name } = state.city;
  $('#project-name').textContent = name;
  document.title = `${name} · CodeCity`;
  const chips = [
    [fmt(stats.types), 'types'],
    [fmt(stats.packages), 'packages'],
    [fmt(stats.methods), 'methods'],
    [fmt(stats.loc), 'LOC'],
  ].map(([v, l]) => `<span class="chip"><b>${v}</b><span>${l}</span></span>`);
  const gh = state.city.github;
  if (gh) {
    const prs = gh.pulls ? ` · ${gh.pulls.length}${gh.pullsSource?.startsWith('all') ? '' : '+'} PRs` : '';
    chips.unshift(`<a class="chip gh" href="${esc(gh.url)}" target="_blank" rel="noopener" title="Open on GitHub${gh.pullsSource ? ' · ' + esc(gh.pullsSource) : ''}">${GH_MARK}<b>${esc(gh.owner)}/${esc(gh.repo)}</b><span>${prs}</span></a>`);
  }
  const langs = Object.entries(stats.languages || {});
  if (langs.length) {
    const total = langs.reduce((n, [, c]) => n + c, 0);
    const label = langs.map(([l, c]) => langs.length > 1 ? `${LANGUAGES[l] || l} ${Math.round((c / total) * 100)}%` : (LANGUAGES[l] || l)).join(' · ');
    chips.unshift(`<span class="chip"><span>${esc(label)}</span></span>`);
  }
  if (stats.failedFiles) chips.push(`<span class="chip warn" title="Files with syntax errors were partially analysed"><b>${stats.failedFiles}</b><span>parse errors</span></span>`);
  $('#stats').innerHTML = chips.join('');
}

function metricUnavailable(m) {
  if (m.git && !state.city.hasGit) return ' (no git)';
  if (m.github && !state.city.github?.pulls) return ' (no GitHub)';
  return '';
}

function populateSelects() {
  const hasGit = state.city.hasGit;
  if (!COLOR_METRICS[state.colorKey] || metricUnavailable(COLOR_METRICS[state.colorKey])) {
    state.colorKey = hasGit ? 'commits' : 'cx';
  }
  const beacons = $('#opt-beacons');
  beacons.disabled = !state.city.github?.pulls?.length;
  beacons.closest('label').hidden = !state.city.github?.pulls;
  if (!HEIGHT_METRICS[state.heightKey]) state.heightKey = 'nom';
  $('#height-metric').innerHTML = Object.entries(HEIGHT_METRICS)
    .map(([k, m]) => `<option value="${k}" ${k === state.heightKey ? 'selected' : ''}>${m.label}</option>`).join('');
  $('#color-metric').innerHTML = Object.entries(COLOR_METRICS)
    .map(([k, m]) => `<option value="${k}" ${k === state.colorKey ? 'selected' : ''} ${metricUnavailable(m) ? 'disabled' : ''}>${m.label}${metricUnavailable(m)}</option>`).join('');
}

function renderLegend({ categorical, lo, hi, metric }) {
  const el = $('#legend');
  if (categorical) {
    const present = new Set(state.city.buildings.map((b) => b.kind));
    el.innerHTML = `<div class="swatches">${Object.entries(KINDS).filter(([k]) => present.has(k)).map(([k, c]) => `<span class="sw"><i style="background:${c}"></i>${k}</span>`).join('')}</div>`;
    return;
  }
  const show = (v) => (metric.unit === 'd' ? `${Math.round(v)}d ago` : Number.isInteger(v) ? fmt(v) : v.toFixed(1));
  const [left, right] = metric.invert ? [show(hi) + '+', show(lo)] : [show(lo), show(hi) + '+'];
  el.innerHTML = `<div class="bar" style="background:linear-gradient(90deg,${RAMP.join(',')})"></div>
    <div class="ends"><span>${left}</span><span>${right}</span></div>
    <div class="ends"><span>${metric.lo}</span><span>${metric.hi}</span></div>`;
}

function relTime(epoch) {
  if (!epoch) return '—';
  const days = (nowSec() - epoch) / DAY;
  if (days < 1) return 'today';
  if (days < 30) return `${Math.round(days)}d ago`;
  if (days < 365) return `${Math.round(days / 30)}mo ago`;
  return `${(days / 365).toFixed(1)}y ago`;
}

const GH_MARK = '<svg class="gh-mark" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>';

/** Permalink into the repository on GitHub for a building's file. */
function githubLink(kind, b) {
  const gh = state.city.github;
  const enc = (p) => p.split('/').map(encodeURIComponent).join('/');
  const path = enc(gh.prefix + b.file);
  const ref = enc(gh.ref);
  if (kind === 'history') return `${gh.url}/commits/${ref}/${path}`;
  return `${gh.url}/${kind}/${ref}/${path}#L${b.line}-L${b.endLine}`;
}

function renderGitHubSection(b) {
  const gh = state.city.github;
  if (!gh) return '';
  const links = `<div class="gh-links">
      <a href="${esc(githubLink('blob', b))}" target="_blank" rel="noopener">${GH_MARK}File</a>
      <a href="${esc(githubLink('blame', b))}" target="_blank" rel="noopener">Blame</a>
      <a href="${esc(githubLink('history', b))}" target="_blank" rel="noopener">History</a>
    </div>`;
  if (!gh.pulls) return links;
  const prs = (b.prs || []).map((k) => gh.pulls[k]);
  return `${links}
    <h3><span>Open pull requests</span><span>${prs.length}</span></h3>
    <div class="prs">${prs.map((pr) => `
      <a class="pr" href="${esc(pr.url)}" target="_blank" rel="noopener">
        <span class="pr-num">#${pr.number}</span>
        <span class="pr-title">${esc(pr.title)}</span>
        <span class="pr-meta">${esc(pr.author || 'ghost')} · ${relTime(Date.parse(pr.updatedAt) / 1000)} · ${pr.files} files${pr.draft ? ' · <i>draft</i>' : ''}</span>
      </a>`).join('') || `<span class="muted">none${gh.pullsSource?.startsWith('all') ? '' : ' among the ' + esc(gh.pullsSource)}</span>`}
    </div>`;
}

function renderInspector() {
  const el = $('#inspector');
  const i = state.selected;
  if (i < 0) { el.hidden = true; return; }
  const b = state.city.buildings[i];
  const hasGit = state.city.hasGit;
  // b.methods is capped at 300 by the server; the method counts below use b.nom, which counts all of them.
  const maxCx = Math.max(1, ...b.methods.map((m) => m[3]));
  const metric = (v, l) => `<div class="metric"><b>${v}</b><span>${l}</span></div>`;
  const depChip = (j, dir) => {
    const d = state.city.buildings[j];
    return `<span class="dep ${dir}" data-idx="${j}" title="${esc(d.id)}">${esc(d.name)}</span>`;
  };
  const out = b.deps.filter((j) => visible(state.city.buildings[j]));
  const inc = state.incoming[i].filter((j) => visible(state.city.buildings[j]));
  el.innerHTML = `
    <div class="head">
      <div><h2>${esc(b.name)}</h2><div class="pkg">${esc(b.pkg || (b.lang === 'java' ? '(default package)' : '(root)'))}${b.module ? ' · ' + esc(b.module) : ''}</div></div>
      <button class="close" id="inspector-close" aria-label="Close">✕</button>
    </div>
    <div class="badges">
      <span class="badge kind" style="background:${KINDS[b.kind] || KINDS.class}">${b.kind}</span>
      <span class="badge">${LANGUAGES[b.lang] || b.lang}</span>
      ${b.test ? '<span class="badge">test</span>' : ''}
    </div>
    <div class="metrics">
      ${metric(b.nom, 'methods')}${metric(b.noa, 'attributes')}${metric(fmt(b.loc), 'lines of code')}
      ${metric(b.cx, 'complexity')}${metric(b.nom ? (b.cx / b.nom).toFixed(1) : '0', 'cx / method')}
      ${metric(out.length + ' / ' + inc.length, 'uses / used by')}
      ${hasGit ? metric(b.commits ?? 0, 'commits') + metric(b.authors ?? 0, 'authors') + metric(relTime(b.last), 'last change') : ''}
    </div>
    <div class="file-link"><span title="${esc(b.file)}:${b.line}">${esc(b.file)}:${b.line}</span>${live ? '<button id="view-source">Source</button>' : ''}</div>
    ${renderGitHubSection(b)}
    ${b.methods.length ? `<h3><span>Methods by complexity</span><span>${b.nom}</span></h3>
    <div class="methods">${b.methods.slice(0, 15).map((m) => `
      <div class="method" title="line ${m[1]} · ${m[2]} LOC · ${m[4]} params">
        <span class="m-name">${esc(m[0])}(${m[4] ? '…' : ''})</span><span class="m-val">cx ${m[3]} · ${m[2]} loc</span>
        <span class="m-bar"><i style="width:${(m[3] / maxCx) * 100}%;background:${rampColor(m[3] / Math.max(10, maxCx)).getStyle()}"></i></span>
      </div>`).join('')}
      ${b.nom > 15 ? `<span class="muted">+ ${b.nom - 15} more</span>` : ''}
    </div>` : ''}
    <h3><span>Uses</span><span>${out.length}</span></h3>
    <div class="deps">${out.map((j) => depChip(j, 'out')).join('') || '<span class="muted">nothing in this project</span>'}</div>
    <h3><span>Used by</span><span>${inc.length}</span></h3>
    <div class="deps">${inc.map((j) => depChip(j, 'in')).join('') || '<span class="muted">no project types</span>'}</div>`;
  el.hidden = false;
  el.scrollTop = 0;
  $('#inspector-close').onclick = () => select(-1);
  $('#view-source')?.addEventListener('click', () => openSource(i));
  el.querySelectorAll('.dep').forEach((d) => d.addEventListener('click', () => select(+d.dataset.idx)));
}

let toastTimer = 0;
function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.hidden = true), 2600);
}

// ---------------------------------------------------------------- source viewer

async function openSource(i) {
  const b = state.city.buildings[i];
  $('#source-title').textContent = `${b.file}:${b.line}`;
  $('#code').textContent = 'Loading…';
  $('#code-gutter').textContent = '';
  $('#source-modal').hidden = false;
  try {
    const res = await fetch(`api/source?id=${i}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const src = await res.json();
    const lines = src.code.split('\n').length;
    $('#code-gutter').textContent = Array.from({ length: lines }, (_, k) => k + 1).join('\n');
    const code = $('#code');
    if (window.hljs) code.innerHTML = window.hljs.highlight(src.code, { language: b.lang || 'java', ignoreIllegals: true }).value;
    else code.textContent = src.code;
    const LINE = 20, PAD = 10;
    const band = $('#code-band');
    band.style.top = `${PAD + (src.line - 1) * LINE}px`;
    band.style.height = `${(src.endLine - src.line + 1) * LINE}px`;
    $('#code-wrap').scrollTop = Math.max(0, (src.line - 4) * LINE);
  } catch (e) {
    $('#code').textContent = `Could not load source: ${e.message}`;
  }
}

function closeSource() { $('#source-modal').hidden = true; }

// ---------------------------------------------------------------- selection, arcs, camera

const marker = new THREE.LineSegments(
  new THREE.EdgesGeometry(unitBox),
  new THREE.LineBasicMaterial({ color: 0xffffff, toneMapped: false, transparent: true, opacity: 0.95 }),
);
marker.visible = false;
scene.add(marker);

function placeSelectionMarker() {
  const i = state.selected;
  marker.visible = i >= 0;
  if (i < 0) return;
  const b = state.city.buildings[i];
  const h = heights.cur[i];
  marker.position.set(b.x, b.y - 0.02, b.z);
  marker.scale.set(b.side + 0.25, h + 0.25, b.side + 0.25);
  marker.material.color.set(state.theme === 'night' ? 0xffffff : 0x111827);
}

// Beacons: a glowing marker bobbing above every class that has open pull requests.
const beaconGeometry = new THREE.OctahedronGeometry(0.55, 0);
beaconGeometry.scale(1, 1.6, 1);
const beaconMaterial = new THREE.MeshBasicMaterial({ color: new THREE.Color('#c084fc').multiplyScalar(1.6), toneMapped: false });
let beaconMesh = null;
let beaconIndices = [];

function buildBeacons() {
  if (beaconMesh) { scene.remove(beaconMesh); beaconMesh.dispose(); beaconMesh = null; }
  beaconIndices = state.city.buildings.flatMap((b, i) => (b.prs?.length ? [i] : []));
  if (!beaconIndices.length) return;
  beaconMesh = new THREE.InstancedMesh(beaconGeometry, beaconMaterial, beaconIndices.length);
  beaconMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  beaconMesh.raycast = () => {};
  scene.add(beaconMesh);
}

function stepBeacons(now) {
  if (!beaconMesh) return;
  beaconMesh.visible = state.beacons;
  if (!state.beacons) return;
  const t = now / 1000;
  beaconIndices.forEach((i, k) => {
    const b = state.city.buildings[i];
    const h = heights.cur[i];
    const size = h > 0.001 ? 0.7 + Math.min(1.2, b.prs.length * 0.25) : 0;
    const bob = Math.sin(t * 2 + i) * 0.25;
    quat.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, t * 1.2 + i);
    mtx.compose(pos.set(b.x, b.y + h + 1.4 + bob, b.z), quat, scl.setScalar(size));
    beaconMesh.setMatrixAt(k, mtx);
  });
  quat.identity();
  beaconMesh.instanceMatrix.needsUpdate = true;
}

function select(i, { fly = true } = {}) {
  state.selected = i;
  renderInspector();
  buildArcs();
  placeSelectionMarker();
  paintBuildings();
  if (i >= 0 && fly) flyToBuilding(i);
}

function clearArcs() {
  for (const a of arcs) {
    arcGroup.remove(a.tube, a.dot);
    a.tube.geometry.dispose();
    a.tube.material.dispose();
    a.dot.material.dispose();
  }
  arcs = [];
}

const dotGeometry = new THREE.SphereGeometry(1, 12, 8);

function topOf(i) {
  const b = state.city.buildings[i];
  return new THREE.Vector3(b.x, b.y + heights.cur[i], b.z);
}

/** Dependency arcs: orange for what the selection uses, blue for what uses it; particles flow along the edge. */
// Each arc is a quadratic Bézier tube from roof to roof, the control point above the midpoint at the taller roof plus
// 35% of the distance plus 2, so long arcs arch higher. Only edges whose both ends are visible are drawn, at most 200
// per direction (filtered first, then capped, like the desktop viewers); with more than 40 edges in total (visible or
// not) the tubes are fainter. A dot per arc travels along it at roughly constant world speed (stepArcs). The Java desktop
// viewer builds the same curves (CityView3D.showArcs).
function buildArcs() {
  clearArcs();
  const i = state.selected;
  if (i < 0) return;
  const { buildings } = state.city;
  const night = state.theme === 'night';
  const shown = (list) => (visible(buildings[i]) ? list.filter((j) => visible(buildings[j])).slice(0, 200) : []);
  const out = shown(buildings[i].deps);
  const inc = shown(state.incoming[i]);
  const opacity = (night ? 0.75 : 0.85) * (buildings[i].deps.length + state.incoming[i].length > 40 ? 0.5 : 1);
  const add = (from, to, colorHex) => {
    const p0 = topOf(from), p1 = topOf(to);
    const dist = p0.distanceTo(p1);
    const mid = p0.clone().lerp(p1, 0.5);
    mid.y = Math.max(p0.y, p1.y) + dist * 0.35 + 2;
    const curve = new THREE.QuadraticBezierCurve3(p0, mid, p1);
    const color = new THREE.Color(colorHex).multiplyScalar(night ? 1.8 : 1);
    const tube = new THREE.Mesh(
      new THREE.TubeGeometry(curve, 40, 0.06 + dist * 0.0008, 6, false),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity, toneMapped: false, depthWrite: false }),
    );
    const dot = new THREE.Mesh(dotGeometry, new THREE.MeshBasicMaterial({ color: color.clone().multiplyScalar(night ? 1.6 : 1), toneMapped: false }));
    dot.scale.setScalar(0.18 + dist * 0.002);
    arcGroup.add(tube, dot);
    arcs.push({ tube, dot, curve, t: Math.random(), speed: 14 / Math.max(5, curve.getLength()) });
  };
  out.forEach((j) => add(i, j, '#f97316'));
  inc.forEach((j) => add(j, i, '#0ea5e9'));
}

function stepArcs(dt) {
  for (const a of arcs) {
    a.t = (a.t + dt * a.speed) % 1;
    a.dot.position.copy(a.curve.getPoint(a.t));
  }
}

let flight = null;
function flyTo(target, position, duration = 900) {
  flight = {
    t0: performance.now(), duration,
    fromTarget: controls.target.clone(), toTarget: target,
    fromPos: camera.position.clone(), toPos: position,
  };
}

function stepFlight(now) {
  if (!flight) return;
  const k = Math.min(1, (now - flight.t0) / flight.duration);
  const e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
  controls.target.lerpVectors(flight.fromTarget, flight.toTarget, e);
  camera.position.lerpVectors(flight.fromPos, flight.toPos, e);
  if (k >= 1) flight = null;
}

function flyToBuilding(i) {
  const b = state.city.buildings[i];
  const h = heights.to[i] || heights.cur[i];
  const target = new THREE.Vector3(b.x, b.y + h * 0.5, b.z);
  const dir = camera.position.clone().sub(controls.target).normalize();
  if (dir.y < 0.35) { dir.y = 0.35; dir.normalize(); }
  const dist = Math.max(h * 1.8, b.side * 7, 22);
  flyTo(target, target.clone().addScaledVector(dir, dist));
}

function flyToDistrict(d) {
  const P = state.city.platform;
  const target = new THREE.Vector3(d.x, (d.depth + 1) * P, d.z);
  const dir = camera.position.clone().sub(controls.target).normalize();
  if (dir.y < 0.45) { dir.y = 0.45; dir.normalize(); }
  flyTo(target, target.clone().addScaledVector(dir, Math.max(d.w, d.d) * 1.25 + 10));
}

function frameAll(animate = true) {
  const r = cityRadius;
  const target = new THREE.Vector3(0, 0, 0);
  const position = new THREE.Vector3(r * 0.95, r * 1.25, r * 1.55);
  if (animate) flyTo(target, position, 1100);
  else { controls.target.copy(target); camera.position.copy(position); }
}

// ---------------------------------------------------------------- picking & tooltip

const raycaster = new THREE.Raycaster();
const ndc = new THREE.Vector2();

// Raycasts from the camera through the pointer. Buildings take precedence over the platforms beneath them; the
// instanceId of the hit is the building or district index. Beacons are excluded (their raycast is a no-op).
function pick(clientX, clientY) {
  if (!buildingMesh) return null;
  ndc.set((clientX / innerWidth) * 2 - 1, -(clientY / innerHeight) * 2 + 1);
  raycaster.setFromCamera(ndc, camera);
  const hit = raycaster.intersectObject(buildingMesh, false)[0];
  if (hit && hit.instanceId != null && hit.instanceId < state.city.buildings.length) return { type: 'building', index: hit.instanceId };
  const dHit = raycaster.intersectObject(districtMesh, false)[0];
  if (dHit && dHit.instanceId != null) return { type: 'district', index: dHit.instanceId };
  return null;
}

const tooltip = $('#tooltip');
function showTooltip(target, x, y) {
  if (!target) { tooltip.hidden = true; return; }
  let html;
  if (target.type === 'building') {
    const b = state.city.buildings[target.index];
    html = `<b>${esc(b.name)}</b> <span class="t-pkg">${b.kind}</span><div class="t-pkg">${esc(b.pkg)}</div>
      <div class="t-row"><span>${b.nom} methods</span><span>${b.noa} attrs</span><span>${fmt(b.loc)} loc</span><span>cx ${b.cx}</span>${b.commits != null ? `<span>${b.commits} commits</span>` : ''}${b.prs ? `<span class="t-pr">${b.prs.length} open PR${b.prs.length > 1 ? 's' : ''}</span>` : ''}</div>`;
  } else {
    const d = state.city.districts[target.index];
    if (d.depth === 0) { tooltip.hidden = true; return; }
    html = `<b>${esc(d.name)}</b><div class="t-pkg">${esc(d.id.replace(/^module:/, ''))}</div><div class="t-row"><span>${d.types} types</span><span>double-click to zoom</span></div>`;
  }
  tooltip.innerHTML = html;
  tooltip.hidden = false;
  const w = tooltip.offsetWidth, h = tooltip.offsetHeight;
  tooltip.style.left = `${Math.min(x + 14, innerWidth - w - 8)}px`;
  tooltip.style.top = `${Math.min(y + 14, innerHeight - h - 8)}px`;
}

let pointer = null;
let hoverQueued = false;
canvas.addEventListener('pointermove', (e) => {
  if (walk) return; // the crosshair does the hovering while walking (stepWalk)
  pointer = { x: e.clientX, y: e.clientY, buttons: e.buttons };
  if (hoverQueued) return;
  hoverQueued = true;
  requestAnimationFrame(() => {
    hoverQueued = false;
    if (!pointer || pointer.buttons) { showTooltip(null); return; }
    const target = pick(pointer.x, pointer.y);
    const hovered = target?.type === 'building' ? target.index : -1;
    if (hovered !== state.hovered) { state.hovered = hovered; paintBuildings(); }
    canvas.style.cursor = hovered >= 0 ? 'pointer' : '';
    showTooltip(target, pointer.x, pointer.y);
  });
});
canvas.addEventListener('pointerleave', () => {
  if (walk) return;
  pointer = null;
  showTooltip(null);
  if (state.hovered >= 0) { state.hovered = -1; paintBuildings(); }
});

let down = null;
canvas.addEventListener('pointerdown', (e) => {
  // While walking the cursor is captured: a left click picks whatever is under the crosshair, without flying there.
  if (walk) {
    if (e.button === 0 && walk.locked) {
      const target = pick(innerWidth / 2, innerHeight / 2);
      if (target?.type === 'building') select(target.index, { fly: false });
      else if (state.selected >= 0) select(-1);
    }
    return;
  }
  down = { x: e.clientX, y: e.clientY };
  flight = null;
});
canvas.addEventListener('pointerup', (e) => {
  if (walk) return;
  if (!down || Math.hypot(e.clientX - down.x, e.clientY - down.y) > 5 || e.button !== 0) return;
  const target = pick(e.clientX, e.clientY);
  if (target?.type === 'building') select(target.index);
  else if (state.selected >= 0) select(-1);
});
canvas.addEventListener('dblclick', (e) => {
  if (walk) return;
  const target = pick(e.clientX, e.clientY);
  if (target?.type === 'district') flyToDistrict(state.city.districts[target.index]);
});

// ---------------------------------------------------------------- search

const searchInput = $('#search');
const resultsEl = $('#search-results');
let results = [];
let activeResult = 0;

function runSearch(q) {
  q = q.trim().toLowerCase();
  if (q.length < 2) {
    state.matches = null;
    resultsEl.hidden = true;
    paintBuildings();
    return;
  }
  const { buildings } = state.city;
  const scored = [];
  buildings.forEach((b, i) => {
    if (!visible(b)) return;
    const name = b.name.toLowerCase();
    const id = b.id.toLowerCase();
    let score = -1;
    if (name === q) score = 0;
    else if (name.startsWith(q)) score = 1;
    else if (name.includes(q)) score = 2;
    else if (id.includes(q)) score = 3;
    if (score >= 0) scored.push({ i, score, len: name.length });
  });
  scored.sort((a, b) => a.score - b.score || a.len - b.len);
  state.matches = new Set(scored.map((s) => s.i));
  results = scored.slice(0, 12).map((s) => s.i);
  activeResult = 0;
  renderResults();
  paintBuildings();
}

function renderResults() {
  const { buildings } = state.city;
  resultsEl.innerHTML = results.length
    ? results.map((i, k) => `<li data-idx="${i}" class="${k === activeResult ? 'active' : ''}"><span class="r-name">${esc(buildings[i].name)}</span><span class="r-pkg">${esc(buildings[i].pkg)}</span></li>`).join('')
      + (state.matches.size > results.length ? `<li class="r-empty">${state.matches.size - results.length} more highlighted in the city</li>` : '')
    : '<li class="r-empty">No matching types</li>';
  resultsEl.hidden = false;
  resultsEl.querySelectorAll('li[data-idx]').forEach((li) => li.addEventListener('mousedown', (e) => {
    e.preventDefault();
    select(+li.dataset.idx);
    resultsEl.hidden = true;
  }));
}

searchInput.addEventListener('input', () => runSearch(searchInput.value));
searchInput.addEventListener('focus', () => { if (results.length && state.matches) resultsEl.hidden = false; });
searchInput.addEventListener('blur', () => setTimeout(() => (resultsEl.hidden = true), 100));
searchInput.addEventListener('keydown', (e) => {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    if (!results.length) return;
    activeResult = (activeResult + (e.key === 'ArrowDown' ? 1 : results.length - 1)) % results.length;
    renderResults();
  } else if (e.key === 'Enter' && results.length) {
    select(results[activeResult]);
    resultsEl.hidden = true;
    searchInput.blur();
  } else if (e.key === 'Escape') {
    searchInput.value = '';
    runSearch('');
    searchInput.blur();
  }
});

// ---------------------------------------------------------------- controls

function bindControls() {
  $('#height-metric').addEventListener('change', (e) => { state.heightKey = e.target.value; savePrefs(); updateHeights(); });
  $('#color-metric').addEventListener('change', (e) => { state.colorKey = e.target.value; savePrefs(); updateColors(); });
  const tests = $('#opt-tests');
  tests.checked = state.showTests;
  tests.addEventListener('change', () => {
    state.showTests = tests.checked;
    savePrefs();
    if (state.selected >= 0 && !visible(state.city.buildings[state.selected])) select(-1);
    updateHeights();
    updateColors();
    renderInspector();
    if (searchInput.value) runSearch(searchInput.value);
  });
  const labels = $('#opt-labels');
  labels.checked = state.labels;
  labels.addEventListener('change', () => { state.labels = labels.checked; savePrefs(); setLabelsVisible(state.labels); });
  const beacons = $('#opt-beacons');
  beacons.checked = state.beacons;
  beacons.addEventListener('change', () => { state.beacons = beacons.checked; savePrefs(); });
  const ao = $('#opt-ao');
  ao.checked = state.ao;
  ao.addEventListener('change', () => { state.ao = ao.checked; savePrefs(); gtao.enabled = state.ao; });
  $('#btn-theme').addEventListener('click', toggleTheme);
  $('#btn-frame').addEventListener('click', () => frameAll());
  $('#btn-walk').addEventListener('click', toggleWalk);
  $('#btn-snapshot').addEventListener('click', snapshot);
  $('#source-close').addEventListener('click', closeSource);
  $('#source-modal').addEventListener('click', (e) => { if (e.target.id === 'source-modal') closeSource(); });
}

function toggleTheme() {
  state.theme = state.theme === 'day' ? 'night' : 'day';
  savePrefs();
  applyTheme();
  placeSelectionMarker();
}

function snapshot() {
  render();
  const a = document.createElement('a');
  a.download = `${state.city.name}-codecity.png`;
  a.href = renderer.domElement.toDataURL('image/png');
  a.click();
}

addEventListener('keydown', (e) => {
  const typing = e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement;
  if (e.key === 'Escape') {
    // Esc leaves walk mode and does nothing else (the selection stays). The browser may also release the pointer
    // lock for this key press and so end walk mode first; the time check keeps that same press from clearing.
    if (walk) { exitWalk(); return; }
    if (performance.now() - walkExitedAt < 300) return;
    if (!$('#source-modal').hidden) closeSource();
    else if (state.selected >= 0) select(-1);
    else if (searchInput.value) { searchInput.value = ''; runSearch(''); }
    return;
  }
  if (typing || e.metaKey || e.ctrlKey || e.altKey) return;
  if (e.code === 'KeyG') { toggleWalk(); return; }
  if (walk && WALK_KEYS.has(e.code)) {
    e.preventDefault(); // Space would scroll or press a focused button, arrows would scroll
    walk.keys.add(e.code);
    return;
  }
  if (e.key === '/') { exitWalk(); e.preventDefault(); searchInput.focus(); searchInput.select(); }
  else if (e.key === 'f' || e.key === 'F') { exitWalk(); frameAll(); }
  else if (e.key === 'n' || e.key === 'N') toggleTheme();
});

addEventListener('keyup', (e) => {
  if (!walk || !WALK_KEYS.has(e.code)) return;
  e.preventDefault();
  walk.keys.delete(e.code);
});

addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
  composer.setSize(innerWidth, innerHeight);
  labelRenderer.setSize(innerWidth, innerHeight);
});

// ---------------------------------------------------------------- walk mode
//
// First-person walking through the city (G or the Walk button). walk.js does the movement and collision; this part
// owns the input, the camera and the HUD. On entering, the orbit camera (position, target, fov, near plane) is saved
// and OrbitControls is switched off; the canvas takes the pointer lock, so the mouse turns the view through
// movementX/Y and a click picks whatever is under the crosshair. Losing the pointer lock (Esc, switching windows)
// ends walk mode, as do G, Esc, / and F; the saved orbit camera is then restored exactly and the selection kept.
// The desktop viewers implement the same rules (desktop/Walker.java, gui/walk.rs, gui/walk.d).

const WALK_KEYS = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'ShiftLeft', 'ShiftRight', 'Space']);
let walk = null; // { player, world, city, keys, saved, locked, hintTimer } while walking
let walkExitedAt = -Infinity;

function toggleWalk() {
  if (walk) exitWalk();
  else enterWalk();
}

function enterWalk() {
  if (!state.city || walk) return;
  flight = null;
  const saved = { position: camera.position.clone(), target: controls.target.clone(), fov: camera.fov, near: camera.near };
  const world = Walk.makeWorld(state.city, heights.cur);
  let player;
  if (state.selected >= 0) {
    // On the street in front of the selected building, looking at it (yaw 0 looks towards −z).
    const b = state.city.buildings[state.selected];
    player = Walk.spawn(world, b.x, b.z + b.side / 2 + Walk.RADIUS + 0.2, 0);
  } else {
    // Where the orbit camera looks, facing the way it faces.
    const dir = controls.target.clone().sub(camera.position);
    player = Walk.spawn(world, controls.target.x, controls.target.z, Math.atan2(-dir.x, -dir.z));
  }
  walk = { player, world, city: state.city, keys: new Set(), saved, locked: false, hintTimer: 0 };
  controls.enabled = false;
  setLabelsVisible(false);
  camera.fov = Walk.FOV;
  camera.near = Walk.NEAR;
  camera.updateProjectionMatrix();
  placeWalkCamera();

  document.activeElement?.blur?.(); // a focused Walk button would otherwise be "clicked" by Space
  showTooltip(null);
  canvas.style.cursor = '';
  document.body.classList.add('walking');
  $('#btn-walk').classList.add('active');
  $('#btn-walk').setAttribute('aria-pressed', 'true');
  $('#crosshair').hidden = false;
  const hint = $('#walk-hint');
  hint.hidden = false;
  hint.classList.remove('faded');
  walk.hintTimer = setTimeout(() => hint.classList.add('faded'), 6000);

  // Some browsers return a promise from requestPointerLock, older ones report failures through pointerlockerror.
  try {
    const p = canvas.requestPointerLock();
    if (p && p.catch) p.catch(() => walkLockFailed());
  } catch {
    walkLockFailed();
  }
}

function walkLockFailed() {
  if (!walk || walk.locked) return;
  exitWalk();
  toast('Walk mode needs mouse capture — click the Walk button or press G again');
}

function exitWalk() {
  if (!walk) return;
  const { saved, hintTimer } = walk;
  walk = null;
  walkExitedAt = performance.now();
  clearTimeout(hintTimer);
  if (document.pointerLockElement === canvas) document.exitPointerLock();
  camera.position.copy(saved.position);
  controls.target.copy(saved.target);
  camera.fov = saved.fov;
  camera.near = saved.near;
  camera.updateProjectionMatrix();
  controls.enabled = true;
  controls.update();
  setLabelsVisible(state.labels);

  document.body.classList.remove('walking');
  $('#btn-walk').classList.remove('active');
  $('#btn-walk').setAttribute('aria-pressed', 'false');
  $('#crosshair').hidden = true;
  $('#walk-hint').hidden = true;
  showTooltip(null);
  if (state.hovered >= 0) { state.hovered = -1; paintBuildings(); }
}

document.addEventListener('pointerlockchange', () => {
  if (!walk) return;
  if (document.pointerLockElement === canvas) walk.locked = true;
  else exitWalk();
});
document.addEventListener('pointerlockerror', () => walkLockFailed());
document.addEventListener('mousemove', (e) => {
  if (walk?.locked) Walk.look(walk.player, e.movementX, e.movementY);
});
addEventListener('blur', () => exitWalk());

function placeWalkCamera() {
  const p = walk.player;
  const eye = Walk.eye(p);
  camera.position.set(eye.x, eye.y, eye.z);
  camera.rotation.set(p.pitch, p.yaw, 0, 'YXZ');
}

// One frame of walking: refresh the world if the city or the heights changed, move, place the camera, and treat the
// building under the crosshair as hovered (highlight + tooltip just below the centre of the screen). Districts get no
// tooltip here: theirs offers double-click to zoom, which is off while walking.
function stepWalk(dt) {
  if (walk.city !== state.city) {
    walk.world = Walk.makeWorld(state.city, heights.cur);
    walk.city = state.city;
  } else {
    Walk.updateHeights(walk.world, state.city, heights.cur);
  }
  const k = walk.keys;
  const input = {
    forward: k.has('KeyW') || k.has('ArrowUp'),
    back: k.has('KeyS') || k.has('ArrowDown'),
    left: k.has('KeyA') || k.has('ArrowLeft'),
    right: k.has('KeyD') || k.has('ArrowRight'),
    run: k.has('ShiftLeft') || k.has('ShiftRight'),
    jump: k.has('Space'),
  };
  Walk.step(walk.player, dt, input, walk.world);
  placeWalkCamera();
  camera.updateMatrixWorld();

  const target = pick(innerWidth / 2, innerHeight / 2);
  const hovered = target?.type === 'building' ? target.index : -1;
  if (hovered === state.hovered) return;
  state.hovered = hovered;
  paintBuildings();
  if (hovered >= 0) {
    showTooltip(target, 0, 0);
    tooltip.style.left = `${Math.round((innerWidth - tooltip.offsetWidth) / 2)}px`;
    tooltip.style.top = `${Math.round(innerHeight / 2 + 22)}px`;
  } else {
    showTooltip(null);
  }
}

// ---------------------------------------------------------------- live updates

function listenForUpdates() {
  if (!live || !window.EventSource) return;
  const events = new EventSource('api/events');
  events.addEventListener('update', async () => {
    try {
      const city = await loadCity();
      buildCity(city, { previousHeights: heights.cur.slice() });
      populateSelects();
      updateColors();
      toast(`City rebuilt · ${fmt(city.stats.types)} types`);
    } catch (e) {
      toast(`Update failed: ${e.message}`);
    }
  });
}

// ---------------------------------------------------------------- loop

const clock = new THREE.Clock();
function render() {
  composer.render();
  labelRenderer.render(scene, camera);
}

function loop() {
  const frameTime = clock.getDelta();
  const dt = Math.min(frameTime, 0.1);
  const now = performance.now();
  stepFlight(now);
  stepHeights(now);
  stepArcs(dt);
  stepBeacons(now);
  // While walking the camera belongs to stepWalk; OrbitControls.update would turn it back towards its target.
  // walk.js caps the frame time itself (MAX_FRAME) and substeps it.
  if (walk) stepWalk(frameTime);
  else controls.update();
  render();
  requestAnimationFrame(loop);
}

async function main() {
  try {
    const city = await loadCity();
    state.city = city;
    populateSelects();
    bindControls();
    buildCity(city);
    $('#loading').classList.add('done');
    listenForUpdates();
    loop();
    if (!city.buildings.length) toast('No Java, TypeScript or Rust types found here');
  } catch (e) {
    const el = $('#loading');
    el.classList.add('error');
    el.innerHTML = `<span>Could not load the city: ${esc(e.message)}</span>`;
    console.error(e);
  }
}

main();
