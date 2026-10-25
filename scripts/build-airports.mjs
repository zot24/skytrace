import { writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';

const source = process.argv[2] || 'https://davidmegginson.github.io/ourairports-data/airports.csv';
const out = new URL('../public/airports.json', import.meta.url);

const text = source.startsWith('http')
  ? await (await fetch(source)).text()
  : await (await import('node:fs/promises')).readFile(source, 'utf8');

const rows = parseCsv(text);
const header = rows[0];
const col = Object.fromEntries(header.map((name, i) => [name, i]));
const keep = [];

for (const row of rows.slice(1)) {
  const type = row[col.type];
  if (type !== 'large_airport' && type !== 'medium_airport') continue;
  const lat = Number(row[col.latitude_deg]);
  const lon = Number(row[col.longitude_deg]);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
  const icao = (row[col.icao_code] || row[col.ident] || '').trim();
  const iata = (row[col.iata_code] || '').trim();
  const name = (row[col.name] || '').trim().slice(0, 80);
  const city = (row[col.municipality] || '').trim().slice(0, 48);
  keep.push([
    icao,
    iata,
    name,
    Math.round(lat * 1e4) / 1e4,
    Math.round(lon * 1e4) / 1e4,
    type === 'large_airport' ? 'L' : 'M',
    city,
  ]);
}

keep.sort((a, b) => a[0].localeCompare(b[0]));
await mkdir(dirname(out.pathname), { recursive: true });
await writeFile(out, JSON.stringify(keep));
const large = keep.filter((a) => a[5] === 'L').length;
console.log(`wrote ${keep.length} airports (${large} large) to ${out.pathname}`);

function parseCsv(input) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < input.length; i++) {
    const c = input[i];
    if (quoted) {
      if (c === '"') {
        if (input[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        field += c;
      }
      continue;
    }
    if (c === '"') {
      quoted = true;
    } else if (c === ',') {
      row.push(field);
      field = '';
    } else if (c === '\n') {
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else if (c !== '\r') {
      field += c;
    }
  }
  if (field.length || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}
