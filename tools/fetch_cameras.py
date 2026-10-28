# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
"""Fetch Israeli speed and red-light cameras from OpenStreetMap and build data/cameras.json.

Cameras come from speed_camera nodes, nodes tagged enforcement=traffic_signals/red_light_camera,
and the device nodes of enforcement relations (maxspeed -> speed, traffic_signals -> red light).

Source: Overpass API (OSM, ODbL). For each camera the speed limit is taken from
its own maxspeed tag, else from the nearest road (within 40 m) that has maxspeed.

Usage: python tools/fetch_cameras.py [--out data/cameras.json]
"""
import argparse, json, math, sys, time, urllib.parse, urllib.request
from datetime import date

MIRRORS = [  # Germany/EU hosts only
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
QUERY = """[out:json][timeout:180][bbox:29.4,34.2,33.4,35.95];
rel["type"="enforcement"]["enforcement"~"^(maxspeed|traffic_signals)$"]->.e;
.e out body;
node(r.e:"device")->.d;
node["highway"="speed_camera"]->.s;
node["enforcement"~"^(traffic_signals|red_light_camera)$"]->.r;
(.s; .r; .d;)->.c;
.c out body;
way(around.c:40)["highway"]["maxspeed"];
out tags geom;"""


def fetch():
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    delay = 10
    for attempt in range(8):
        url = MIRRORS[attempt % len(MIRRORS)]
        try:
            req = urllib.request.Request(url, body, {"User-Agent": "SpeedAlert/0.1 (personal app)",
                                                     "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=240) as r:
                return json.load(r)
        except Exception as e:  # busy server, timeout, bad JSON
            print(f"attempt {attempt + 1} {url}: {e}", file=sys.stderr)
            time.sleep(delay)
            delay = min(delay * 2, 120)
    sys.exit("all Overpass attempts failed")


def parse_speed(v):
    if not v:
        return None
    v = v.split(";")[0].strip().lower()
    if v.isdigit():
        return int(v)
    if v.endswith("mph") and v[:-3].strip().isdigit():
        return round(int(v[:-3]) * 1.609)
    return None


def seg_dist(lat, lon, a, b):
    """Metres from point to segment a-b (equirectangular, fine for < 1 km)."""
    k = math.cos(math.radians(lat)) * 111320.0
    ax, ay = (a["lon"] - lon) * k, (a["lat"] - lat) * 110540.0
    bx, by = (b["lon"] - lon) * k, (b["lat"] - lat) * 110540.0
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx, ay + t * dy)


URBAN_HW = {"residential", "living_street", "unclassified", "tertiary", "service"}


def build(data):
    nodes = list({e["id"]: e for e in data["elements"] if e["type"] == "node"}.values())
    rels = {}  # device node id -> enforcement relation tags
    for r in data["elements"]:
        if r["type"] == "relation":
            for m in r.get("members", []):
                if m["type"] == "node" and m.get("role") == "device":
                    rels[m["ref"]] = r.get("tags", {})
    ways = [e for e in data["elements"] if e["type"] == "way" and e.get("geometry")]
    out = []
    for n in nodes:
        rel = rels.get(n["id"], {})
        t = {**rel, **n.get("tags", {})}
        if n.get("tags", {}).get("highway") == "speed_camera" or rel.get("enforcement") == "maxspeed":
            kind = "speed"
        else:
            kind = "red_light"
        limit, src, hw = parse_speed(t.get("maxspeed")), "camera", None
        best = None
        for w in ways:
            g = w["geometry"]
            d = min(seg_dist(n["lat"], n["lon"], g[i], g[i + 1]) for i in range(len(g) - 1)) if len(g) > 1 else 1e9
            if d <= 40 and (best is None or d < best[0]):
                best = (d, w)
        if best:
            hw = best[1]["tags"].get("highway")
            if limit is None:
                limit, src = parse_speed(best[1]["tags"].get("maxspeed")), "road"
        if limit is None:
            src = None
        direction = t.get("direction") or t.get("camera:direction")
        try:
            direction = float(direction) if direction is not None else None
        except ValueError:
            direction = None  # e.g. "forward" / "N" — ignored
        out.append({"id": n["id"], "lat": round(n["lat"], 6), "lon": round(n["lon"], 6),
                    "limit": limit, "limitSource": src, "direction": direction,
                    "urban": (limit is not None and limit <= 60) or hw in URBAN_HW,
                    "kind": kind, "name": t.get("name") or t.get("ref")})
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default="data/cameras.json")
    a = ap.parse_args()
    cams = build(fetch())
    doc = {"source": "OpenStreetMap contributors (ODbL), via Overpass API",
           "fetched": date.today().isoformat(), "count": len(cams), "cameras": cams}
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    known = sum(c["limit"] is not None for c in cams)
    red = sum(c["kind"] == "red_light" for c in cams)
    print(f"{len(cams)} cameras ({red} red-light), {known} with speed limit -> {a.out}")


if __name__ == "__main__":
    main()
