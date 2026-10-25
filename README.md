# skytrace

Every live plane in the world, moving smoothly on a dark vector map.

## Run

```bash
npm i
npm run dev
```

That starts Vite at http://127.0.0.1:5173 and the API worker with `wrangler dev` on port 8787. Vite proxies `/api` to the worker. This repo does not deploy anything.

`http://127.0.0.1:5173/?synthetic=10000` draws 10,000 simulated aircraft in the browser for performance checks. No feed is called.

```bash
npm run check
```

Typechecks, builds, and runs a headless Playwright smoke test (synthetic traffic, a plane click, and a JFK search). It prints first-load milliseconds and fps measured over 5 seconds.

`npm run airports` rebuilds `public/airports.json` from the OurAirports CSV.

## Data and licences

The code is MIT. The data is not, and it is not all licensed the same way.

- **adsb.lol** is the primary feed. The API and the data it publishes are ODbL. If you build a derived database from it, that database stays share-alike. There is no world snapshot (`/v2/all` is gone). `/api/planes` walks `https://api.adsb.lol/v2/point/{lat}/{lon}/250` — 250 nautical miles is the maximum radius — a few tiles per refresh, and keeps a short-lived tile cache. Upstream answers 429 if you request faster than that. Ask them before production use; an API key may be required later.
- **OpenSky Network** is the fallback (`/api/states/all`) when adsb.lol returns nothing. OpenSky data is non-commercial only, and anonymous calls are rate limited. It has callsign, altitude, speed, and heading, not aircraft type or registration.
- **OurAirports** supplies the large and medium airports. The dataset is public domain, with no warranty.
- The basemap is **OpenFreeMap**’s dark style, © **OpenStreetMap** contributors. Those credits, plus the feed credits, stay visible in the map.

`/api/planes` is cached for about 5 seconds with the Cache API. The browser dead-reckons between those updates from ground speed and heading.
