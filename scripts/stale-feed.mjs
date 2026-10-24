// Mocks adsb.lol and OpenSky. Never opens a socket to either host.
const realNow = Date.now;
let now = 1_800_000_000_000;
Date.now = () => now;

globalThis.caches = {
  default: {
    async match() {
      return undefined;
    },
    async put() {},
    async delete() {},
  },
};

const calls = { adsb: 0, opensky: 0 };
let adsbStatus = 200;
let openskyStatus = 200;

function reject(url) {
  throw new Error(`refusing live fetch ${url}`);
}

function tilePlanes(lat, lon) {
  if (Math.abs(lat) < 0.05 && Math.abs(lon) < 0.05) return [];
  const planes = [];
  for (let i = 0; i < 4; i++) {
    planes.push({
      hex: `t${lat}_${lon}_${i}`,
      flight: 'TST' + i,
      lat: 50.04,
      lon: 8.56,
      alt_baro: 32000,
      gs: 450,
      track: 90,
      t: 'B738',
      r: 'D-TEST',
      seen_pos: 1,
    });
  }
  return planes;
}

globalThis.fetch = async (input) => {
  const url = String(input);
  if (url.includes('api.adsb.lol')) {
    calls.adsb += 1;
    if (adsbStatus !== 200) return new Response('slow down', { status: adsbStatus });
    const match = url.match(/\/v2\/point\/(-?\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)\/250/);
    const lat = match ? Number(match[1]) : 0;
    const lon = match ? Number(match[2]) : 0;
    return Response.json({ ac: tilePlanes(lat, lon) });
  }
  if (url.includes('opensky-network.org')) {
    calls.opensky += 1;
    if (openskyStatus !== 200) return new Response('slow down', { status: openskyStatus });
    const states = [];
    for (let i = 0; i < 2; i++) {
      states.push([
        `os${i}`,
        'OSK' + i,
        'Germany',
        now / 1000,
        now / 1000,
        8.56,
        50.04,
        10000,
        false,
        231.5,
        90,
        0,
        null,
        10000,
        null,
        false,
        0,
      ]);
    }
    return Response.json({ states });
  }
  reject(url);
};

const { default: worker } = await import(new URL('../worker/index.ts', import.meta.url));
const web = await import(new URL('../src/traffic.ts', import.meta.url));
const mobile = await import(new URL('../apps/mobile/src/traffic.ts', import.meta.url));
const planes = await import(new URL('../apps/mobile/src/planes.ts', import.meta.url));
const view = await import(new URL('../apps/mobile/src/view.ts', import.meta.url));

const failures = [];
function check(name, ok, detail) {
  const line = `${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' ' + detail : ''}`;
  console.log(line);
  if (!ok) failures.push(line);
}

async function hit(path) {
  const res = await worker.fetch(new Request('http://127.0.0.1:8787' + path), {});
  const body = await res.json();
  return {
    status: res.status,
    src: body.src,
    stale: body.stale,
    n: body.ac.length,
    header: res.headers.get('x-skytrace-source'),
    body,
  };
}

function project(lat, lon, gs, track, seconds) {
  const distNm = gs * (seconds / 3600);
  const rad = (track * Math.PI) / 180;
  const dLat = (distNm * Math.cos(rad)) / 60;
  const cosLat = Math.cos((lat * Math.PI) / 180);
  const dLon = (distNm * Math.sin(rad)) / (60 * Math.max(0.15, Math.abs(cosLat)));
  return { lat: lat + dLat, lon: lon + dLon };
}

const fra = '/api/planes?lamin=49.9&lamax=50.2&lomin=8.3&lomax=8.9';

const primed = await hit('/api/planes');
check('prime count', primed.n === 12 && primed.src === 'adsb.lol' && primed.stale === false, JSON.stringify({ n: primed.n, src: primed.src, stale: primed.stale }));
const agedHexes = primed.body.ac.map((row) => row[0]);

adsbStatus = 429;
const fellBack = await hit(fra);
check(
  '429 falls back to opensky',
  fellBack.src === 'opensky' && fellBack.header === 'opensky' && fellBack.n === 14 && fellBack.stale === false && calls.opensky === 1,
  JSON.stringify({ n: fellBack.n, src: fellBack.src, header: fellBack.header, stale: fellBack.stale, opensky: calls.opensky }),
);

now += 20_000;
openskyStatus = 429;
const held = await hit(fra);
check(
  'later 429 keeps the snapshot',
  held.n === 14 && held.stale === true && held.src === 'opensky',
  JSON.stringify({ n: held.n, src: held.src, stale: held.stale, header: held.header }),
);

view.setViewBox({ west: 8.3, south: 49.9, east: 8.9, north: 50.2, zoom: 11 });

const rowA = ['abc123', 'TST', 50.04, 8.56, 32000, 360, 90, 'B738', 'D-TEST'];
const rowB = ['def456', 'TSB', 50.04, 8.56, 28000, 360, 90, 'A320', 'D-TSB'];
const t0 = 1_000;
const coast60 = project(50.04, 8.56, 360, 90, 60);

