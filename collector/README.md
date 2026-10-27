# Trail collector

Polls adsb.lol from a Durable Object alarm, keeps each aircraft's flight path, and serves it.

```sh
npm install && npm run dev
curl 'http://127.0.0.1:8788/trails?hex=<icao>'
curl 'http://127.0.0.1:8788/trails?bbox=-18.5,27.5,-13,29.5'   # minLon,minLat,maxLon,maxLat
```

Open flights live in the Durable Object; closed ones (10 min unseen or on the ground) are gzipped to R2 as `hex/date/flightId`.
adsb.lol publishes no numeric rate limit, so it polls one 250nm tile every 15s, round-robin over `TILES`.
Data: adsb.lol, ODbL 1.0.
