type Ac = [string, string, number, number, number, number, number, string, string];

type Bbox = { south: number; north: number; west: number; east: number };

type AdsbAc = {
  hex?: string;
  flight?: string | null;
  lat?: number | null;
  lon?: number | null;
  alt_baro?: number | string | null;
  alt_geom?: number | null;
  gs?: number | null;
  track?: number | null;
  true_heading?: number | null;
  t?: string | null;
  r?: string | null;
  seen_pos?: number | null;
  lastPosition?: { lat?: number; lon?: number; seen_pos?: number } | null;
};

// adsb.lol no longer serves a world snapshot. /v2/point covers at most 250nm,
// so a global grid is refreshed a few tiles per cached response.
const TILES: Array<[number, number]> = [
  [40.6, -73.8], [33.6, -84.4], [41.9, -87.6], [32.9, -97.0], [39.8, -104.7],
  [34.0, -118.2], [47.5, -122.3], [25.8, -80.3], [43.7, -79.4], [61.2, -150.0],
  [21.3, -157.9], [19.4, -99.1], [4.7, -74.1], [-23.4, -46.6], [-34.8, -58.4],
  [51.5, -0.5], [50.0, 8.6], [41.9, 12.5], [40.4, -3.7], [59.3, 18.1],
  [55.8, 37.6], [41.0, 29.0], [25.3, 55.4], [30.1, 31.4], [-26.1, 28.2],
  [1.4, 103.8], [13.7, 100.5], [28.6, 77.2], [19.1, 72.9], [31.2, 121.5],
  [39.9, 116.4], [22.3, 114.2], [35.7, 139.8], [37.6, 127.0], [-33.9, 151.2],
  [-37.7, 145.0], [52.0, -30.0], [48.0, -15.0], [30.0, -45.0], [20.0, 160.0],
];

const TILE_TTL_MS = 120_000;
const BATCH = 3;
const tiles = new Map<string, { at: number; ac: Ac[] }>();
let cursor = 0;

export default {
  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname !== '/api/planes') return new Response('not found', { status: 404 });

    const cacheKey = new Request(`https://skytrace.internal${url.pathname}${url.search}`);
    const cached = await readCache(cacheKey);
    if (cached) return cached;

    const bbox = readBbox(url);
    let src: 'adsb.lol' | 'opensky' | 'none' = 'adsb.lol';
    let ac: Ac[] = [];
    try {
      await refresh(bbox ? cover(bbox) : nextBatch());
      ac = collect();
      if (ac.length === 0) throw new Error('adsb.lol returned no aircraft');
    } catch {
      try {
        ac = await openSky(bbox);
        src = 'opensky';
      } catch {
        src = 'none';
        ac = [];
      }
    }

    const headers = {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'public, max-age=5',
      'access-control-allow-origin': '*',
      'x-cached-at': String(Date.now()),
    };
    const res = new Response(JSON.stringify({ src, ts: Date.now(), ac }), { headers });
    try {
      await caches.default.put(cacheKey, res.clone());
    } catch {
      // Local wrangler can run without the Cache API.
    }
    return res;
  },
};

async function readCache(key: Request): Promise<Response | null> {
  try {
    const hit = await caches.default.match(key);
    if (!hit) return null;
    const at = Number(hit.headers.get('x-cached-at') || 0);
    if (Date.now() - at < 5000) return hit;
    await caches.default.delete(key);
  } catch {
    // Cache API is best-effort outside production workers.
  }
  return null;
}

function nextBatch(): Array<[number, number]> {
  const batch: Array<[number, number]> = [];
  for (let i = 0; i < BATCH; i++) batch.push(TILES[(cursor + i) % TILES.length]);
  cursor = (cursor + BATCH) % TILES.length;
  return batch;
}

async function refresh(list: Array<[number, number]>) {
  let index = 0;
  async function worker() {
    while (index < list.length) {
      const [lat, lon] = list[index++];
      const id = `${round1(lat)},${round1(lon)}`;
      const prev = tiles.get(id);
      if (prev && Date.now() - prev.at < 5000) continue;
      try {
        tiles.set(id, { at: Date.now(), ac: await adsbTile(round1(lat), round1(lon)) });
      } catch {
        // Keep the previous tile when a single upstream call is rate limited.
      }
    }
  }
  const workers = Math.min(2, list.length);
  await Promise.all(Array.from({ length: workers }, () => worker()));
}

function collect(): Ac[] {
  const now = Date.now();
  const byHex = new Map<string, Ac>();
  for (const [id, hit] of tiles) {
    if (now - hit.at > TILE_TTL_MS) {
      tiles.delete(id);
      continue;
    }
    for (const ac of hit.ac) byHex.set(ac[0], ac);
  }
  return [...byHex.values()];
}

async function adsbTile(lat: number, lon: number): Promise<Ac[]> {
  const body = (await fetchJson(`https://api.adsb.lol/v2/point/${lat}/${lon}/250`, 8000)) as { ac?: AdsbAc[] };
  const rows: Ac[] = [];
  for (const ac of body.ac ?? []) {
    const row = fromAdsb(ac);
    if (row) rows.push(row);
  }
  return rows;
}

