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

## Data (`data/`, packed into the APK as assets)

| File | Content | Source |
|---|---|---|
| `cameras.json` | Camera positions, speed limit, direction, urban flag | OpenStreetMap (ODbL) via Overpass; `tools/fetch_cameras.py` |
| `penalties.json` | Fine and points by km/h over the limit, urban / interurban | Traffic-lawyer summaries of the 2026 schedule; **not verified against the official order** |

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
- Penalty figures must be checked against צו התעבורה (עבירות קנס).
