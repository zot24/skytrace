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
let hexSeq = 0;

function nextHex() {
  hexSeq += 1;
  return hexSeq.toString(16).padStart(6, '0');
}

function reject(url) {
  throw new Error(`refusing live fetch ${url}`);
}

globalThis.fetch = async (input) => {
  const url = String(input);
  if (url.includes('api.adsb.lol')) {
    calls.adsb += 1;
    if (adsbStatus !== 200) return new Response('slow down', { status: adsbStatus });
    const ac = [];
    for (let i = 0; i < 4; i++) {
      ac.push({
        hex: nextHex(),
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
    return Response.json({ ac });
  }
  if (url.includes('opensky-network.org')) {
    calls.opensky += 1;
    if (openskyStatus !== 200) return new Response('slow down', { status: openskyStatus });
    const states = [];
    for (let i = 0; i < 2; i++) {
      states.push([
        nextHex(),
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

const root = '/data/.herdr/worktrees/skytrace/fix-429-stale-feed';
const { default: worker } = await import(root + '/worker/index.ts');
const web = await import(root + '/src/traffic.ts');
const mobile = await import(root + '/apps/mobile/src/traffic.ts');
const planes = await import(root + '/apps/mobile/src/planes.ts');
const view = await import(root + '/apps/mobile/src/view.ts');

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

const fra = '/api/planes?lamin=49.9&lamax=50.2&lomin=8.3&lomax=8.9';

const primed = await hit('/api/planes');
check('prime count', primed.n === 12 && primed.src === 'adsb.lol' && primed.stale === false, JSON.stringify({ n: primed.n, src: primed.src, stale: primed.stale }));

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
const t0 = 1_000;
for (const client of [web, mobile]) {
  const outcome = client.applyFeedBody(held.body, held.status === 200, t0);
  check(client === web ? 'web ingest holds' : 'mobile ingest holds', outcome.fresh === false && client.craftList().length === 14, JSON.stringify({ fresh: outcome.fresh, n: client.craftList().length }));
}

const drawnAt = (at) => {
  const crafts = mobile.craftList();
  const positions = new Float64Array(crafts.length * 2);
  for (let i = 0; i < crafts.length; i++) {
    const p = mobile.positionAt(crafts[i], at);
    positions[i * 2] = p.lon;
    positions[i * 2 + 1] = p.lat;
  }
  return planes.buildPlaneData(crafts, positions, null).count;
};

const drawnNow = drawnAt(t0);
const drawnLater = drawnAt(t0 + 180_000);
check('drawn count holds for 3 min', drawnNow === 14 && drawnLater === 14, `t0=${drawnNow} t+180s=${drawnLater}`);

let webHeld = 0;
for (const craft of web.craftList()) {
  const p = web.positionAt(craft, t0 + 180_000);
  if (p.lat === 50.04 && p.lon === 8.56) webHeld += 1;
}
check('web positions stay on the fix', webHeld === 14, `held=${webHeld} of ${web.craftList().length}`);

console.log(`plane count t0=${drawnNow} t+180s=${drawnLater} snapshot=${held.n} source=${held.src} adsbCalls=${calls.adsb} openskyCalls=${calls.opensky}`);
Date.now = realNow;
if (failures.length) {
  console.error(`${failures.length} failed`);
  process.exit(1);
}
console.log('stale feed holds');
