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
let list: Craft[] = [];

export function craftList(): Craft[] {
  return list;
}

export function craftByHex(hex: string): Craft | undefined {
  return byHex.get(hex);
}

export function positionAt(craft: Craft, now: number): { lat: number; lon: number } {
  const dt = (now - craft.t) / 1000;
  if (dt <= 0 || craft.gs <= 0) return { lat: craft.lat, lon: craft.lon };
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
    }
  }
  for (const hex of byHex.keys()) {
    if (!seen.has(hex)) byHex.delete(hex);
  }
  list = Array.from(byHex.values());
}

export function synthetic(count: number, now: number): void {
  const rnd = mulberry32(0x51c7ace);
  const types = ['A320', 'B738', 'B77W', 'A359', 'E190', 'B739', 'A21N', 'B763', 'C172', 'B744'];
  const airlines = ['UAL', 'DAL', 'AAL', 'SWA', 'BAW', 'AFR', 'DLH', 'KLM', 'UAE', 'QFA', 'SIA', 'JBU', 'ACA', 'ANZ', 'THY'];
  const rows: FeedRow[] = [];
  for (let i = 0; i < count; i++) {
    const lat = -55 + ((rnd() + rnd()) / 2) * 120;
    const lon = -180 + rnd() * 360;
    const gs = Math.round(90 + rnd() * 470);
    const track = Math.round(rnd() * 360);
    const alt = Math.round((1 + rnd() * 42) * 1000);
    const callsign = airlines[i % airlines.length] + String(100 + Math.floor(rnd() * 8900));
    const type = types[i % types.length];
    const reg = 'N' + (10000 + i).toString(36).toUpperCase();
    rows.push([(i + 1).toString(16).padStart(6, '0'), callsign, lat, lon, alt, gs, track, type, reg]);
  }
  replaceAll(rows, now);
}

function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
