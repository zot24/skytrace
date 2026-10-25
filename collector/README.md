# Trail collector

Polls adsb.lol from a Durable Object alarm, keeps each aircraft's flight path, and serves it.

```sh
npm install && npm run dev
curl 'http://127.0.0.1:8788/trails?hex=4ca7b5'
curl 'http://127.0.0.1:8788/trails?bbox=5,48,12,52'   # minLon,minLat,maxLon,maxLat
```

Open flights live in the Durable Object; closed ones (10 min unseen or on the ground) are gzipped to R2 as `hex/date/flightId`.
adsb.lol publishes no numeric rate limit, so it polls one 250nm tile every 15s, round-robin over `TILES`.
Data: adsb.lol, ODbL 1.0.
