import { DurableObject } from "cloudflare:workers";

interface Env {
  POLLER: DurableObjectNamespace<Poller>;
  TRAILS: R2Bucket;
  POLL_SECONDS: string;
  TILES: string;
}

type Point = [t: number, lat: number, lon: number, alt: number, gs: number, track: number];
type Bbox = [minLon: number, minLat: number, maxLon: number, maxLat: number];

interface Flight {
  id: string;
  hex: string;
  callsign: string;
  points: Point[];
  seen: number;
  dirty: boolean;
}

interface Aircraft {
  hex: string;
  flight?: string;
  lat?: number;
  lon?: number;
  alt_baro?: number | "ground";
  gs?: number;
  track?: number;
  seen_pos?: number;
}

const KEEP_EVERY_MS = 60_000;
const CLOSE_UNSEEN_MS = 10 * 60_000;
const MAX_BACKOFF_MS = 5 * 60_000;
const TURN_DEG = 5;
const CLIMB_FT = 300;
const SPEED_KT = 15;
const SIMPLIFY_DEG = 0.002;

export default {
  fetch: (req: Request, env: Env) => env.POLLER.get(env.POLLER.idFromName("global")).fetch(req),
};

export class Poller extends DurableObject<Env> {
  private flights = new Map<string, Flight>();
  private failures = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    const sql = ctx.storage.sql;
    sql.exec("CREATE TABLE IF NOT EXISTS open (hex TEXT PRIMARY KEY, id TEXT, callsign TEXT, seen INTEGER, points TEXT)");
    sql.exec(
      "CREATE TABLE IF NOT EXISTS closed (key TEXT PRIMARY KEY, hex TEXT, ended INTEGER, minLon REAL, minLat REAL, maxLon REAL, maxLat REAL)",
    );
    for (const r of sql.exec<{ hex: string; id: string; callsign: string; seen: number; points: string }>("SELECT * FROM open")) {
      this.flights.set(r.hex, { ...r, points: JSON.parse(r.points), dirty: false });
    }
    console.log(JSON.stringify({ restoredOpenFlights: this.flights.size }));
  }

  async fetch(req: Request): Promise<Response> {
    if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(Date.now());
    const url = new URL(req.url);
    if (url.pathname !== "/trails") return new Response("not found", { status: 404 });
    const hex = url.searchParams.get("hex")?.toLowerCase();
    const bbox = url.searchParams.get("bbox")?.split(",").map(Number) as Bbox | undefined;
    if (!hex && (bbox?.length !== 4 || bbox.some(Number.isNaN))) {
      return new Response("pass hex=<icao> or bbox=minLon,minLat,maxLon,maxLat", { status: 400 });
    }
    const open = [...this.flights.values()].filter((f) => (hex ? f.hex === hex : overlaps(extent(f.points), bbox!)));
    const keys = hex
      ? this.ctx.storage.sql.exec<{ key: string }>("SELECT key FROM closed WHERE hex = ? ORDER BY ended DESC LIMIT 50", hex)
      : this.ctx.storage.sql.exec<{ key: string }>(
          "SELECT key FROM closed WHERE maxLon >= ? AND maxLat >= ? AND minLon <= ? AND minLat <= ? ORDER BY ended DESC LIMIT 200",
          ...bbox!,
        );
    const closed = await Promise.all([...keys].map(({ key }) => this.readClosed(key)));
    return Response.json({
      trails: [
        ...open.map((f) => ({ id: f.id, hex: f.hex, callsign: f.callsign, open: true, points: f.points })),
        ...closed.filter((t) => t !== null),
      ],
    });
  }

  async alarm(): Promise<void> {
    const tiles = this.env.TILES.split(";").map((t) => t.split(",").map(Number));
    const interval = Number(this.env.POLL_SECONDS) * 1000;
    const [lat, lon, nm] = tiles[Math.floor(Date.now() / interval) % tiles.length];
    try {
      const res = await fetch(`https://api.adsb.lol/v2/point/${lat}/${lon}/${nm}`, {
        headers: { "user-agent": "skytrace-trails (local dev; github.com/zot24/skytrace)" },
      });
      if (!res.ok) throw new Error(`adsb.lol ${res.status}`);
      const { ac = [] } = await res.json<{ ac?: Aircraft[] }>();
      this.failures = 0;
      const kept = this.ingest(ac, Date.now());
      const closed = await this.closeFinished(Date.now());
      const written = this.checkpoint();
      console.log(
        JSON.stringify({ tile: `${lat},${lon}`, aircraft: ac.length, kept, open: this.flights.size, closed, checkpointRows: written }),
      );
    } catch (err) {
      this.failures++;
      console.log(JSON.stringify({ error: String(err), failures: this.failures }));
    }
    await this.ctx.storage.setAlarm(Date.now() + Math.min(interval * 2 ** this.failures, MAX_BACKOFF_MS));
  }

  private ingest(aircraft: Aircraft[], now: number): number {
    let kept = 0;
    for (const a of aircraft) {
      if (a.lat === undefined || a.lon === undefined) continue;
      const t = now - Math.round((a.seen_pos ?? 0) * 1000);
      const p: Point = [t, a.lat, a.lon, a.alt_baro === "ground" ? 0 : (a.alt_baro ?? 0), a.gs ?? 0, a.track ?? 0];
      let f = this.flights.get(a.hex);
      if (!f) {
        f = { id: `${a.hex}-${t}`, hex: a.hex, callsign: a.flight?.trim() ?? "", points: [], seen: t, dirty: true };
        this.flights.set(a.hex, f);
      }
      f.seen = Math.max(f.seen, t);
      if (a.alt_baro === "ground") f.seen = -1;
      const last = f.points.at(-1);
      if (!last || changed(last, p)) {
        f.points.push(p);
        f.dirty = true;
        kept++;
      }
    }
    return kept;
  }

  private async closeFinished(now: number): Promise<number> {
    const done = [...this.flights.values()].filter((f) => f.seen < now - CLOSE_UNSEEN_MS);
    for (const f of done) {
      this.flights.delete(f.hex);
      this.ctx.storage.sql.exec("DELETE FROM open WHERE hex = ?", f.hex);
      if (f.points.length < 2) continue;
      const points = simplify(f.points);
      const key = `${f.hex}/${new Date(points[0][0]).toISOString().slice(0, 10)}/${f.id}`;
      const body = new Response(JSON.stringify({ id: f.id, hex: f.hex, callsign: f.callsign, open: false, points })).body!;
      const gz = await new Response(body.pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
      await this.env.TRAILS.put(key, gz, { httpMetadata: { contentType: "application/json", contentEncoding: "gzip" } });
      this.ctx.storage.sql.exec(
        "INSERT OR REPLACE INTO closed VALUES (?, ?, ?, ?, ?, ?, ?)",
        key,
        f.hex,
        points.at(-1)![0],
        ...extent(points),
      );
      console.log(JSON.stringify({ closedFlight: key, points: f.points.length, simplified: points.length, bytes: gz.byteLength }));
    }
    return done.length;
  }

  private checkpoint(): number {
    const dirty = [...this.flights.values()].filter((f) => f.dirty);
    this.ctx.storage.transactionSync(() => {
      for (const f of dirty) {
        this.ctx.storage.sql.exec(
          "INSERT OR REPLACE INTO open VALUES (?, ?, ?, ?, ?)",
          f.hex,
          f.id,
          f.callsign,
          f.seen,
          JSON.stringify(f.points),
        );
        f.dirty = false;
      }
    });
    return dirty.length;
  }

  private async readClosed(key: string): Promise<unknown> {
    const obj = await this.env.TRAILS.get(key);
    if (!obj) return null;
    return new Response(obj.body.pipeThrough(new DecompressionStream("gzip"))).json();
  }
}

