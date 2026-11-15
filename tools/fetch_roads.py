# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
"""Build data/roads.txt: speed limit per road segment in Israel, from OpenStreetMap.

Used by the app to warn when driving 30 km/h or more above the limit where there is no camera.
A road is kept when it has a numeric maxspeed tag; an untagged residential street gets the
urban default of 50 km/h (Traffic Regulations, reg. 54). Other untagged main roads are kept
with limit 0 (unknown): the app never alerts on them, and they stop a nearby street or ramp from
being matched instead (field report 2026-10-29: "50" in the middle of the Ayalon). Minor untagged
roads are left out, and so are untagged ramps (`_link`): an unknown ramp beside a tagged highway
hid the highway's limit (photos of 2026-11-12, Ayalon and road 1: no limit within 20 m of a ramp).

Signs photographed by the user correct the table where OpenStreetMap is wrong: each entry of
data/sign_overrides.json (`lat`, `lon` of the sign, `heading` of travel, `limit`, `max_m`) sets
`limit` from the sign onward along the road, until the tagged limit changes, the road ends or
`max_m` metres passed. An entry that matches no road is reported and skipped.

Output, one way per line: `limit urban lat lon dlat dlon ...` (coordinates in 1e-5 degrees,
deltas from the previous point), geometry simplified to 5 m.

Also writes data/roads.version (sha256 of roads.txt); the app downloads roads.txt only when
that changes.

Usage: python tools/fetch_roads.py [--out data/roads.txt] [--input overpass.json]
"""
import argparse, hashlib, json, math, os, sys

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
                data = json.load(r)
            # Overpass answers 200 with a partial result and a "remark" on timeout or memory errors.
            if "error" in data.get("remark", "").lower():
                raise RuntimeError(data["remark"][:200])
            return data
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
    if hw in MAIN:
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


OVERRIDE_NEAR_M = 30.0
OVERRIDE_ALONG_DEG = 30.0
OVERRIDE_MAX_M = 3000.0


def _xy(p, lat0):
    return p[1] * math.cos(math.radians(lat0)) * 111320.0, p[0] * 110540.0


def _dist(a, b):
    ax, ay = _xy(a, a[0]); bx, by = _xy(b, a[0])
    return math.hypot(bx - ax, by - ay)


def _cut(geom, metres):
    """Splits a polyline `metres` from its start: (head, tail); tail is None when it is shorter."""
    done = 0.0
    for i in range(len(geom) - 1):
        d = _dist(geom[i], geom[i + 1])
        if d > 0 and done + d > metres:
            t = (metres - done) / d
            p = (geom[i][0] + (geom[i + 1][0] - geom[i][0]) * t, geom[i][1] + (geom[i + 1][1] - geom[i][1]) * t)
            return geom[:i + 1] + [p], [p] + geom[i + 1:]
        done += d
    return geom, None


