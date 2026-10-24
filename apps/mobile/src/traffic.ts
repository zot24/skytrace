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
};

const DEG = Math.PI / 180;
const NM_PER_DEG = 60;

const byHex = new Map<string, Craft>();
const tracks = new Map<string, [number, number, number][]>();
let list: Craft[] = [];

const TRACK_CAP = 180;
const TRACK_MIN_DEG = 0.0015;

export function craftList(): Craft[] {
  return list;
}

export function craftByHex(hex: string): Craft | undefined {
  return byHex.get(hex);
}

export function notePosition(hex: string, lon: number, lat: number, t: number) {
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

export function trackOf(hex: string): readonly [number, number, number][] {
  return tracks.get(hex) ?? [];
}

export function positionAt(craft: Craft, now: number): { lat: number; lon: number } {
  const dt = (now - craft.t) / 1000;
  if (dt === 0 || craft.gs <= 0) return { lat: craft.lat, lon: craft.lon };
  const distNm = craft.gs * (dt / 3600);
  const rad = craft.track * DEG;
  const dLat = (distNm * Math.cos(rad)) / NM_PER_DEG;
  const cosLat = Math.cos(craft.lat * DEG);
  const dLon = (distNm * Math.sin(rad)) / (NM_PER_DEG * Math.max(0.15, Math.abs(cosLat)));
  let lat = craft.lat + dLat;
  let lon = craft.lon + dLon;
  if (lat > 85) lat = 85;
  if (lat < -85) lat = -85;
  if (lon > 180) lon -= 360;
  else if (lon < -180) lon += 360;
  return { lat, lon };
}

export function replaceAll(rows: FeedRow[], now: number) {
  const seen = new Set<string>();
  for (const row of rows) {
    const hex = row[0];
    if (!hex) continue;
    seen.add(hex);
    const existing = byHex.get(hex);
    if (existing) {
      existing.callsign = row[1] || existing.callsign;
      existing.lat = row[2];
      existing.lon = row[3];
      existing.alt = row[4];
      existing.gs = row[5];
      existing.track = row[6];
      existing.type = row[7] || existing.type;
      existing.reg = row[8] || existing.reg;
      existing.t = now;
      notePosition(hex, row[3], row[2], now);
    } else {
      byHex.set(hex, {
        hex,
        callsign: row[1],
        lat: row[2],
        lon: row[3],
        alt: row[4],
        gs: row[5],
        track: row[6],
        type: row[7],
        reg: row[8],
        t: now,
      });
      notePosition(hex, row[3], row[2], now);
    }
  }
  for (const hex of byHex.keys()) {
    if (!seen.has(hex)) {
      byHex.delete(hex);
      tracks.delete(hex);
    }
  }
  list = Array.from(byHex.values());
}

export function applyFeedBody(
  body: { src?: string; ac?: FeedRow[]; stale?: boolean } | null,
  ok: boolean,
  now: number,
): { fresh: boolean; src: string } {
  const fresh = !!body && ok && !body.stale && body.src !== 'none' && (body.ac?.length ?? 0) > 0;
  if (fresh && body?.ac) {
    replaceAll(body.ac, now);
    return { fresh: true, src: body.src || 'adsb.lol' };
  }
  if (body?.ac?.length) {
    replaceAll(body.ac, now);
    const src = body.src && body.src !== 'none' ? body.src : '';
    return { fresh: false, src };
  }
  return { fresh: false, src: '' };
}
