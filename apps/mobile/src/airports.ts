import raw from '../assets/airports.json';

export type Airport = {
  icao: string;
  iata: string;
  name: string;
  lat: number;
  lon: number;
  kind: 'L' | 'M';
  city: string;
};

type AirportRow = [string, string, string, number, number, 'L' | 'M', string];

const rows = raw as AirportRow[];

export const airports: Airport[] = rows.map((row) => ({
  icao: row[0],
  iata: row[1],
  name: row[2],
  lat: row[3],
  lon: row[4],
  kind: row[5],
  city: row[6],
}));

export const airportData = JSON.stringify({
  type: 'FeatureCollection',
  features: airports.map((airport) => ({
    type: 'Feature',
    properties: { k: airport.kind, iata: airport.iata, icao: airport.icao },
    geometry: { type: 'Point', coordinates: [airport.lon, airport.lat] },
  })),
});

export function searchAirports(query: string): Airport[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  const scored: { airport: Airport; score: number }[] = [];
  for (const airport of airports) {
    const iata = airport.iata.toLowerCase();
    const icao = airport.icao.toLowerCase();
    const name = airport.name.toLowerCase();
    const city = airport.city.toLowerCase();
    let score = 0;
    if (iata && iata === needle) score = 100;
    else if (icao === needle) score = 92;
    else if (iata.startsWith(needle)) score = 80;
    else if (icao.startsWith(needle)) score = 70;
    else if (name.startsWith(needle)) score = 60;
    else if (city.startsWith(needle)) score = 50;
    else if (name.includes(needle)) score = 40;
    else if (city.includes(needle)) score = 30;
    if (score) scored.push({ airport, score: score + (airport.kind === 'L' ? 2 : 0) });
  }
  scored.sort((a, b) => b.score - a.score || a.airport.name.localeCompare(b.airport.name));
  return scored.slice(0, 8).map((item) => item.airport);
}
