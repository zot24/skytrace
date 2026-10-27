import { DurableObject } from "cloudflare:workers";

interface Env {
  POLLER: DurableObjectNamespace<Poller>;
  TRAILS: R2Bucket;
  POLL_SECONDS: string;
  TILES: string;
}

type Point = [t: number, lat: number, lon: number, alt: number, gs: number, track: number];
type Bbox = [minLon: number, minLat: number, maxLon: number, maxLat: number];
type Aircraft = Partial<Record<"hex" | "flight" | "lat" | "lon" | "alt_baro" | "gs" | "track" | "seen_pos", unknown>>;
type Query = { hex: string; since: number } | { boxes: Bbox[]; since: number };

interface Flight {
  id: string;
  hex: string;
  callsign: string;
  points: Point[];
  seen: number;
  dirty: boolean;
}

interface Trail {
  id: string;
  hex: string;
  callsign: string;
  open: boolean;
  points: Point[];
}

const KEEP_EVERY_MS = 60_000;
const CLOSE_UNSEEN_MS = 10 * 60_000;
const RETAIN_CLOSED_MS = 7 * 24 * 3_600_000;
const MAX_BACKOFF_MS = 5 * 60_000;
const FETCH_TIMEOUT_MS = 10_000;
const MAX_TRAILS = 100;
const TURN_DEG = 5;
const CLIMB_FT = 300;
const SPEED_KT = 15;
const SIMPLIFY_DEG = 0.002;
const HEX = /^~?[0-9a-f]{6}$/;

export default {
  fetch: (req: Request, env: Env) => env.POLLER.get(env.POLLER.idFromName("global")).fetch(req),
};

