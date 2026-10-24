import './style.css';
import type { GeoJSONSource, Map, MapMouseEvent } from 'maplibre-gl';
import { PlaneLayer } from './plane-layer';
import type { Airport } from './airports';
import {
  craftByHex,
  craftList,
  notePosition,
  positionAt,
  applyFeedBody,
  synthetic,
  trackOf,
  type Craft,
  type FeedRow,
} from './traffic';

type FeedBody = {
  src?: string;
  ac?: FeedRow[];
  stale?: boolean;
  retryAfterMs?: number;
};

declare global {
  interface Window {
    __skytrace: {
      ready: boolean;
      firstMapLoadMs: number;
      fps: number;
      drawn: number;
      source: string;
      stale: boolean;
    };
    __map: Map;
    __samplePlane: () => { x: number; y: number } | null;
  }
}

const STUB_MS = 150_000;
const SUCCESS_MS = 5_000;
const FAIL_BASE_MS = 2_000;
const FAIL_CAP_MS = 60_000;

const started = performance.now();
const frameTimes: number[] = [];
const syntheticCount = readSynthetic();
let sourceName = syntheticCount ? 'synthetic' : 'connecting';
let feedStale = false;
window.__skytrace = {
  ready: false,
  firstMapLoadMs: 0,
  fps: 0,
  drawn: 0,
  source: sourceName,
  stale: false,
};

const stat = document.querySelector<HTMLElement>('#stat')!;
const form = document.querySelector<HTMLFormElement>('#search')!;
const q = document.querySelector<HTMLInputElement>('#q')!;
const hits = document.querySelector<HTMLUListElement>('#hits')!;
const panel = document.querySelector<HTMLElement>('#panel')!;
const pCs = document.querySelector<HTMLElement>('#p-cs')!;
const pType = document.querySelector<HTMLElement>('#p-type')!;
const pReg = document.querySelector<HTMLElement>('#p-reg')!;
const pAlt = document.querySelector<HTMLElement>('#p-alt')!;
const pGs = document.querySelector<HTMLElement>('#p-gs')!;
const pHdg = document.querySelector<HTMLElement>('#p-hdg')!;

let airports: Airport[] = [];
let shown: Airport[] = [];
let cursor = -1;
let selected: string | null = null;
let lastTrail = 0;
let airportsReady = false;
let pollAttempt = 0;
let pollTimer = 0;
let map!: Map;

const layer = new PlaneLayer();
layer.onFrame = noteFrame;

document.querySelector<HTMLButtonElement>('#close')!.addEventListener('click', clearSelection);
window.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') clearSelection();
});

form.addEventListener('submit', (event) => {
  event.preventDefault();
  const choice = shown[cursor >= 0 ? cursor : 0];
  if (choice) choose(choice);
});

q.addEventListener('input', () => {
  cursor = -1;
  renderHits(searchAirports(q.value));
});

q.addEventListener('keydown', (event) => {
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
  if (!shown.length) return;
  event.preventDefault();
  cursor = event.key === 'ArrowDown'
    ? Math.min(shown.length - 1, cursor + 1)
    : Math.max(0, cursor - 1);
  paintCursor();
});

window.setInterval(() => {
  paintStat();
  const craft = selected ? craftByHex(selected) : undefined;
  if (selected && !craft) clearSelection();
  else if (craft) {
    fillPanel(craft);
    extendTrail(performance.now());
  }
}, 250);

void boot();

