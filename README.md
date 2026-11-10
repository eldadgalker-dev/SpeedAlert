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
- Usage log on the phone (filesDir/logs/usage-log.csv), kept across starts: every 15 minutes while running,
  and at start and stop, the app version, battery, CPU share and estimated drain. The "יומן שימוש" button
  shows totals overall and per version and hands the file to the phone's share sheet; the app sends nothing.
- Speed-sign photos: the "צילום שלט מהירות" button opens the app's own camera screen (SignCameraActivity,
  CameraX, CAMERA permission): pinch zoom, touch to focus and read around that point (yellow circle), a double tap on the circle shoots, live reading; the shutter
  takes a burst of 12 frames at 30 fps (short exposure), keeps the majority number and the sharpest frame
  (variance of the Laplacian). The location is followed at full accuracy meanwhile.
  The number is read on the phone (SignReader.kt: red ring by colour, digits against a 5x7 font, only legal
  limits; no ML Kit, no network); the user confirms or corrects it. Each sign goes to filesDir/signs/signs-log.csv
  with time, version, position, accuracy, bearing, the number read, the number confirmed, the road-table limit
  there and whether they differ; the photo (upright, up to 1600 px) is kept next to it. "יומן שלטי מהירות"
  shows the log and hands the CSV and the latest 50 photos to the share sheet, to check corrections to the data.
- Data table on the main screen: per data file "מעודכן" (green) when the last check against the server succeeded
  within 24 h (every changed file is downloaded by it, so all files then equal the server), else "לא נבדק" (orange);
  below it the date of the data itself ("נתונים מ-"); then the time of the last successful check.
- Buttons: mode (beeps and sentences / beeps only, saved), reset (service restarted, speech engine reconnected),
  full shut-down (alerts stopped and the app closed).
- Every alert opens with three loud, harsh beeps on the alarm stream; a continuous alert then repeats
  without a break, with one beep before every further sentence, the closing "מהירות תקינה" included, until
  the event ends (slowing down, camera passed). A dropped road match does not stop a speeding alert under way,
  and the excess is counted from the speed as spoken ("נוכחית 90. מותרת 90." is "מהירות תקינה").
- 10 m before the camera: a long two-tone sound, different from the beeps and louder to the ear; it cuts off
  any sentence under way. It is timed from the speed so that it plays 10 m before the camera, not at the next GPS fix.
- A camera alert silences a speeding alert at once, until the camera is passed.
- A fine without points is said without "ללא נקודות".
- An announced camera that is left behind without being reached (the road turned off) closes its event with
  "מצלמה לא בהמשך הדרך" (one beep).
- Speeding on a new road goes on with the new road's limit until the speed is at or under it.
- The speech engine is reconnected when driving starts after 30 minutes without speech, and once when a sentence
  fails, so that a first alert after days unused is not beeps without words (field report 2026-11-08).
- The help page shows the last change and the last check of every data set, read from GitHub when it opens.
- A camera counts only when it is ahead (within 35° of the heading) and not facing across the road.
- Speed camera: from 500 m before it the alert repeats without a break, with the current
  distance and speed, until the camera is passed (also while stopped near it):
  "מצלמת מהירות בעוד 480 מטר. מהירות נוכחית 120. מותרת 90. הוֹרֵד 30 קילומטר לשעה. קנס 750 שקלים ועוד 8 נקודות."
  The distance said is the one left when the number is heard (speed, fix age, beeps and speech time);
  near the camera the sentence shortens: first the fine and points drop, then the current speed.
- Red-light camera: from 300 m before it, repeated until it is passed or the car stops (below 5 km/h):
  "מצלמת רמזור בעוד 280 מטר."
- Within the limit every message ends with "מהירות תקינה."; after speeding without a camera,
  "מהירות תקינה. מותרת 50." is said once on getting back within the limit.
- Bus-lane enforcement (Ministry of Transport sections on roads 4, 5, 443, 461, 471): once when
  entering a section: "אכיפת נתיב תחבורה ציבורית בכביש 443. אסור לנסוע בנתיב התחבורה הציבורית."
- Urban bus lanes with enforcement cameras (data.gov.il): once at the start of the lane, only along its
  direction (start to end) and only in its hours (Sun-Thu, Friday, Saturday windows; holidays not marked);
  consecutive segments of one street are announced once: "מצלמת אכיפה בנתיב התחבורה הציבורית בהרצל. אסור לנסוע בנתיב."
- Parking-enforcement cameras (Ness Ziona, 9 fixed posts, data/parking.json, manual): once the car has
  stood (under 5 km/h) for 8 s within 40 m of a post: "מצלמת אכיפת חנייה ברחוב ויצמן 16. אסור לעצור ולחנות כאן, גם כשהנהג ברכב."
  Driving past is silent; armed again 200 m away. Positions from the city's addresses and junctions via OSM.
- Speeding without a camera: 30 km/h or more above the limit of the road under the car
  (3 fixes in a row), repeated until the speed is back at or under the limit: "חריגת מהירות. מהירות נוכחית 82. מותרת 50. ..."
- Audio: alarm stream (plays in silent and vibrate mode), unmuted and set to maximum together
  with the media stream for as long as an alert is due, then restored; other audio is ducked.
  "Do not disturb" in total-silence mode blocks even alarms; the app shows a warning then.
- Not covered: mobile police cameras (no public source), parking cameras outside Ness Ziona (Tel Aviv
  publishes street addresses only, no positions yet), Ness Ziona's mobile parking cameras.

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
| `buslanes.json` | `sections`: interurban bus-lane enforcement (both ends), manual; `lanes`: urban lanes with cameras, one way, with hours, weekly (Tuesday) by `tools/fetch_buslanes.py` | Ministry of Transport, gov.il/he/pages/citizen_cameras; data.gov.il dataset nataz_kayam (CC-BY) |
| `sections.json` | Average-speed sections, one entry per direction: name, limit, from, to, optional length_m | None published yet (empty list, filled by hand when a source appears) |
| `penalties.json` | Fine and points by km/h over the limit, urban / interurban | צו התעבורה (עבירות קנס) items 75-78א and the points table of תקנות התעבורה, read from Hebrew Wikisource (Open Law Book); `tools/fetch_penalties.py`, weekly `.github/workflows/penalties.yml` (commits only a changed table; a parse doubt fails the run and keeps the file) |

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

- OSM coverage of Israeli cameras is partial (219 cameras on 2026-11-09; 55 without a known limit).
  The official GovMap layer is not downloadable as a file and is not included.
- Point-to-point (average speed) sections: `AverageSpeedWatcher` announces the start and the end and
  alerts only while the section average is above the limit, with the speed that brings it back.
  `data/sections.json` is empty: OSM has none in Israel and no public list exists (2026-10-29).

## Copyright and licence

- Application code: Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/ — BSD-3-Clause (see `LICENSE.txt`).
- Map data: © OpenStreetMap contributors, ODbL. Bus-lane sections: Israel Ministry of Transport (gov.il).