export class Poller extends DurableObject<Env> {
  private flights = new Map<string, Flight>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    const sql = ctx.storage.sql;
    sql.exec("CREATE TABLE IF NOT EXISTS open (hex TEXT PRIMARY KEY, id TEXT, callsign TEXT, seen INTEGER, points TEXT)");
    sql.exec(
      "CREATE TABLE IF NOT EXISTS closed (key TEXT PRIMARY KEY, hex TEXT, ended INTEGER, minLon REAL, minLat REAL, maxLon REAL, maxLat REAL)",
    );
    sql.exec("CREATE INDEX IF NOT EXISTS closed_hex ON closed (hex, ended)");
    sql.exec("CREATE INDEX IF NOT EXISTS closed_ended ON closed (ended)");
    for (const r of sql.exec<{ hex: string; id: string; callsign: string; seen: number; points: string }>("SELECT * FROM open")) {
      this.flights.set(r.hex, { ...r, points: JSON.parse(r.points), dirty: false });
    }
    console.log(JSON.stringify({ restoredOpenFlights: this.flights.size }));
  }

  async fetch(req: Request): Promise<Response> {
    if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(Date.now());
    const url = new URL(req.url);
    if (url.pathname !== "/trails") return Response.json({ error: "not found" }, { status: 404 });
    if (req.method !== "GET") return Response.json({ error: "use GET" }, { status: 405, headers: { allow: "GET" } });
    const query = parseQuery(url.searchParams);
    if (typeof query === "string") return Response.json({ error: query }, { status: 400 });

    const open: Trail[] = [...this.flights.values()]
      .filter((f) => ("hex" in query ? f.hex === query.hex : query.boxes.some((b) => overlaps(extent(f.points), b))))
      .map((f) => ({ id: f.id, hex: f.hex, callsign: f.callsign, open: true, points: f.points.filter((p) => p[0] >= query.since) }))
      .filter((t) => t.points.length > 0)
      .sort((a, b) => b.points.at(-1)![0] - a.points.at(-1)![0]);
    const keys = this.closedKeys(query, MAX_TRAILS + 1);
    const closed = await Promise.all(keys.slice(0, Math.max(0, MAX_TRAILS - open.length)).map((k) => this.readClosed(k)));
    const trails = [
      ...open,
      ...closed.filter((t) => t !== null).map((t) => ({ ...t, points: t.points.filter((p) => p[0] >= query.since) })),
    ];
    return Response.json({ trails: trails.slice(0, MAX_TRAILS), truncated: open.length + keys.length > MAX_TRAILS });
  }

  async alarm(): Promise<void> {
    const kv = this.ctx.storage.kv;
    const tiles = this.env.TILES.split(";").map((t) => t.split(",").map(Number));
    const interval = Number(this.env.POLL_SECONDS) * 1000;
    const turn = kv.get<number>("turn") ?? 0;
    kv.put("turn", turn + 1);
    let failures = kv.get<number>("failures") ?? 0;
    let retryAfterMs = 0;
    try {
      const [lat, lon, nm] = tiles[turn % tiles.length];
      const res = await fetch(`https://api.adsb.lol/v2/point/${lat}/${lon}/${nm}`, {
        headers: { "user-agent": "skytrace-trails (local dev; github.com/zot24/skytrace)" },
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
      if (!res.ok) {
        retryAfterMs = (Number(res.headers.get("retry-after")) || 0) * 1000;
        throw new Error(`adsb.lol ${res.status}`);
      }
      const { ac } = await res.json<{ ac?: unknown }>();
      if (!Array.isArray(ac)) throw new Error("adsb.lol response has no ac array");
      const now = Date.now();
      const closed = await this.closeFinished(now);
      const kept = this.ingest(ac, now);
      const written = this.checkpoint();
      this.ctx.storage.sql.exec("DELETE FROM closed WHERE ended < ?", now - RETAIN_CLOSED_MS);
      failures = 0;
      console.log(
        JSON.stringify({ tile: `${lat},${lon}`, aircraft: ac.length, kept, open: this.flights.size, closed, checkpointRows: written }),
      );
    } catch (err) {
      failures++;
      console.log(JSON.stringify({ error: String(err), failures }));
    }
    kv.put("failures", failures);
    const delay = Math.min(Math.max(interval * 2 ** failures, retryAfterMs), MAX_BACKOFF_MS);
    await this.ctx.storage.setAlarm(Date.now() + delay);
  }

  private ingest(aircraft: Aircraft[], now: number): number {
    let kept = 0;
    for (const a of aircraft) {
      const hex = typeof a.hex === "string" ? a.hex.toLowerCase() : "";
      const lat = finite(a.lat);
      const lon = finite(a.lon);
      if (!HEX.test(hex) || lat === null || lon === null || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
      const onGround = a.alt_baro === "ground";
      let f = this.flights.get(hex);
      if (!f && onGround) continue;
      const t = now - Math.round(Math.min(Math.max(finite(a.seen_pos) ?? 0, 0), 60) * 1000);
      const p: Point = [t, lat, lon, onGround ? 0 : (finite(a.alt_baro) ?? 0), finite(a.gs) ?? 0, finite(a.track) ?? 0];
      if (!f) {
        const callsign = typeof a.flight === "string" ? a.flight.trim() : "";
        f = { id: `${hex}-${t}`, hex, callsign, points: [], seen: t, dirty: true };
        this.flights.set(hex, f);
      }
      f.seen = onGround ? -1 : Math.max(f.seen, t);
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
    let closed = 0;
    for (const f of [...this.flights.values()].filter((f) => f.seen < now - CLOSE_UNSEEN_MS)) {
      try {
        if (f.points.length > 1) await this.archive(f);
        this.flights.delete(f.hex);
        this.ctx.storage.sql.exec("DELETE FROM open WHERE hex = ?", f.hex);
        closed++;
      } catch (err) {
        console.log(JSON.stringify({ archiveError: String(err), flight: f.id }));
      }
    }
    return closed;
  }

  private async archive(f: Flight): Promise<void> {
    const points = simplify(f.points);
    const key = `${f.hex}/${new Date(points[0][0]).toISOString().slice(0, 10)}/${f.id}`;
    const trail: Trail = { id: f.id, hex: f.hex, callsign: f.callsign, open: false, points };
    const gz = await new Response(new Response(JSON.stringify(trail)).body!.pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
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

  private closedKeys(query: Query, limit: number): string[] {
    const sql = this.ctx.storage.sql;
    if ("hex" in query) {
      return sql
        .exec<{ key: string }>(
          "SELECT key FROM closed WHERE hex = ? AND ended >= ? ORDER BY ended DESC LIMIT ?",
          query.hex,
          query.since,
          limit,
        )
        .toArray()
        .map((r) => r.key);
    }
    const rows = query.boxes.flatMap((b) =>
      sql
        .exec<{ key: string; ended: number }>(
          "SELECT key, ended FROM closed WHERE maxLon >= ? AND maxLat >= ? AND minLon <= ? AND minLat <= ? AND ended >= ? ORDER BY ended DESC LIMIT ?",
          ...b,
          query.since,
          limit,
        )
        .toArray(),
    );
    return [...new Set(rows.sort((a, b) => b.ended - a.ended).map((r) => r.key))].slice(0, limit);
  }

  private async readClosed(key: string): Promise<Trail | null> {
    const obj = await this.env.TRAILS.get(key);
    if (!obj) return null;
    return new Response(obj.body.pipeThrough(new DecompressionStream("gzip"))).json<Trail>();
  }
}

function parseQuery(params: URLSearchParams): Query | string {
  const hex = params.get("hex")?.toLowerCase();
  const bbox = params.get("bbox");
  const since = params.has("since") ? Number(params.get("since")) : 0;
  if (!Number.isSafeInteger(since) || since < 0) return "since must be a unix time in milliseconds";
  if ((hex === undefined) === (bbox === null)) return "pass exactly one of hex=<icao> or bbox=minLon,minLat,maxLon,maxLat";
  if (hex !== undefined) return HEX.test(hex) ? { hex, since } : "hex must be 6 hex digits, e.g. 4ca7b5";
  const b = bbox!.split(",").map((v) => (v.trim() === "" ? NaN : Number(v)));
  if (b.length !== 4 || !b.every(Number.isFinite)) return "bbox must be four numbers: minLon,minLat,maxLon,maxLat";
  const [minLon, minLat, maxLon, maxLat] = b;
  if ([minLon, maxLon].some((v) => Math.abs(v) > 180) || [minLat, maxLat].some((v) => Math.abs(v) > 90)) {
    return "bbox longitudes must be within ±180 and latitudes within ±90";
  }
  if (minLat > maxLat) return "bbox minLat must not exceed maxLat";
  const boxes: Bbox[] =
    minLon <= maxLon
      ? [[minLon, minLat, maxLon, maxLat]]
      : [
          [minLon, minLat, 180, maxLat],
          [-180, minLat, maxLon, maxLat],
        ];
  return { boxes, since };
}

function finite(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
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