def apply_overrides(ways, overrides, log=lambda m: print(m, file=sys.stderr)):
    """ways: dicts with `limit`, `urban`, `link`, `geom` [(lat, lon)]. Returns the list with the overrides applied."""
    for o in overrides:
        lat, lon, heading, limit = float(o["lat"]), float(o["lon"]), float(o["heading"]), int(o["limit"])
        if not 5 <= limit <= 130:
            log(f"override skipped (limit {limit}): {o}"); continue
        best = None
        px, py = _xy((lat, lon), lat)
        for wi, w in enumerate(ways):
            if w["limit"] <= 0 or w["link"]:
                continue
            g = w["geom"]
            if min(abs(g[0][0] - lat), abs(g[-1][0] - lat)) > 0.2:
                continue
            for i in range(len(g) - 1):
                if abs(g[i][0] - lat) > 0.01 and abs(g[i + 1][0] - lat) > 0.01:
                    continue
                ax, ay = _xy(g[i], lat); bx, by = _xy(g[i + 1], lat)
                ex, ey = bx - ax, by - ay
                l2 = ex * ex + ey * ey
                if l2 < 1e-6:
                    continue
                t = max(0.0, min(1.0, ((px - ax) * ex + (py - ay) * ey) / l2))
                d = math.hypot(ax + t * ex - px, ay + t * ey - py)
                turn = abs((math.degrees(math.atan2(ex, ey)) - heading + 180) % 360 - 180)
                if d <= OVERRIDE_NEAR_M and turn <= OVERRIDE_ALONG_DEG and (best is None or d < best[0]):
                    best = (d, wi, i, t)
        if best is None:
            log(f"override matches no road: {o}"); continue
        _, wi, i, t = best
        w = ways[wi]; g = w["geom"]; base = w["limit"]
        if base == limit:
            log(f"override already in the data ({limit}): {o}"); continue
        p = (g[i][0] + (g[i + 1][0] - g[i][0]) * t, g[i][1] + (g[i + 1][1] - g[i][1]) * t)
        head = g[:i + 1] + [p]; tail = [p] + g[i + 1:]
        left = min(float(o.get("max_m", OVERRIDE_MAX_M)), OVERRIDE_MAX_M)
        urban = limit <= 60 or (w["urban"] and base > 60)
        pieces = [dict(w, geom=head)] if len(head) > 2 or _dist(head[0], head[-1]) > 1 else []
        seen = {wi}; total = 0.0; cur = w
        while True:
            part, rest = _cut(tail, left)
            length = sum(_dist(a, b) for a, b in zip(part, part[1:]))
            pieces.append(dict(cur, geom=part, limit=limit, urban=urban))
            total += length; left -= length
            if rest is not None:
                pieces.append(dict(cur, geom=rest)); break
            if left <= 1:
                break
            end = tail[-1]
            nxt = [k for k, v in enumerate(ways) if k not in seen and not v["link"] and v["limit"] == base
                   and abs(v["geom"][0][0] - end[0]) < 1e-4 and _dist(v["geom"][0], end) < 0.5]
            if not nxt:
                break
            seen.add(nxt[0]); cur = ways[nxt[0]]; tail = cur["geom"]
        ways = [v for k, v in enumerate(ways) if k not in seen] + [q for q in pieces if len(q["geom"]) >= 2]
        log(f"override {limit} (was {base}) applied to {total:.0f} m from {lat:.5f},{lon:.5f}")
    return ways


def load_overrides(path):
    if not path or not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [o for o in json.load(f) if isinstance(o, dict) and not o.get("disabled")]


def build(data, overrides=()):
    ways = []
    for w in data["elements"]:
        if w.get("type") != "way" or not w.get("geometry") or len(w["geometry"]) < 2:
            continue
        t = w.get("tags", {})
        limit = limit_of(t)
        if limit is None or not (limit == 0 or 5 <= limit <= 130):
            continue
        hw = t.get("highway", "").removesuffix("_link")
        ways.append({"limit": limit, "urban": limit <= 60 or hw in URBAN_HW,
                     "link": t.get("highway", "").endswith("_link"),
                     "geom": [(g["lat"], g["lon"]) for g in w["geometry"]]})
    if overrides:
        ways = apply_overrides(ways, overrides)
    lines, kept = [], 0
    for w in ways:
        limit, urban, geom = w["limit"], w["urban"], w["geom"]
        k = math.cos(math.radians(geom[0][0])) * 111320.0
        pts = [(round(la * 1e5), round(lo * 1e5), lo * k, la * 110540.0) for la, lo in geom]
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
    ap.add_argument("--overrides", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "sign_overrides.json"),
                    help="signs photographed by the user (default data/sign_overrides.json)")
    a = ap.parse_args()
    if a.input:
        with open(a.input, encoding="utf-8") as f:
            data = json.load(f)
    else:
        data = fetch()
    lines = build(data, load_overrides(a.overrides))
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