function fromAdsb(ac: AdsbAc): Ac | null {
  if (!ac.hex) return null;
  let lat = num(ac.lat);
  let lon = num(ac.lon);
  let seen = num(ac.seen_pos) ?? 0;
  if ((lat == null || lon == null) && ac.lastPosition) {
    lat = num(ac.lastPosition.lat);
    lon = num(ac.lastPosition.lon);
    seen = num(ac.lastPosition.seen_pos) ?? seen;
  }
  if (lat == null || lon == null || seen > 30) return null;
  const track = Math.round(num(ac.track) ?? num(ac.true_heading) ?? 0);
  return [
    ac.hex.toLowerCase(),
    (ac.flight || '').trim(),
    round4(lat),
    round4(lon),
    altitudeFeet(ac.alt_baro, ac.alt_geom),
    Math.round(num(ac.gs) ?? 0),
    wrap360(track),
    (ac.t || '').trim(),
    (ac.r || '').trim(),
  ];
}

async function openSky(bbox: Bbox | null): Promise<Ac[]> {
  const url = new URL('https://opensky-network.org/api/states/all');
  if (bbox) {
    url.searchParams.set('lamin', String(bbox.south));
    url.searchParams.set('lamax', String(bbox.north));
    url.searchParams.set('lomin', String(bbox.west));
    url.searchParams.set('lomax', String(bbox.east));
  }
  const body = (await fetchJson(url.toString(), 12000)) as { states?: unknown[][] | null };
  if (!body.states) throw new Error('opensky empty');
  const rows: Ac[] = [];
  for (const state of body.states) {
    const row = fromOpenSky(state);
    if (row) rows.push(row);
  }
  if (rows.length === 0) throw new Error('opensky empty');
  return rows;
}

function fromOpenSky(state: unknown[]): Ac | null {
  const icao = typeof state[0] === 'string' ? state[0].toLowerCase() : '';
  const lon = num(state[5]);
  const lat = num(state[6]);
  if (!icao || lat == null || lon == null) return null;
  const onGround = state[8] === true;
  const altM = num(state[7]) ?? num(state[13]) ?? 0;
  const track = Math.round(num(state[10]) ?? 0);
  return [
    icao,
    typeof state[1] === 'string' ? state[1].trim() : '',
    round4(lat),
    round4(lon),
    onGround ? 0 : Math.round(altM * 3.28084),
    Math.round((num(state[9]) ?? 0) * 1.94384),
    wrap360(track),
    '',
    '',
  ];
}

async function fetchJson(url: string, ms: number): Promise<unknown> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    const res = await fetch(url, {
      signal: ctrl.signal,
      headers: { accept: 'application/json', 'user-agent': 'skytrace/0.1' },
    });
    if (!res.ok) throw new Error(`${res.status} ${url}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

function readBbox(url: URL): Bbox | null {
  const south = num(url.searchParams.get('lamin'));
  const north = num(url.searchParams.get('lamax'));
  const west = num(url.searchParams.get('lomin'));
  const east = num(url.searchParams.get('lomax'));
  if (south == null || north == null || west == null || east == null || south >= north) return null;
  return { south, north, west, east };
}

function cover(bbox: Bbox): Array<[number, number]> {
  const latSpan = bbox.north - bbox.south;
  const crosses = bbox.west > bbox.east;
  const lonSpan = crosses ? 360 - bbox.west + bbox.east : bbox.east - bbox.west;
  const midLat = (bbox.south + bbox.north) / 2;
  const midLon = normLon(bbox.west + lonSpan / 2);
  const cos = Math.max(0.25, Math.cos((midLat * Math.PI) / 180));
  if (latSpan <= 8 && lonSpan <= 8 / cos) return [[round1(midLat), round1(midLon)]];

  const out: Array<[number, number]> = [];
  const latStep = 6;
  for (let lat = bbox.south; lat <= bbox.north && out.length < 4; lat += latStep) {
    const step = Math.min(20, latStep / Math.max(0.25, Math.cos((lat * Math.PI) / 180)));
    const longs = crosses
      ? [...range(bbox.west, 180, step), ...range(-180, bbox.east, step)]
      : range(bbox.west, bbox.east, step);
    for (const lon of longs) {
      out.push([round1(lat), round1(normLon(lon))]);
      if (out.length >= 4) break;
    }
  }
  return out.length ? out : [[round1(midLat), round1(midLon)]];
}

function range(from: number, to: number, step: number): number[] {
  const values: number[] = [];
  for (let value = from; value <= to + 1e-6 && values.length < 8; value += step) values.push(value);
  return values;
}

function altitudeFeet(baro: number | string | null | undefined, geom: number | null | undefined): number {
  if (baro === 'ground') return 0;
  if (typeof baro === 'number') return Math.round(baro);
  if (typeof geom === 'number') return Math.round(geom);
  return 0;
}

function num(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() !== '') {
    const n = Number(value);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

function round4(n: number): number {
  return Math.round(n * 1e4) / 1e4;
}

function round1(n: number): number {
  return Math.round(n * 10) / 10;
}

function wrap360(deg: number): number {
  return ((deg % 360) + 360) % 360;
}

function normLon(lon: number): number {
  let value = lon;
  while (value > 180) value -= 360;
  while (value < -180) value += 360;
  return value;
}
