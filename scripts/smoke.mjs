import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';
import { chromium } from 'playwright';

const chromiumRoot = '/data/tools/chromium-root';
const chromiumLibs = [
  `${chromiumRoot}/usr/lib/x86_64-linux-gnu`,
  `${chromiumRoot}/lib/x86_64-linux-gnu`,
].filter((dir) => existsSync(dir));
const hasGlib = (spawnSync('ldconfig', ['-p'], { encoding: 'utf8' }).stdout || '').includes('libglib-2.0.so.0');
if (chromiumLibs.length && !hasGlib) {
  process.env.LD_LIBRARY_PATH = [...chromiumLibs, process.env.LD_LIBRARY_PATH].filter(Boolean).join(':');
}

const bundledFonts = `${chromiumRoot}/usr/share/fonts`;
if (!existsSync('/etc/fonts/fonts.conf') && existsSync(bundledFonts)) {
  mkdirSync('/tmp/skytrace-fontconfig', { recursive: true });
  const fontsConf = '/tmp/skytrace-fonts.conf';
  writeFileSync(
    fontsConf,
    `<?xml version="1.0"?>\n<fontconfig><dir>${bundledFonts}</dir><cachedir>/tmp/skytrace-fontconfig</cachedir></fontconfig>\n`,
  );
  process.env.FONTCONFIG_FILE = fontsConf;
}

const port = 4173;
const base = `http://127.0.0.1:${port}`;
const server = spawn(
  process.execPath,
  ['node_modules/vite/bin/vite.js', 'preview', '--host', '127.0.0.1', '--port', String(port), '--strictPort'],
  { stdio: 'inherit', detached: true },
);

let done = false;
function finish(code) {
  if (done) return;
  done = true;
  try {
    process.kill(-server.pid, 'SIGTERM');
  } catch {
    server.kill('SIGTERM');
  }
  setTimeout(() => process.exit(code), 300);
}

server.on('exit', (code) => {
  if (!done) {
    console.error(`preview server exited early (${code})`);
    process.exit(code || 1);
  }
});

