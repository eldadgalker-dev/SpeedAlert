# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
"""Build data/roads.txt: speed limit per road segment in Israel, from OpenStreetMap.

Used by the app to warn when driving 30 km/h or more above the limit where there is no camera.
A road is kept when it has a numeric maxspeed tag; an untagged residential street gets the
urban default of 50 km/h (Traffic Regulations, reg. 54). Other untagged main roads are kept
with limit 0 (unknown): the app never alerts on them, and they stop a nearby street or ramp from
being matched instead (field report 2026-10-29: "50" in the middle of the Ayalon). Minor untagged
roads are left out.

Output, one way per line: `limit urban lat lon dlat dlon ...` (coordinates in 1e-5 degrees,
deltas from the previous point), geometry simplified to 5 m.

Also writes data/roads.version (sha256 of roads.txt); the app downloads roads.txt only when
that changes.

Usage: python tools/fetch_roads.py [--out data/roads.txt] [--input overpass.json]
"""
import argparse, hashlib, json, math, sys

sys.path.insert(0, __file__.rsplit("tools", 1)[0] + "tools")
from fetch_cameras import MIRRORS, parse_speed, URBAN_HW  # noqa: E402

import time, urllib.parse, urllib.request

CLASSES = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street"
MAIN = {"motorway", "trunk", "primary", "secondary", "tertiary"}
QUERY = f"""[out:json][timeout:600][bbox:29.4,34.2,33.4,35.95];
way["highway"~"^({CLASSES})(_link)?$"];
out tags geom;"""
TOLERANCE_M = 5.0


def fetch():
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    delay = 20
    for attempt in range(6):
        url = MIRRORS[attempt % len(MIRRORS)]
        try:
            req = urllib.request.Request(url, body, {"User-Agent": "SpeedAlert/0.4 (personal app)",
                                                     "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=900) as r:
                return json.load(r)
        except Exception as e:
            print(f"attempt {attempt + 1} {url}: {e}", file=sys.stderr)
            time.sleep(delay)
            delay = min(delay * 2, 180)
    sys.exit("all Overpass attempts failed")


def limit_of(tags):
    v = parse_speed(tags.get("maxspeed"))
    if v is not None:
        return v
    hw = tags.get("highway", "")
    if hw == "residential":
        return 50
    if hw.removesuffix("_link") in MAIN:
        return 0  # unknown: blocks the match, never alerts
    return None


def simplify(pts):
    """Douglas-Peucker on (x, y) metres, keeps the ends."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a][2], pts[a][3]
        bx, by = pts[b][2], pts[b][3]
        dx, dy = bx - ax, by - ay
        L = math.hypot(dx, dy) or 1e-9
        best, idx = 0.0, -1
        for i in range(a + 1, b):
            d = abs(dy * (pts[i][2] - ax) - dx * (pts[i][3] - ay)) / L
            if d > best:
                best, idx = d, i
        if best > TOLERANCE_M:
            keep[idx] = True
            stack += [(a, idx), (idx, b)]
    return [p for p, k in zip(pts, keep) if k]


def build(data):
    lines, kept = [], 0
    for w in data["elements"]:
        if w.get("type") != "way" or not w.get("geometry") or len(w["geometry"]) < 2:
            continue
        t = w.get("tags", {})
        limit = limit_of(t)
        if limit is None or not (limit == 0 or 5 <= limit <= 130):
            continue
        hw = t.get("highway", "").removesuffix("_link")
        urban = limit <= 60 or hw in URBAN_HW
        k = math.cos(math.radians(w["geometry"][0]["lat"])) * 111320.0
        pts = [(round(g["lat"] * 1e5), round(g["lon"] * 1e5), g["lon"] * k, g["lat"] * 110540.0)
               for g in w["geometry"]]
        pts = simplify(pts)
        nums = [limit, int(urban), pts[0][0], pts[0][1]]
        for p, q in zip(pts, pts[1:]):
            nums += [q[0] - p[0], q[1] - p[1]]
        lines.append(" ".join(map(str, nums)))
        kept += 1
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default="data/roads.txt")
    ap.add_argument("--input", help="saved Overpass JSON instead of downloading")
    a = ap.parse_args()
    if a.input:
        with open(a.input, encoding="utf-8") as f:
            data = json.load(f)
    else:
        data = fetch()
    lines = build(data)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        # No date in the file: it changes only when the roads change, so the app re-downloads only then.
        f.write(f"# OpenStreetMap contributors (ODbL), via Overpass API; ways {len(lines)}\n")
        f.write("\n".join(lines) + "\n")
    with open(a.out, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    with open(a.out.rsplit(".", 1)[0] + ".version", "w", encoding="utf-8", newline="\n") as f:
        f.write(digest + "\n")
    print(f"{len(lines)} roads -> {a.out} (sha256 {digest[:12]})")


if __name__ == "__main__":
    main()
