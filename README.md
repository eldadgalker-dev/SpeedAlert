# SpeedAlert

Android app (Kotlin) that warns by voice, in Hebrew and at full media volume,
when the car approaches a speed camera in Israel.

## What it does

- Foreground service follows GPS once per second: position, speed, heading.
- A camera counts only when it is within 500 m, ahead (within 35° of the heading),
  and not facing across the road.
- First alert at ≤ 500 m; a second alert at ≤ 200 m only if still above the limit.
- Speeding: "מצלמת מהירות בעוד 480 מטר. הורד 14 קילומטר לשעה. קנס 250 שקלים, ללא נקודות."
- Within the limit: "מצלמת מהירות בעוד 480 מטר. מהירות מותרת 90."
- Media volume is raised to maximum for the alert and restored afterwards; other
  audio (Waze, music) is ducked.

## Updates

- **Camera data:** `.github/workflows/data.yml` runs `tools/fetch_cameras.py` every hour and
  commits `data/cameras.json` only when the camera list changed (a result with < 80 % of the
  current cameras is refused). The app downloads `data/` from `main` at launch and every 30
  minutes while alerts run; a download that does not parse is discarded and the last good copy
  (or the one packed in the APK) stays in use.
- **App:** `.github/workflows/release.yml` builds a signed APK on every app change to `main` and
  publishes Release `v<speed.versionBase>.<run>` with `SpeedAlert.apk` and `version.json`.
  The "בדוק עדכון גרסה" button downloads it, checks SHA-256 and opens the installer.
  Needs repository secrets `SPEED_KEYSTORE_B64`, `SPEED_KEYSTORE_PASSWORD`, `SPEED_KEY_ALIAS`,
  `SPEED_KEY_PASSWORD` (keystore kept outside the repository).
- **Install page:** `docs/index.html` (GitHub Pages from `/docs`).

## Data (`data/`, packed into the APK as assets)

| File | Content | Source |
|---|---|---|
| `cameras.json` | Camera positions, speed limit, direction, urban flag | OpenStreetMap (ODbL) via Overpass; `tools/fetch_cameras.py` |
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
- Point-to-point (average speed) sections and red-light cameras are not handled separately.
