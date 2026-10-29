# SpeedAlert

Android app (Kotlin) that warns by voice, in Hebrew and at maximum volume, about speed and
red-light cameras, bus-lane enforcement and speeding in Israel.

## What it does

- Foreground service follows GPS: once per second within 1.5 km of a camera or while alerting, every 2 s otherwise. After 3 minutes below
  15 km/h with no alert, location stops and the significant-motion sensor (hardware trigger) wakes
  full GPS when the phone moves; a wake without driving goes back to saving after 90 s. Without that
  sensor: a balanced (network) fix every 15 s, back to full GPS after about 80 m.
- Activity Recognition (Play services, IN_VEHICLE transitions): entering a vehicle wakes full GPS at
  once; leaving it while stopped goes to saving without the 3-minute wait.
- Main screen: battery (estimate from the app's CPU and GPS time) and CPU of this app only, over the
  last minutes (up to 10); dates as dd/mm/yyyy.
- Every announcement opens with three loud, harsh beeps on the alarm stream (every sentence, repeats of a continuous alert included).
- A camera counts only when it is ahead (within 35° of the heading) and not facing across the road.
- Speed camera: from 500 m before it the alert repeats without a break, with the current
  distance and speed, until the camera is passed (also while stopped near it):
  "מצלמת מהירות בעוד 480 מטר. מהירות נוכחית 120. מותרת 90. הוֹרֵד 30 קילומטר לשעה. קנס 750 שקלים ועוד 8 נקודות."
- Red-light camera: from 300 m before it, repeated until it is passed or the car stops (below 5 km/h):
  "מצלמת רמזור בעוד 280 מטר."
- Within the limit every message ends with "מהירות תקינה."; after speeding without a camera,
  "מהירות תקינה. מותרת 50." is said once on getting back within the limit.
- Bus-lane enforcement (Ministry of Transport sections on roads 4, 5, 443, 461, 471): once when
  entering a section: "אכיפת נתיב תחבורה ציבורית בכביש 443. אסור לנסוע בנתיב התחבורה הציבורית."
- Speeding without a camera: 30 km/h or more above the limit of the road under the car
  (3 fixes in a row), repeated while it lasts: "חריגת מהירות. מהירות נוכחית 82. מותרת 50. ..."
- Audio: alarm stream (plays in silent and vibrate mode), unmuted and set to maximum together
  with the media stream for as long as an alert is due, then restored; other audio is ducked.
  "Do not disturb" in total-silence mode blocks even alarms; the app shows a warning then.
- Not covered: mobile police cameras (no public source), parking cameras (Tel Aviv publishes
  street addresses only, no positions yet).

## Updates

- **Camera data:** `.github/workflows/data.yml` runs `tools/fetch_cameras.py` every hour and
  commits `data/cameras.json` only when the camera list changed (a result with < 80 % of the
  current cameras is refused). The app downloads `data/` from `main` at launch and every 30
  minutes while alerts run; a download that does not parse is discarded and the last good copy
  (or the one packed in the APK) stays in use.
- **App:** `.github/workflows/release.yml` builds a signed APK on every app change to `main` and
  publishes Release `v<speed.versionBase>.<run>` with `SpeedAlert.apk` and `version.json`.
  The app checks `version.json` at every launch and every data check (30 min), shows a newer
  version on the main screen and notifies once. The "בדוק עדכון" button downloads it, checks
  SHA-256 and opens the installer.
  Needs repository secrets `SPEED_KEYSTORE_B64`, `SPEED_KEYSTORE_PASSWORD`, `SPEED_KEY_ALIAS`,
  `SPEED_KEY_PASSWORD` (keystore kept outside the repository).
- **Install page:** `docs/index.html` (GitHub Pages from `/docs`).

## Data (`data/`, packed into the APK as assets)

| File | Content | Source |
|---|---|---|
| `cameras.json` | Speed and red-light cameras: position, kind, speed limit, direction, urban flag | OpenStreetMap (ODbL) via Overpass; `tools/fetch_cameras.py` |
| `roads/` | `roads.txt` split into 0.1° tiles plus `index.txt` (name, sha256, bytes) by `tools/tile_roads.py`; the app keeps only the tiles around the car (none while idle), downloads the index on each data check and a changed tile only when the car comes near it | Built from `roads.txt` by the weekly workflow |
| `roads.txt`, `roads.version` | Speed limit per road segment (not packed in the APK) (OSM `maxspeed`; untagged residential = 50; untagged main roads = 0, unknown, which blocks the match; the highest limit among parallel roads within 20 m wins), sha256 | OpenStreetMap via Overpass; `tools/fetch_roads.py`, weekly `.github/workflows/roads.yml` |
| `buslanes.json` | Bus-lane enforcement sections (both ends) | Ministry of Transport, gov.il/he/pages/citizen_cameras |
| `sections.json` | Average-speed sections, one entry per direction: name, limit, from, to, optional length_m | None published yet (empty list, filled by hand when a source appears) |
| `penalties.json` | Fine and points by km/h over the limit, urban / interurban | צו התעבורה (עבירות קנס) items 75-78א and the points table of תקנות התעבורה; verified 2026-10-28 (sources in the file) |

Speed limit per camera: the camera's own `maxspeed` tag, else the nearest road
(≤ 40 m) with `maxspeed`. Cameras with no known limit announce distance only.
Urban table is used when the limit is ≤ 60 km/h or the road is a city street type.

Refresh the cameras:

```
python tools/fetch_cameras.py
```

## Build

Requires JDK 17–21 and the Android SDK (platform 35). Build output goes to
`~/.speedalert-build` (the project folder is on Google Drive).

```
gradlew.bat testDebugUnitTest assembleDebug
```

APK: `~/.speedalert-build/app/outputs/apk/debug/app-debug.apk`.

## On the phone

1. Install the APK (allow installing from unknown sources).
2. Make sure a Hebrew voice exists: Settings → Text-to-speech → Speech Services by Google → Hebrew.
3. Open SpeedAlert → "בדיקת קול", then "הפעל התראות". Allow precise location and notifications.
4. Exclude the app from battery optimisation so the service is not killed on long drives.

## Known limits

- OSM coverage of Israeli cameras is partial (182 cameras on 2026-10-28; 46 without a known limit).
  The official GovMap layer is not downloadable as a file and is not included.
- Point-to-point (average speed) sections: `AverageSpeedWatcher` announces the start and the end and
  alerts only while the section average is above the limit, with the speed that brings it back.
  `data/sections.json` is empty: OSM has none in Israel and no public list exists (2026-10-29).

## Copyright and licence

- Application code: Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/ — BSD-3-Clause (see `LICENSE.txt`).
- Map data: © OpenStreetMap contributors, ODbL. Bus-lane sections: Israel Ministry of Transport (gov.il).
