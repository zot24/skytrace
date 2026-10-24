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

export async function loadAirports(): Promise<Airport[]> {
  const res = await fetch('/airports.json');
  if (!res.ok) throw new Error(`airports ${res.status}`);
  const rows = (await res.json()) as AirportRow[];
  return rows.map((row) => ({
    icao: row[0],
    iata: row[1],
    name: row[2],
    lat: row[3],
    lon: row[4],
    kind: row[5],
    city: row[6],
  }));
}