async function boot() {
  const [ml, workerMod] = await Promise.all([
    import('maplibre-gl'),
    import('maplibre-gl/dist/maplibre-gl-worker.mjs?url'),
    import('maplibre-gl/dist/maplibre-gl.css'),
  ]);
  ml.setWorkerUrl(workerMod.default);
  map = new ml.Map({
    container: 'map',
    style: 'https://tiles.openfreemap.org/styles/dark',
    center: [12, 24],
    zoom: 1.8,
    attributionControl: false,
    canvasContextAttributes: { antialias: true },
  });

  window.__map = map;
  window.__samplePlane = samplePlane;

  map.on('load', () => {
    window.__skytrace.firstMapLoadMs = Math.round(performance.now() - started);
    map.addSource('airports', { type: 'geojson', data: emptyCollection() });
    map.addSource('trail', { type: 'geojson', data: emptyCollection() });
    map.addSource('planes', { type: 'geojson', data: emptyCollection() });
    map.addLayer({
      id: 'airports-large',
      type: 'circle',
      source: 'airports',
      filter: ['==', ['get', 'k'], 'L'],
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], 1.5, 1.7, 4, 3.2, 8, 5.5],
        'circle-color': '#e4b15a',
        'circle-stroke-width': 1,
        'circle-stroke-color': 'rgba(0,0,0,0.5)',
        'circle-opacity': 0.92,
      },
    });
    map.addLayer({
      id: 'airports-medium',
      type: 'circle',
      source: 'airports',
      minzoom: 4,
      filter: ['==', ['get', 'k'], 'M'],
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], 4, 1.6, 7, 3, 11, 4.5],
        'circle-color': '#8ea0b5',
        'circle-opacity': 0.8,
        'circle-stroke-width': 0.6,
        'circle-stroke-color': 'rgba(0,0,0,0.45)',
      },
    });
    map.addLayer({
      id: 'airport-label',
      type: 'symbol',
      source: 'airports',
      minzoom: 6,
      filter: ['==', ['get', 'k'], 'L'],
      layout: {
        'text-field': ['case', ['!=', ['get', 'iata'], ''], ['get', 'iata'], ['get', 'icao']],
        'text-font': ['Noto Sans Regular'],
        'text-size': 11,
        'text-offset': [0, 0.7],
        'text-anchor': 'top',
      },
      paint: {
        'text-color': '#e7eef6',
        'text-halo-color': '#0c1016',
        'text-halo-width': 1.2,
      },
    });
    const trailLayout = { 'line-cap': 'round' as const, 'line-join': 'round' as const };
    map.addLayer({
      id: 'trail-glow',
      type: 'line',
      source: 'trail',
      layout: trailLayout,
      paint: {
        'line-color': '#ffb020',
        'line-width': ['interpolate', ['linear'], ['zoom'], 1, 10, 6, 14, 10, 18, 14, 24],
        'line-opacity': 0.35,
        'line-blur': 1.5,
      },
    });
    map.addLayer({
      id: 'trail-casing',
      type: 'line',
      source: 'trail',
      layout: trailLayout,
      paint: {
        'line-color': '#1a1004',
        'line-width': ['interpolate', ['linear'], ['zoom'], 1, 6, 6, 8, 10, 11, 14, 15],
        'line-opacity': 0.95,
      },
    });
    map.addLayer({
      id: 'trail',
      type: 'line',
      source: 'trail',
      layout: trailLayout,
      paint: {
        'line-color': '#ffc14a',
        'line-width': ['interpolate', ['linear'], ['zoom'], 1, 3, 6, 4, 10, 5.5, 14, 7.5],
        'line-opacity': 1,
      },
    });
    map.addLayer(layer);
    if (airportsReady) publishAirports();
    if (syntheticCount) {
      synthetic(syntheticCount, performance.now());
      syncPlanes();
    } else {
      void poll();
    }
    window.__skytrace.ready = true;
    paintStat();
  });

  map.on('click', (event: MapMouseEvent) => {
    const hit = pick(event.point.x, event.point.y);
    if (hit) select(hit.hex);
    else clearSelection();
  });

  void import('./airports')
    .then((mod) => mod.loadAirports())
    .then((rows) => {
      airports = rows;
      airportsReady = true;
      if (map.isStyleLoaded()) publishAirports();
    })
    .catch((error) => {
      console.error(error);
    });
}

function readSynthetic(): number | null {
  const raw = new URLSearchParams(location.search).get('synthetic');
  if (raw == null || raw === '') return null;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) return null;
  return Math.min(100_000, Math.floor(n));
}

function noteFrame() {
  const now = performance.now();
  frameTimes.push(now);
  const cut = now - 1000;
  while (frameTimes.length && frameTimes[0] < cut) frameTimes.shift();
  window.__skytrace.fps = frameTimes.length;
  publishProbe();
}

function emptyCollection(): GeoJSON.FeatureCollection {
  return { type: 'FeatureCollection', features: [] };
}

function source(id: string): GeoJSONSource {
  const found = map.getSource(id);
  if (!found || found.type !== 'geojson') throw new Error(`missing source ${id}`);
  return found as GeoJSONSource;
}

function syncPlanes() {
  layer.crafts = craftList();
  const features: GeoJSON.Feature[] = layer.crafts.map((craft) => ({
    type: 'Feature',
    id: craft.hex,
    properties: { hex: craft.hex, hdg: Math.round(craft.track) },
    geometry: { type: 'Point', coordinates: [craft.lon, craft.lat] },
  }));
  source('planes').setData({ type: 'FeatureCollection', features });
}

function failureDelay(attempt: number): number {
  const exp = Math.min(FAIL_CAP_MS, FAIL_BASE_MS * 2 ** Math.min(attempt - 1, 5));
  return Math.round(Math.min(FAIL_CAP_MS, exp * (0.5 + Math.random() * 0.5)));
}

function armPoll(delay: number, reason: string | null) {
  window.clearTimeout(pollTimer);
  if (reason) console.info(`skytrace backoff ${delay}ms (${reason})`);
  pollTimer = window.setTimeout(() => void poll(), delay);
}