function changed(a: Point, b: Point): boolean {
  const turn = Math.abs(((b[5] - a[5] + 540) % 360) - 180);
  return b[0] - a[0] >= KEEP_EVERY_MS || turn >= TURN_DEG || Math.abs(b[3] - a[3]) >= CLIMB_FT || Math.abs(b[4] - a[4]) >= SPEED_KT;
}

function extent(points: Point[]): Bbox {
  const lons = points.map((p) => p[2]);
  const lats = points.map((p) => p[1]);
  return [Math.min(...lons), Math.min(...lats), Math.max(...lons), Math.max(...lats)];
}

function overlaps(a: Bbox, b: Bbox): boolean {
  return a[2] >= b[0] && a[3] >= b[1] && a[0] <= b[2] && a[1] <= b[3];
}

function simplify(points: Point[]): Point[] {
  if (points.length < 3) return points;
  const [a, b] = [points[0], points.at(-1)!];
  let worst = 0;
  let at = 0;
  for (let i = 1; i < points.length - 1; i++) {
    const d = offLine(points[i], a, b);
    if (d > worst) [worst, at] = [d, i];
  }
  if (worst < SIMPLIFY_DEG) return [a, b];
  return [...simplify(points.slice(0, at + 1)).slice(0, -1), ...simplify(points.slice(at))];
}

function offLine(p: Point, a: Point, b: Point): number {
  const [dx, dy] = [b[2] - a[2], b[1] - a[1]];
  const len = Math.hypot(dx, dy);
  if (len === 0) return Math.hypot(p[2] - a[2], p[1] - a[1]);
  return Math.abs(dy * p[2] - dx * p[1] + b[2] * a[1] - b[1] * a[2]) / len;
}
