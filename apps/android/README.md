# skytrace for Android

Jetpack Compose map of the same `/api/planes` feed as the web app.

## Build

Use JDK 21, Android platform 35, and build-tools 35.0.0.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
cd apps/android
./gradlew assembleDebug testDebugUnitTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

The manifest allows cleartext HTTP. The default worker URL is `http://10.0.2.2:8787`, which is the host machine from an Android emulator. Tap the credit line to open About and change that URL. From the repo root, `npm run dev` serves the worker on port 8787.

Airport search reads `public/airports.json`. The Gradle build copies that file into the app assets.

Favourites for airports and aircraft stay on the device in DataStore. There is no account.

## Aircraft icons

The nine silhouettes are original drawings in `SilhouetteBitmap.kt`. They use the MIT licence of this repo. Shapes from tar1090 are not included. tar1090 is GPL-2.0-or-later, and that licence would cover a combined app.

A known ICAO type code picks the silhouette. A missing or unknown type uses the ADS-B emitter category. The worker appends that category as the 10th field of each aircraft row when adsb.lol sends `category`. OpenSky rows leave the field empty. A known type wins over the category.

| Input | Silhouette |
| --- | --- |
| A388, A359, A310, B744, B77W, B789 | Wide body |
| A319, A320, B738 | Narrow body |
| E75L, CRJ9, BCS1 | Regional jet |
| C56X, GLEX | Bizjet |
| AT76, DH8D | Turboprop |
| C172 | Light piston |
| R22, R44 | Helicopter, light |
| H60, emitter category A7 | Helicopter, medium |
| H47 | Helicopter, heavy |
| Emitter category A5 or A4 | Wide body |
| Emitter category A3, or no type and no category | Narrow body |
| Emitter category A2 | Regional jet |
| Emitter category A6 | Bizjet |
| Emitter category A1 | Light piston |

Category A7 is drawn as a medium helicopter. The category does not carry a weight class.

## Motion

Every frame dead-reckons from the last fresh ground speed and track. A new fix eases in over 2 seconds, so the icon does not jump. A stale payload or HTTP 429 does not refresh that fix and does not remove aircraft. Full opacity lasts 60 seconds from the last fresh fix. The icon then fades over 20 seconds and is removed. A fresh snapshot removes aircraft that are no longer in the feed.