function markStale() {
  const base = sourceName.replace(/ · stale$/, '');
  sourceName = base && base !== 'connecting' && base !== 'feed unavailable' ? `${base} · stale` : 'feed unavailable';
}

function publishProbe() {
  window.__skytrace.drawn = layer.drawn;
  window.__skytrace.source = sourceName.replace(/ · stale$/, '');
  window.__skytrace.stale = feedStale;
}

async function poll() {
  try {
    const res = await fetch(feedUrl(), { cache: 'no-store' });
    let body: FeedBody | null = null;
    try {
      body = (await res.json()) as FeedBody;
    } catch {
      body = null;
    }
    const outcome = applyFeedBody(body, res.ok, performance.now());
    if (outcome.src) sourceName = outcome.src;
    if (body?.ac?.length) syncPlanes();
    if (!outcome.fresh) {
      pollAttempt += 1;
      feedStale = true;
      if (craftList().length) markStale();
      else sourceName = 'feed unavailable';
      const why = !res.ok ? `http ${res.status}` : body?.stale ? 'stale' : 'empty';
      armPoll(failureDelay(pollAttempt), `attempt ${pollAttempt}, ${why}`);
      paintStat();
      return;
    }
    feedStale = false;
    pollAttempt = 0;
    armPoll(SUCCESS_MS + Math.round(Math.random() * 800), null);
  } catch {
    applyFeedBody(null, false, performance.now());
    pollAttempt += 1;
    feedStale = true;
    if (craftList().length) markStale();
    else sourceName = 'feed unavailable';
    armPoll(failureDelay(pollAttempt), `attempt ${pollAttempt}, network`);
  }
  paintStat();
}

function feedUrl(): string {
  const bounds = map.getBounds();
  const latSpan = bounds.getNorth() - bounds.getSouth();
  const lonSpan = Math.abs(bounds.getEast() - bounds.getWest());
  let url = '/api/planes';
  if (latSpan <= 12 && lonSpan <= 18) {
    const qn = (n: number) => (Math.round(n * 2) / 2).toFixed(1);
    url = `/api/planes?lamin=${qn(bounds.getSouth())}&lamax=${qn(bounds.getNorth())}&lomin=${qn(bounds.getWest())}&lomax=${qn(bounds.getEast())}`;
  }
  const flag = new URLSearchParams(location.search).get('simulate429');
  if (flag == null || flag === '0' || flag === 'false') return url;
  return `${url}${url.includes('?') ? '&' : '?'}simulate429=1`;
}

function paintStat() {
  publishProbe();
  const count = craftList().length.toLocaleString('en-US');
  const fps = window.__skytrace.fps;
  stat.textContent = `${count} aircraft · ${sourceName}${fps ? ` · ${fps} fps` : ''}`;
}

function pick(x: number, y: number): Craft | null {
  const now = performance.now();
  const crafts = craftList();
  let best: Craft | null = null;
  let bestD = 18 * 18;
  for (let i = 0; i < crafts.length; i++) {
    const p = positionAt(crafts[i], now);
    const s = map.project([p.lon, p.lat]);
    const dx = s.x - x;
    const dy = s.y - y;
    const d = dx * dx + dy * dy;
    if (d < bestD) {
      bestD = d;
      best = crafts[i];
    }
  }
  return best;
}

function samplePlane(): { x: number; y: number } | null {
  const now = performance.now();
  const w = map.getContainer().clientWidth;
  const h = map.getContainer().clientHeight;
  let best: { x: number; y: number; score: number } | null = null;
  for (const craft of craftList()) {
    const p = positionAt(craft, now);
    const s = map.project([p.lon, p.lat]);
    if (s.x < 28 || s.y < 88 || s.x > w - 28 || s.y > h - 52) continue;
    const dx = s.x - w / 2;
    const dy = s.y - h / 2;
    const score = dx * dx + dy * dy;
    if (!best || score < best.score) best = { x: s.x, y: s.y, score };
  }
  return best ? { x: best.x, y: best.y } : null;
}

function select(hex: string) {
  const craft = craftByHex(hex);
  if (!craft) return;
  selected = hex;
  layer.selected = hex;
  lastTrail = 0;
  panel.hidden = false;
  fillPanel(craft);
  extendTrail(performance.now(), true);
}

function clearSelection() {
  selected = null;
  layer.selected = null;
  panel.hidden = true;
  if (map?.getSource('trail')) source('trail').setData(emptyCollection());
}

function fillPanel(craft: Craft) {
  pCs.textContent = craft.callsign || craft.hex.toUpperCase();
  pType.textContent = craft.type || '—';
  pReg.textContent = craft.reg || '—';
  pAlt.textContent = `${Math.round(craft.alt).toLocaleString('en-US')} ft`;
  pGs.textContent = `${Math.round(craft.gs).toLocaleString('en-US')} kt`;
  const heading = Math.round(((craft.track % 360) + 360) % 360);
  pHdg.textContent = `${String(heading).padStart(3, '0')}°`;
}