try {
  await waitForServer(base);
  const browser = await chromium.launch({
    headless: true,
    args: [
      '--use-gl=angle',
      '--use-angle=swiftshader',
      '--enable-unsafe-swiftshader',
      '--ignore-gpu-blocklist',
      '--enable-webgl',
      '--disable-dev-shm-usage',
    ],
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  await page.goto(`${base}/?synthetic=10000`, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForFunction(() => window.__skytrace && window.__skytrace.ready === true, null, {
    timeout: 45_000,
  });

  const loaded = await page.evaluate(async () => {
    const map = window.__map;
    const layer = map.getLayer('planes');
    const src = map.getSource('planes');
    const data = src && src.getData ? await src.getData() : null;
    const features = data && data.type === 'FeatureCollection' ? data.features.length : 0;
    const canvas = map.getCanvas();
    return {
      layer: !!(layer && layer.type === 'custom'),
      features,
      canvas: canvas.width > 0 && canvas.height > 0,
    };
  });
  if (!loaded.canvas) throw new Error('map canvas missing');
  if (!loaded.layer) throw new Error('planes layer missing');
  if (loaded.features < 1000) throw new Error(`planes layer has ${loaded.features} features`);
  await page.waitForFunction(() => window.__skytrace.fps > 0, null, { timeout: 15_000 });

  const point = await page.evaluate(() => window.__samplePlane());
  if (!point) throw new Error('no visible plane to click');
  const worldShot = await page.screenshot({ type: 'png' });
  writeFileSync('/tmp/skytrace-world.png', worldShot);
  const icon = iconAt(worldShot, point.x, point.y);
  if (!icon.ok) throw new Error(`plane icon not drawn at ${Math.round(point.x)},${Math.round(point.y)} (peak ${icon.peak} bg ${icon.bg})`);
  await page.mouse.click(point.x, point.y);
  await page.waitForSelector('#panel:not([hidden])', { timeout: 5000 });
  const panelText = (await page.textContent('#panel')) || '';
  if (!panelText.includes('ft') || !panelText.toLowerCase().includes('kt')) {
    throw new Error(`panel missing flight details: ${panelText}`);
  }
  writeFileSync('/tmp/skytrace-panel.png', await page.screenshot({ type: 'png' }));

  await page.fill('#q', 'JFK');
  await page.waitForFunction(() => {
    const button = document.querySelector('#hits button');
    return !!button && (button.textContent || '').includes('JFK');
  }, null, { timeout: 15_000 });
  await page.click('#hits button');
  await page.waitForFunction(() => {
    const center = window.__map.getCenter();
    return Math.abs(center.lat - 40.6394) < 1 && Math.abs(center.lng + 73.7793) < 1;
  }, null, { timeout: 15_000 });
  writeFileSync('/tmp/skytrace-jfk.png', await page.screenshot({ type: 'png' }));

  const measured = await page.evaluate(
    () =>
      new Promise((resolve) => {
        const start = performance.now();
        let frames = 0;
        function loop(now) {
          frames += 1;
          if (now - start < 5000) requestAnimationFrame(loop);
          else {
            resolve({
              fps: frames / ((now - start) / 1000),
              skyFps: window.__skytrace.fps,
              load: window.__skytrace.firstMapLoadMs,
            });
          }
        }
        requestAnimationFrame(loop);
      }),
  );

  console.log(`first-load ms: ${Math.round(measured.load)}`);
  console.log(`fps over 5s: ${measured.fps.toFixed(1)} (skytrace ${measured.skyFps})`);
  if (errors.length) throw new Error(`page errors:\n${errors.join('\n')}`);
  await browser.close();
  finish(0);
} catch (error) {
  console.error(error);
  finish(1);
}

function iconAt(png, x, y) {
  const img = decodePng(png);
  const cx = Math.round(x);
  const cy = Math.round(y);
  let peak = 0;
  for (let dy = -8; dy <= 8; dy++) {
    for (let dx = -8; dx <= 8; dx++) {
      if (dx * dx + dy * dy > 64) continue;
      peak = Math.max(peak, lum(img, cx + dx, cy + dy));
    }
  }
  const ring = [];
  for (let i = 0; i < 12; i++) {
    const angle = (i / 12) * Math.PI * 2;
    ring.push(lum(img, Math.round(cx + Math.cos(angle) * 36), Math.round(cy + Math.sin(angle) * 36)));
  }
  ring.sort((a, b) => a - b);
  const bg = ring[Math.floor(ring.length / 2)];
  return { ok: peak > 140 && peak > bg + 40, peak: Math.round(peak), bg: Math.round(bg) };
}

function lum(img, x, y) {
  if (x < 0 || y < 0 || x >= img.width || y >= img.height) return 0;
  const i = (y * img.width + x) * 4;
  return img.rgba[i] * 0.2126 + img.rgba[i + 1] * 0.7152 + img.rgba[i + 2] * 0.0722;
}

function decodePng(buf) {
  let p = 8;
  let width = 0;
  let height = 0;
  let colorType = 0;
  const idat = [];
  while (p + 8 <= buf.length) {
    const len = buf.readUInt32BE(p);
    const type = buf.toString('ascii', p + 4, p + 8);
    const data = buf.subarray(p + 8, p + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      colorType = data[9];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    p += 12 + len;
  }
  const bpp = colorType === 6 ? 4 : colorType === 2 ? 3 : 0;
  if (!bpp) throw new Error(`unsupported png color type ${colorType}`);
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * bpp;
  const rgba = Buffer.alloc(width * height * 4);
  let s = 0;
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[s++];
    const row = Buffer.from(raw.subarray(s, s + stride));
    s += stride;
    for (let i = 0; i < stride; i++) {
      const left = i >= bpp ? row[i - bpp] : 0;
      const up = prev[i];
      const ul = i >= bpp ? prev[i - bpp] : 0;
      if (filter === 1) row[i] = (row[i] + left) & 255;
      else if (filter === 2) row[i] = (row[i] + up) & 255;
      else if (filter === 3) row[i] = (row[i] + ((left + up) >> 1)) & 255;
      else if (filter === 4) {
        const pa = Math.abs(up - ul);
        const pb = Math.abs(left - ul);
        const pc = Math.abs(left + up - 2 * ul);
        const pr = pa <= pb && pa <= pc ? left : pb <= pc ? up : ul;
        row[i] = (row[i] + pr) & 255;
      }
    }
    prev = row;
    for (let x = 0; x < width; x++) {
      const di = (y * width + x) * 4;
      const si = x * bpp;
      rgba[di] = row[si];
      rgba[di + 1] = row[si + 1];
      rgba[di + 2] = row[si + 2];
      rgba[di + 3] = bpp === 4 ? row[si + 3] : 255;
    }
  }
  return { width, height, rgba };
}

async function waitForServer(url) {
  for (let i = 0; i < 80; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch {
      // preview is still booting
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('preview server did not start');
}
