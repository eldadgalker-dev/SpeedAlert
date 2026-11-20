# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
"""Fetch Israeli speed and red-light cameras from OpenStreetMap and build data/cameras.json.

Cameras come from speed_camera nodes, nodes tagged enforcement=traffic_signals/red_light_camera,
and the device nodes of enforcement relations (maxspeed -> speed, traffic_signals -> red light).

Source: Overpass API (OSM, ODbL). For each camera the speed limit is taken from
its own maxspeed tag, else from the nearest road (within 40 m) that has maxspeed.

Second source (user request 2026-11-20): the camera map of mitsu.co.il ("מיצו בדרכים"), a
Google My Maps layer read as KML. It gives each site's coordinates, heading, limit, type and
status. Active speed, red-light-and-speed, rail-crossing and average-speed sites are used;
scarecrows, bus-lane, security and inactive sites are not. A mitsu site replaces OSM cameras
within [SAME_M] of it (its limit and heading are checked by hand). When the map cannot be read
the OSM list is used alone and the run says so.

Usage: python tools/fetch_cameras.py [--out data/cameras.json]
"""
import argparse, json, math, sys, time, urllib.parse, urllib.request
import xml.etree.ElementTree as ET
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


MITSU_KML = "https://www.google.com/maps/d/kml?mid=1bspXgBYQ2GWj_6rDdGrJhG81RQConHHm&forcekml=1"
MITSU_ID = 9_000_000_000  # added to mitsu ids, which are small numbers, so they never meet an OSM node id
SAME_M = 60
KML = "{http://www.opengis.net/kml/2.2}"


def fetch_mitsu():
    """The KML text of the mitsu camera map, or None (the OSM list is then used alone)."""
    for attempt in range(3):
        try:
            req = urllib.request.Request(MITSU_KML, headers={"User-Agent": "Mozilla/5.0 (SpeedAlert personal app)"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:
            print(f"mitsu attempt {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(10)
    return None


def mitsu_kind(camera_type, status):
    """Our kind for a mitsu site, or None when it is not used."""
    if "לא פעילה" in status or "דחליל" in camera_type:
        return None
    if "ממוצעת" in camera_type:
        return "average"
    if "מחסום מעבר רכבת" in camera_type:
        return "red_light"
    if "מהירות" in camera_type:       # "מצלמת מהירות", "מצלמת אור אדום ומהירות"
        return "speed"
    return None                        # bus lane, security


def parse_mitsu(kml):
    """Cameras from the mitsu KML (bytes); only the ExtendedData fields are read."""
    out = []
    root = ET.fromstring(kml)
    for p in root.iter(KML + "Placemark"):
        d = {e.get("name"): (e.findtext(KML + "value") or "").strip() for e in p.iter(KML + "Data")}
        kind = mitsu_kind(d.get("camera_type", ""), d.get("camera_status", ""))
        if kind is None:
            continue
        try:
            lat, lon = float(d["latitude"]), float(d["longitude"])
            mid = int(float(d["id"]))
        except (KeyError, ValueError):
            continue
        if not (29.4 <= lat <= 33.4 and 34.2 <= lon <= 35.95):
            continue
        try:
            heading = float(d.get("heading", ""))
        except ValueError:
            heading = None
        try:
            limit = int(float(d.get("speed_limit_kmph", "")))
        except ValueError:
            limit = None
        if limit is not None and not 20 <= limit <= 130:
            limit = None
        name = (p.findtext(KML + "name") or "")
        out.append({"id": MITSU_ID + mid, "lat": round(lat, 6), "lon": round(lon, 6),
                    "limit": limit, "limitSource": "mitsu" if limit is not None else None,
                    "direction": heading, "urban": "עירונית" in name and "בינעירונית" not in name,
                    "kind": kind, "name": d.get("description") or None})
    return out


def flat_m(a, b):
    k = math.cos(math.radians(a["lat"])) * 111320.0
    return math.hypot((a["lon"] - b["lon"]) * k, (a["lat"] - b["lat"]) * 110540.0)


def merge(osm, mitsu):
    """OSM cameras, with every one within [SAME_M] of a mitsu site replaced by that site."""
    mitsu = list({m["id"]: m for m in mitsu}.values())   # a site listed twice (review 2026-11-20)
    keep = [c for c in osm if all(flat_m(c, m) > SAME_M for m in mitsu)]
    return keep + mitsu


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
    osm_count = len(cams)
    kml = fetch_mitsu()
    mitsu = []
    if kml is None:
        print("::warning::mitsu map could not be read; OSM cameras only")
    else:
        try:
            mitsu = parse_mitsu(kml)
        except ET.ParseError as e:
            print(f"::warning::mitsu map is not KML ({e}); OSM cameras only")
    cams = merge(cams, mitsu)
    source = "OpenStreetMap contributors (ODbL), via Overpass API"
    if mitsu:
        source += "; mitsu.co.il camera map (מיצו בדרכים)"
    doc = {"source": source,
           "fetched": date.today().isoformat(), "count": len(cams), "cameras": cams}
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    known = sum(c["limit"] is not None for c in cams)
    red = sum(c["kind"] == "red_light" for c in cams)
    avg = sum(c["kind"] == "average" for c in cams)
    print(f"{len(cams)} cameras ({red} red-light, {avg} average), {known} with speed limit -> {a.out}; "
          f"OSM {osm_count}, mitsu {len(mitsu)}, OSM replaced by mitsu {osm_count + len(mitsu) - len(cams)}")


if __name__ == "__main__":
    main()
