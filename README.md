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

## Android

`apps/mobile` is an Expo dev-client app (not Expo Go) for Android. Package id `com.zot24.skytrace`. It uses the same `/api/planes` payload, dead-reckons between updates, and draws planes with MapLibre on the OpenFreeMap dark style. The API base defaults to `http://10.0.2.2:8787`, the emulator address for the host worker. Set `EXPO_PUBLIC_SKYTRACE_API` to point at another host. Cleartext HTTP is enabled in the generated manifest so that address works.

```bash
cd apps/mobile
npm i
npx expo prebuild --platform android --no-install
cd android
./gradlew assembleDebug
```

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. `android/` is generated and gitignored. This repo does not run Gradle. Start the worker on the host first (`npm run dev` in the repo root listens on `127.0.0.1:8787`, which the emulator reaches as `10.0.2.2`). Then install the APK and open skytrace. Search an airport to fly the camera there.