function runClient(name, client) {
  if (typeof client.opacityAt !== 'function') {
    check(`${name} opacity`, false, 'opacityAt missing');
    return;
  }
  const fresh = client.applyFeedBody({ src: 'adsb.lol', stale: false, ac: [rowA, rowB] }, true, t0);
  check(`${name} fresh ingest`, fresh.fresh === true && client.craftList().length === 2, JSON.stringify({ fresh: fresh.fresh, n: client.craftList().length }));

  client.applyFeedBody({ src: 'adsb.lol', stale: true, ac: [rowA, rowB] }, true, t0 + 10_000);
  const at30 = client.positionAt(client.craftByHex('abc123'), t0 + 30_000);
  const at60 = client.positionAt(client.craftByHex('abc123'), t0 + 60_000);
  const at65 = client.positionAt(client.craftByHex('abc123'), t0 + 65_000);
  const start = client.positionAt(client.craftByHex('abc123'), t0);
  check(
    `${name} coasts for 60s from its own fix`,
    at30.lon > start.lon + 0.05 && at60.lon > at30.lon && Math.abs(at60.lon - coast60.lon) < 1e-4 && Math.abs(at60.lat - 50.04) < 1e-6,
    `start=${start.lon} t30=${at30.lon} t60=${at60.lon} expected=${coast60.lon}`,
  );
  check(
    `${name} holds the 60s position while fading`,
    Math.abs(at65.lon - at60.lon) < 1e-6 && Math.abs(at65.lat - at60.lat) < 1e-6,
    `t60=${at60.lon} t65=${at65.lon}`,
  );
  const op60 = client.opacityAt(client.craftByHex('abc123'), t0 + 60_000);
  const op65 = client.opacityAt(client.craftByHex('abc123'), t0 + 65_000);
  const op70 = client.opacityAt(client.craftByHex('abc123'), t0 + 70_000);
  check(
    `${name} fades after 60s`,
    op60 === 1 && Math.abs(op65 - 0.5) < 1e-6 && op70 === 0,
    `t60=${op60} t65=${op65} t70=${op70}`,
  );

  const before = client.positionAt(client.craftByHex('abc123'), t0 + 10_000);
  const moved = client.applyFeedBody(
    { src: 'adsb.lol', stale: false, ac: [['abc123', 'TST', 50.04, 9, 32000, 360, 90, 'B738', 'D-TEST']] },
    true,
    t0 + 10_000,
  );
  const atOnce = client.positionAt(client.craftByHex('abc123'), t0 + 10_000);
  const mid = client.positionAt(client.craftByHex('abc123'), t0 + 10_500);
  const done = client.positionAt(client.craftByHex('abc123'), t0 + 11_000);
  const goal = project(50.04, 9, 360, 90, 1);
  const other = client.craftByHex('def456');
  const otherOp = other ? client.opacityAt(other, t0 + 70_000) : -1;
  const movedOp = client.opacityAt(client.craftByHex('abc123'), t0 + 70_000);
  check(
    `${name} eases onto a fresh fix`,
    moved.fresh === true && Math.abs(atOnce.lon - before.lon) < 1e-6 && mid.lon > before.lon + 0.05 && mid.lon < 9 && Math.abs(done.lon - goal.lon) < 1e-4,
    `before=${before.lon} atOnce=${atOnce.lon} mid=${mid.lon} done=${done.lon} goal=${goal.lon}`,
  );
  check(
    `${name} other plane keeps its own clock`,
    otherOp === 0 && movedOp === 1,
    `other=${otherOp} moved=${movedOp}`,
  );
}

runClient('web', web);
runClient('mobile', mobile);

function drawnAt(at) {
  const crafts = mobile.craftList();
  const positions = new Float64Array(crafts.length * 2);
  for (let i = 0; i < crafts.length; i++) {
    const p = mobile.positionAt(crafts[i], at);
    positions[i * 2] = p.lon;
    positions[i * 2 + 1] = p.lat;
  }
  return planes.buildPlaneData(crafts, positions, null, at);
}

const fadeNow = drawnAt(t0 + 65_000);
const fadeLater = drawnAt(t0 + 70_000);
let fadeOp = null;
try {
  fadeOp = JSON.parse(fadeNow.json).features[0]?.properties?.op;
} catch {
  fadeOp = null;
}
check(
  'drawn count fades after 60s',
  fadeNow.count === 1 && Math.abs(fadeOp - 0.5) < 1e-6 && fadeLater.count === 0,
  `t65=${fadeNow.count} op=${fadeOp} t70=${fadeLater.count}`,
);

now += 150_000;
adsbStatus = 200;
openskyStatus = 429;
const openskyBefore = calls.opensky;
const emptyArea = await hit('/api/planes?lamin=-1&lamax=1&lomin=-1&lomax=1');
const emptyHexes = new Set(emptyArea.body.ac.map((row) => row[0]));
const emptyKept = agedHexes.every((hex) => emptyHexes.has(hex));
check(
  'healthy empty area keeps tiles older than 120s',
  emptyArea.src === 'adsb.lol' && emptyArea.stale === false && emptyKept && emptyArea.n >= agedHexes.length && calls.opensky === openskyBefore,
  JSON.stringify({ src: emptyArea.src, stale: emptyArea.stale, n: emptyArea.n, kept: emptyKept, openskyDelta: calls.opensky - openskyBefore }),
);

const swept = await hit('/api/planes');
const sweptHexes = new Set(swept.body.ac.map((row) => row[0]));
const sweptKept = agedHexes.every((hex) => sweptHexes.has(hex));
check(
  'healthy sweep keeps aged areas',
  swept.src === 'adsb.lol' && swept.stale === false && sweptKept,
  JSON.stringify({ src: swept.src, stale: swept.stale, n: swept.n, kept: sweptKept }),
);

console.log(`aged areas ${agedHexes.length} emptyArea=${emptyArea.n} swept=${swept.n} source=${emptyArea.src} adsbCalls=${calls.adsb} openskyCalls=${calls.opensky}`);
Date.now = realNow;
if (failures.length) {
  console.error(`${failures.length} failed`);
  process.exit(1);
}
console.log('stale feed holds');