function extendTrail(now: number, force = false) {
  if (!selected || !map?.getSource('trail')) return;
  const craft = craftByHex(selected);
  if (!craft) return;
  if (force || now - lastTrail >= 1000) {
    const p = positionAt(craft, now);
    notePosition(selected, p.lon, p.lat, now);
    lastTrail = now;
  }
  source('trail').setData(trailCollection(craft, now));
}

function trailCollection(craft: Craft, now: number): GeoJSON.FeatureCollection {
  const fixes = trackOf(craft.hex);
  const head = positionAt(craft, now);
  const real: [number, number][] = fixes.map((fix) => [fix[0], fix[1]]);
  const tip: [number, number] = [head.lon, head.lat];
  if (!real.length || !samePoint(real[real.length - 1], tip)) real.push(tip);

  const features: GeoJSON.Feature[] = [];
  const originT = fixes[0]?.[2] ?? craft.t;
  const span = fixes.length ? now - originT : 0;
  if (span < STUB_MS && craft.gs >= 40 && real[0]) {
    const origin = real[0];
    const anchor = positionAt({ ...craft, lon: origin[0], lat: origin[1], t: originT }, originT - STUB_MS);
    features.push(...lineFeatures([[anchor.lon, anchor.lat], origin], 'stub'));
  }
  features.push(...lineFeatures(real, 'real'));
  return { type: 'FeatureCollection', features };
}

function samePoint(a: [number, number], b: [number, number]) {
  return Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6;
}

function lineFeatures(points: [number, number][], kind: 'stub' | 'real'): GeoJSON.Feature[] {
  const lines: [number, number][][] = [];
  let current: [number, number][] = [];
  for (const point of points) {
    const prev = current[current.length - 1];
    if (prev && Math.abs(prev[0] - point[0]) > 180) {
      if (current.length >= 2) lines.push(current);
      current = [];
    }
    current.push(point);
  }
  if (current.length >= 2) lines.push(current);
  return lines.map((coordinates) => ({
    type: 'Feature',
    properties: { kind },
    geometry: { type: 'LineString', coordinates },
  }));
}

function publishAirports() {
  const features: GeoJSON.Feature[] = airports.map((airport) => ({
    type: 'Feature',
    properties: { k: airport.kind, iata: airport.iata, icao: airport.icao },
    geometry: { type: 'Point', coordinates: [airport.lon, airport.lat] },
  }));
  source('airports').setData({ type: 'FeatureCollection', features });
}

function searchAirports(query: string): Airport[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  const scored: { airport: Airport; score: number }[] = [];
  for (const airport of airports) {
    const iata = airport.iata.toLowerCase();
    const icao = airport.icao.toLowerCase();
    const name = airport.name.toLowerCase();
    const city = airport.city.toLowerCase();
    let score = 0;
    if (iata && iata === needle) score = 100;
    else if (icao === needle) score = 92;
    else if (iata.startsWith(needle)) score = 80;
    else if (icao.startsWith(needle)) score = 70;
    else if (name.startsWith(needle)) score = 60;
    else if (city.startsWith(needle)) score = 50;
    else if (name.includes(needle)) score = 40;
    else if (city.includes(needle)) score = 30;
    if (score) scored.push({ airport, score: score + (airport.kind === 'L' ? 2 : 0) });
  }
  scored.sort((a, b) => b.score - a.score || a.airport.name.localeCompare(b.airport.name));
  return scored.slice(0, 8).map((item) => item.airport);
}

function renderHits(list: Airport[]) {
  shown = list;
  hits.replaceChildren();
  list.forEach((airport, index) => {
    const li = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    if (index === cursor) button.setAttribute('aria-selected', 'true');
    const code = document.createElement('span');
    code.className = 'code';
    code.textContent = [airport.iata, airport.icao].filter(Boolean).join(' · ');
    button.append(code, `  ${airport.name}${airport.city ? ` · ${airport.city}` : ''}`);
    button.addEventListener('click', () => choose(airport));
    li.append(button);
    hits.append(li);
  });
  hits.hidden = list.length === 0;
}

function paintCursor() {
  const buttons = hits.querySelectorAll('button');
  buttons.forEach((button, index) => {
    if (index === cursor) button.setAttribute('aria-selected', 'true');
    else button.removeAttribute('aria-selected');
  });
}

function choose(airport: Airport) {
  hits.hidden = true;
  q.value = airport.iata || airport.icao || airport.name;
  map?.flyTo({ center: [airport.lon, airport.lat], zoom: 11, essential: true, duration: 1400 });
}
