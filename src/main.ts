import 'maplibre-gl/dist/maplibre-gl.css';
import './style.css';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import { GeoJSONSource, Map, setWorkerUrl, type MapMouseEvent } from 'maplibre-gl';

setWorkerUrl(workerUrl);
import { PlaneLayer } from './plane-layer';
import {
  craftByHex,
  craftList,
  positionAt,
  replaceAll,
  synthetic,
  type Craft,
  type FeedRow,
} from './traffic';

type Airport = {
  icao: string;
  iata: string;
  name: string;
  lat: number;
  lon: number;
  kind: 'L' | 'M';
  city: string;
};

type AirportRow = [string, string, string, number, number, 'L' | 'M', string];

declare global {
  interface Window {
    __skytrace: { ready: boolean; firstMapLoadMs: number; fps: number };
    __map: Map;
    __samplePlane: () => { x: number; y: number } | null;
  }
}

const started = performance.now();
const frameTimes: number[] = [];
window.__skytrace = { ready: false, firstMapLoadMs: 0, fps: 0 };

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

const syntheticCount = readSynthetic();
let sourceName = syntheticCount ? 'synthetic' : 'connecting';
let airports: Airport[] = [];
let shown: Airport[] = [];
let cursor = -1;
let selected: string | null = null;
let trail: [number, number][] = [];
let lastTrail = 0;
let airportsReady = false;

const layer = new PlaneLayer();
layer.onFrame = noteFrame;

const map = new Map({
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
  map.addLayer({
    id: 'trail',
    type: 'line',
    source: 'trail',
    paint: {
      'line-color': '#e6b15a',
      'line-width': 2,
      'line-opacity': 0.9,
    },
    layout: { 'line-cap': 'round', 'line-join': 'round' },
  });
  map.addLayer(layer);
  if (airportsReady) publishAirports();
  if (syntheticCount) {
    synthetic(syntheticCount, performance.now());
    syncPlanes();
  } else {
    void poll();
    window.setInterval(() => void poll(), 5000);
  }
  window.__skytrace.ready = true;
  paintStat();
});

map.on('click', (event: MapMouseEvent) => {
  const hit = pick(event.point.x, event.point.y);
  if (hit) select(hit.hex);
  else clearSelection();
});

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

void loadAirports();

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
}

function emptyCollection(): GeoJSON.FeatureCollection {
  return { type: 'FeatureCollection', features: [] };
}

function source(id: string): GeoJSONSource {
  const found = map.getSource(id);
  if (!(found instanceof GeoJSONSource)) throw new Error(`missing source ${id}`);
  return found;
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

async function poll() {
  try {
    const res = await fetch(feedUrl(), { cache: 'no-store' });
    if (!res.ok) throw new Error(String(res.status));
    const body = (await res.json()) as { src?: string; ac?: FeedRow[] };
    if (!body.ac || body.src === 'none') {
      sourceName = 'feed unavailable';
    } else {
      replaceAll(body.ac, performance.now());
      syncPlanes();
      sourceName = body.src || 'adsb.lol';
    }
  } catch {
    sourceName = 'feed unavailable';
  }
  paintStat();
}

function feedUrl(): string {
  const bounds = map.getBounds();
  const latSpan = bounds.getNorth() - bounds.getSouth();
  const lonSpan = Math.abs(bounds.getEast() - bounds.getWest());
  if (latSpan > 12 || lonSpan > 18) return '/api/planes';
  const qn = (n: number) => (Math.round(n * 2) / 2).toFixed(1);
  return `/api/planes?lamin=${qn(bounds.getSouth())}&lamax=${qn(bounds.getNorth())}&lomin=${qn(bounds.getWest())}&lomax=${qn(bounds.getEast())}`;
}

function paintStat() {
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
  trail = [];
  lastTrail = 0;
  panel.hidden = false;
  fillPanel(craft);
  extendTrail(performance.now(), true);
}

function clearSelection() {
  selected = null;
  layer.selected = null;
  panel.hidden = true;
  trail = [];
  source('trail').setData(emptyCollection());
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
  if (!selected) return;
  const craft = craftByHex(selected);
  if (!craft) return;
  const p = positionAt(craft, now);
  if (force || now - lastTrail >= 1000) {
    const prev = trail[trail.length - 1];
    const moved = !prev || Math.abs(prev[0] - p.lon) > 180
      || Math.abs(prev[0] - p.lon) + Math.abs(prev[1] - p.lat) > 0.01;
    if (moved) {
      trail.push([p.lon, p.lat]);
      if (trail.length > 700) trail.shift();
    }
    lastTrail = now;
  }
  const head: [number, number] = [p.lon, p.lat];
  const coords = trail.length && samePoint(trail[trail.length - 1], head) ? trail : trail.concat([head]);
  source('trail').setData(trailFeature(coords));
}

function samePoint(a: [number, number], b: [number, number]) {
  return Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6;
}

function trailFeature(points: [number, number][]): GeoJSON.FeatureCollection {
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
  return {
    type: 'FeatureCollection',
    features: lines.map((coordinates) => ({
      type: 'Feature',
      properties: {},
      geometry: { type: 'LineString', coordinates },
    })),
  };
}

async function loadAirports() {
  const rows = (await fetch('/airports.json').then((res) => res.json())) as AirportRow[];
  airports = rows.map((row) => ({
    icao: row[0],
    iata: row[1],
    name: row[2],
    lat: row[3],
    lon: row[4],
    kind: row[5],
    city: row[6],
  }));
  airportsReady = true;
  if (map.isStyleLoaded()) publishAirports();
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
  map.flyTo({ center: [airport.lon, airport.lat], zoom: 11, essential: true, duration: 1400 });
}
