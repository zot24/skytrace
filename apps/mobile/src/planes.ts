import type { Craft } from './traffic';
import { viewBox } from './view';

const EMPTY = '{"type":"FeatureCollection","features":[]}';

export function buildPlaneData(
  crafts: readonly Craft[],
  positions: Float64Array,
  selected: string | null,
): { json: string; count: number } {
  const pad = (80 * 360) / (512 * 2 ** Math.max(0, viewBox.zoom));
  const south = viewBox.south - pad;
  const north = viewBox.north + pad;
  const west = viewBox.west - pad;
  const east = viewBox.east + pad;
  const wraps = viewBox.east < viewBox.west;
  const parts: string[] = ['{"type":"FeatureCollection","features":['];
  let count = 0;
  for (let i = 0; i < crafts.length; i++) {
    const craft = crafts[i]!;
    const lon = positions[i * 2]!;
    const lat = positions[i * 2 + 1]!;
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
    const sel = craft.hex === selected;
    if (!sel && !inView(lat, lon, south, north, west, east, wraps)) continue;
    if (count) parts.push(',');
    const id = JSON.stringify(craft.hex);
    const hdg = Math.round(((craft.track % 360) + 360) % 360);
    parts.push(
      `{"type":"Feature","id":${id},"properties":{"hex":${id},"hdg":${hdg},"sel":${sel ? 1 : 0}},"geometry":{"type":"Point","coordinates":[${lon.toFixed(5)},${lat.toFixed(5)}]}}`,
    );
    count++;
  }
  if (!count) return { json: EMPTY, count: 0 };
  parts.push(']}');
  return { json: parts.join(''), count };
}

function inView(
  lat: number,
  lon: number,
  south: number,
  north: number,
  west: number,
  east: number,
  wraps: boolean,
): boolean {
  if (lat < south || lat > north) return false;
  let span = east - west;
  if (span < 0) span += 360;
  if (span >= 360) return true;
  if (!wraps && west <= east) {
    if (west < -180) return lon >= west + 360 || lon <= east;
    if (east > 180) return lon >= west || lon <= east - 360;
    return lon >= west && lon <= east;
  }
  return lon >= west || lon <= east;
}
