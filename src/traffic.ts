import { createFleet, type FeedRow } from './fleet';

export { opacityAt, positionAt, type Craft, type FeedRow } from './fleet';

const fleet = createFleet();

export const craftList = fleet.craftList;
export const craftByHex = fleet.craftByHex;
export const notePosition = fleet.notePosition;
export const trackOf = fleet.trackOf;
export const replaceAll = fleet.replaceAll;
export const applyFeedBody = fleet.applyFeedBody;

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
