export type FeedRow = [
  hex: string,
  callsign: string,
  lat: number,
  lon: number,
  altFt: number,
  gsKt: number,
  trackDeg: number,
  type: string,
  reg: string,
];

export type Craft = {
  hex: string;
  callsign: string;
  type: string;
  reg: string;
  alt: number;
  gs: number;
  track: number;
  lat: number;
  lon: number;
  t: number;
  easeT: number;
  easeErrLat: number;
  easeErrLon: number;
};

const DEG = Math.PI / 180;
const NM_PER_DEG = 60;
const COAST_MS = 60_000;
const FADE_MS = 10_000;
const EASE_MS = 1_000;
const TRACK_CAP = 180;
const TRACK_MIN_DEG = 0.0015;

export function opacityAt(craft: Craft, now: number): number {
  const age = now - craft.t;
  if (age <= COAST_MS) return 1;
  const faded = (age - COAST_MS) / FADE_MS;
  if (faded >= 1) return 0;
  return 1 - faded;
}

export function positionAt(craft: Craft, now: number): { lat: number; lon: number } {
  const seconds = craft.gs <= 0 ? 0 : coastSeconds(craft.t, now);
  const goal = seconds === 0
    ? { lat: craft.lat, lon: craft.lon }
    : project(craft.lat, craft.lon, craft.gs, craft.track, seconds);
  const elapsed = now - craft.easeT;
  if (elapsed < 0 || elapsed >= EASE_MS || (craft.easeErrLat === 0 && craft.easeErrLon === 0)) return goal;
  const u = elapsed / EASE_MS;
  const s = u * u * (3 - 2 * u);
  return {
    lat: clampLat(goal.lat + craft.easeErrLat * (1 - s)),
    lon: wrapLon(goal.lon + craft.easeErrLon * (1 - s)),
  };
}

export function createFleet() {
  const byHex = new Map<string, Craft>();
  const tracks = new Map<string, [number, number, number][]>();
  let list: Craft[] = [];

  function notePosition(hex: string, lon: number, lat: number, t: number) {
    let path = tracks.get(hex);
    if (!path) {
      path = [];
      tracks.set(hex, path);
    }
    const prev = path[path.length - 1];
    if (prev) {
      let dLon = Math.abs(prev[0] - lon);
      if (dLon > 180) dLon = 360 - dLon;
      if (dLon + Math.abs(prev[1] - lat) < TRACK_MIN_DEG) return;
    }
    path.push([lon, lat, t]);
    if (path.length > TRACK_CAP) path.shift();
  }

  function trackOf(hex: string): readonly [number, number, number][] {
    return tracks.get(hex) ?? [];
  }

  function publish() {
    list = Array.from(byHex.values());
  }

  function dropFaded(now: number) {
    for (const [hex, craft] of byHex) {
      if (now - craft.t >= COAST_MS + FADE_MS) {
        byHex.delete(hex);
        tracks.delete(hex);
      }
    }
  }

  function adopt(craft: Craft, row: FeedRow, now: number) {
    const shown = positionAt(craft, now);
    const lat = row[2];
    const lon = row[3];
    let errLon = shown.lon - lon;
    if (errLon > 180) errLon -= 360;
    else if (errLon < -180) errLon += 360;
    craft.callsign = row[1] || craft.callsign;
    craft.lat = lat;
    craft.lon = lon;
    craft.alt = row[4];
    craft.gs = row[5];
    craft.track = row[6];
    craft.type = row[7] || craft.type;
    craft.reg = row[8] || craft.reg;
    craft.t = now;
    craft.easeT = now;
    craft.easeErrLat = shown.lat - lat;
    craft.easeErrLon = errLon;
    notePosition(craft.hex, lon, lat, now);
  }

  function place(row: FeedRow, now: number): Craft {
    return {
      hex: row[0],
      callsign: row[1],
      lat: row[2],
      lon: row[3],
      alt: row[4],
      gs: row[5],
      track: row[6],
      type: row[7],
      reg: row[8],
      t: now,
      easeT: now,
      easeErrLat: 0,
      easeErrLon: 0,
    };
  }

  function insert(row: FeedRow, now: number) {
    const hex = row[0];
    byHex.set(hex, place(row, now));
    notePosition(hex, row[3], row[2], now);
  }

  function replaceAll(rows: FeedRow[], now: number) {
    const seen = new Set<string>();
    for (const row of rows) {
      const hex = row[0];
      if (!hex) continue;
      seen.add(hex);
      const existing = byHex.get(hex);
      const next = place(row, now);
      if (existing) Object.assign(existing, next);
      else byHex.set(hex, next);
      notePosition(hex, row[3], row[2], now);
    }
    for (const hex of byHex.keys()) {
      if (!seen.has(hex)) {
        byHex.delete(hex);
        tracks.delete(hex);
      }
    }
    publish();
  }

  function applyFeedBody(
    body: { src?: string; ac?: FeedRow[]; stale?: boolean } | null,
    ok: boolean,
    now: number,
  ): { fresh: boolean; src: string } {
    const fresh = !!body && ok && !body.stale && body.src !== 'none' && (body.ac?.length ?? 0) > 0;
    if (fresh && body?.ac) {
      for (const row of body.ac) {
        const hex = row[0];
        if (!hex) continue;
        const existing = byHex.get(hex);
        if (existing) adopt(existing, row, now);
        else insert(row, now);
      }
    } else if (body?.ac?.length) {
      for (const row of body.ac) {
        const hex = row[0];
        if (!hex || byHex.has(hex)) continue;
        insert(row, now);
      }
    }
    dropFaded(now);
    publish();
    if (fresh && body) return { fresh: true, src: body.src || 'adsb.lol' };
    const src = body?.src && body.src !== 'none' ? body.src : '';
    return { fresh: false, src };
  }

  return {
    craftList: () => list,
    craftByHex: (hex: string) => byHex.get(hex),
    notePosition,
    trackOf,
    replaceAll,
    applyFeedBody,
  };
}

function coastSeconds(fixT: number, now: number): number {
  const dt = (now - fixT) / 1000;
  if (dt > COAST_MS / 1000) return COAST_MS / 1000;
  return dt;
}

function project(lat: number, lon: number, gs: number, track: number, seconds: number) {
  const distNm = gs * (seconds / 3600);
  const rad = track * DEG;
  const dLat = (distNm * Math.cos(rad)) / NM_PER_DEG;
  const cosLat = Math.cos(lat * DEG);
  const dLon = (distNm * Math.sin(rad)) / (NM_PER_DEG * Math.max(0.15, Math.abs(cosLat)));
  return { lat: clampLat(lat + dLat), lon: wrapLon(lon + dLon) };
}

function clampLat(lat: number): number {
  if (lat > 85) return 85;
  if (lat < -85) return -85;
  return lat;
}

function wrapLon(lon: number): number {
  let value = lon;
  while (value > 180) value -= 360;
  while (value < -180) value += 360;
  return value;
}
