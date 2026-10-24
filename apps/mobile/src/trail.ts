import { notePosition, positionAt, trackOf, type Craft } from './traffic';

const STUB_MS = 150_000;
const EMPTY = '{"type":"FeatureCollection","features":[]}';

export function emptyTrail(): string {
  return EMPTY;
}

export function extendTrail(hex: string, craft: Craft, now: number, lastNote: number): number {
  if (now - lastNote < 1000) return lastNote;
  const head = positionAt(craft, now);
  notePosition(hex, head.lon, head.lat, now);
  return now;
}

export function trailData(craft: Craft, now: number): string {
  const fixes = trackOf(craft.hex);
  const head = positionAt(craft, now);
  const real: [number, number][] = fixes.map((fix) => [fix[0], fix[1]]);
  const tip: [number, number] = [head.lon, head.lat];
  if (!real.length || !samePoint(real[real.length - 1]!, tip)) real.push(tip);

  const features: { kind: 'stub' | 'real'; coordinates: [number, number][] }[] = [];
  const originT = fixes[0]?.[2] ?? craft.t;
  const span = fixes.length ? now - originT : 0;
  if (span < STUB_MS && craft.gs >= 40 && real[0]) {
    const origin = real[0];
    const anchor = positionAt({ ...craft, lon: origin[0], lat: origin[1], t: originT }, originT - STUB_MS);
    features.push(...lineFeatures([[anchor.lon, anchor.lat], origin], 'stub'));
  }
  features.push(...lineFeatures(real, 'real'));
  return JSON.stringify({
    type: 'FeatureCollection',
    features: features.map((feature) => ({
      type: 'Feature',
      properties: { kind: feature.kind },
      geometry: { type: 'LineString', coordinates: feature.coordinates },
    })),
  });
}

function samePoint(a: [number, number], b: [number, number]) {
  return Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6;
}

function lineFeatures(
  points: [number, number][],
  kind: 'stub' | 'real',
): { kind: 'stub' | 'real'; coordinates: [number, number][] }[] {
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
  return lines.map((coordinates) => ({ kind, coordinates }));
}
