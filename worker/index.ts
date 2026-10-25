type Ac = [string, string, number, number, number, number, number, string, string, string];

type Bbox = { south: number; north: number; west: number; east: number };

type Snapshot = { src: 'adsb.lol' | 'opensky'; ac: Ac[]; at: number };

export interface Env {
  SKYTRACE_SIMULATE_429?: string;
}

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
  category?: string | null;
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

const AREA_CAP = 96;
const BATCH = 3;
const BACKOFF_BASE_MS = 2_000;
const BACKOFF_CAP_MS = 60_000;
const tiles = new Map<string, { at: number; ac: Ac[] }>();
let cursor = 0;
let failStreak = 0;
let backoffUntil = 0;
let lastGood: Snapshot | null = null;

class UpstreamError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = 'UpstreamError';
    this.status = status;
  }
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname !== '/api/planes') return new Response('not found', { status: 404 });

    const simulate = wantSimulated429(url, env);
    const cacheUrl = new URL(url);
    cacheUrl.searchParams.delete('simulate429');
    const cacheKey = new Request(`https://skytrace.internal${cacheUrl.pathname}${cacheUrl.search}`);
    if (!simulate) {
      const cached = await readCache(cacheKey);
      if (cached) return cached;
    }

    const now = Date.now();
    if (now < backoffUntil) {
      const retryAfterMs = holdBackoff(simulate ? 'simulated 429 holding' : 'upstream 429 holding');
      return respond(rememberTiles(), true, retryAfterMs, null);
    }

    const bbox = readBbox(cacheUrl);
    if (simulate) {
      const retryAfterMs = scheduleBackoff('simulated 429');
      return respond(rememberTiles(), true, retryAfterMs, null);
    }

    try {
      const outcome = await refresh(bbox ? cover(bbox) : nextBatch());
      if (outcome.rateLimited) {
        const retryAfterMs = scheduleBackoff('upstream 429');
        return respond(rememberTiles(), true, retryAfterMs, null);
      }
      const ac = collect();
      if (ac.length === 0) throw new UpstreamError(502, 'adsb.lol returned no aircraft');
      lastGood = { src: 'adsb.lol', ac, at: Date.now() };
      failStreak = 0;
      backoffUntil = 0;
      return respond(lastGood, false, 0, cacheKey);
    } catch (error) {
      if (statusOf(error) === 429) {
        const retryAfterMs = scheduleBackoff('upstream 429');
        return respond(rememberTiles(), true, retryAfterMs, null);
      }
      try {
        const ac = await openSky(bbox);
        lastGood = { src: 'opensky', ac, at: Date.now() };
        failStreak = 0;
        backoffUntil = 0;
        return respond(lastGood, false, 0, cacheKey);
      } catch (skyError) {
        const reason = statusOf(skyError) === 429 ? 'upstream 429' : 'upstream failure';
        const retryAfterMs = scheduleBackoff(reason);
        return respond(rememberTiles(), true, retryAfterMs, null);
      }
    }
  },
};

function wantSimulated429(url: URL, env: Env): boolean {
  const host = url.hostname;
  const local = host === '127.0.0.1' || host === 'localhost' || host === '::1';
  if (!local) return false;
  if (env.SKYTRACE_SIMULATE_429 === '1') return true;
  if (!url.searchParams.has('simulate429')) return false;
  const value = url.searchParams.get('simulate429');
  return value !== '0' && value !== 'false';
}

function scheduleBackoff(reason: string): number {
  failStreak += 1;
  const exp = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** Math.min(failStreak - 1, 5));
  const delay = Math.round(Math.min(BACKOFF_CAP_MS, exp * (0.5 + Math.random() * 0.5)));
  backoffUntil = Date.now() + delay;
  console.log(`skytrace backoff ${delay}ms (${reason}, attempt ${failStreak})`);
  return delay;
}

function holdBackoff(reason: string): number {
  const delay = Math.max(0, backoffUntil - Date.now());
  console.log(`skytrace backoff ${delay}ms (${reason}, attempt ${failStreak})`);
  return delay;
}

function rememberTiles(): Snapshot | null {
  const ac = collect();
  if (ac.length && (!lastGood || ac.length >= lastGood.ac.length)) {
    lastGood = { src: 'adsb.lol', ac, at: lastGood?.at ?? Date.now() };
  }
  return lastGood;
}

function respond(snap: Snapshot | null, stale: boolean, retryAfterMs: number, cacheKey: Request | null): Response {
  const ac = snap?.ac ?? [];
  const src = snap?.src ?? 'none';
  const status = ac.length ? 200 : stale ? 429 : 200;
  const headers: Record<string, string> = {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': stale ? 'no-store' : 'public, max-age=5',
    'access-control-allow-origin': '*',
    'x-cached-at': String(Date.now()),
  };
  if (stale) headers['retry-after'] = String(Math.max(1, Math.ceil(retryAfterMs / 1000)));
  const res = new Response(JSON.stringify({
    src,
    ts: snap?.at ?? Date.now(),
    stale,
    retryAfterMs: stale ? retryAfterMs : 0,
    ac,
  }), { status, headers });
  if (cacheKey && status === 200 && !stale) {
    void caches.default.put(cacheKey, res.clone()).catch(() => {
      // Local wrangler can run without the Cache API.
    });
  }
  return res;
}

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

async function refresh(list: Array<[number, number]>): Promise<{ rateLimited: boolean }> {
  let index = 0;
  let rateLimited = false;
  async function worker() {
    while (index < list.length && !rateLimited) {
      const [lat, lon] = list[index++];
      const id = `${round1(lat)},${round1(lon)}`;
      const prev = tiles.get(id);
      if (prev && Date.now() - prev.at < 5000) continue;
      try {
        putArea(id, await adsbTile(round1(lat), round1(lon)));
      } catch (error) {
        if (statusOf(error) === 429) rateLimited = true;
        // Keep the previous tile when a single upstream call fails.
      }
    }
  }
  const workers = Math.min(2, list.length);
  await Promise.all(Array.from({ length: workers }, () => worker()));
  return { rateLimited };
}

function putArea(id: string, ac: Ac[]): void {
  tiles.set(id, { at: Date.now(), ac });
  while (tiles.size > AREA_CAP) {
    let oldestId: string | null = null;
    let oldestAt = Infinity;
    for (const [key, hit] of tiles) {
      if (hit.at < oldestAt) {
        oldestAt = hit.at;
        oldestId = key;
      }
    }
    if (oldestId == null) break;
    tiles.delete(oldestId);
  }
}

function collect(): Ac[] {
  const byHex = new Map<string, Ac>();
  for (const hit of tiles.values()) {
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
    (ac.category || '').trim(),
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
    if (!res.ok) throw new UpstreamError(res.status, `${res.status} ${url}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

function statusOf(error: unknown): number {
  return error instanceof UpstreamError ? error.status : 0;
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
