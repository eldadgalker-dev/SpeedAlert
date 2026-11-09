# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
"""Refresh the urban bus lanes with enforcement cameras in data/buslanes.json from data.gov.il.

Source: Ministry of Transport, "נתיבי תחבורה ציבורית קיימים" on data.gov.il (CKAN datastore,
resource a853e465-b435-445c-b38b-26fadd795df5, licence CC-BY). Only lanes whose ENFORCMENT is
"מצלמה" or "שילוט ומצלמה" are kept. Each lane: name, city, start and end (WGS84, the direction
of travel is start to end) and its hours per day type (Sun-Thu, Friday, Saturday; hours as
fractions, two windows at most; an empty list means no enforcement that day).

The interurban sections ("sections", gov.il camera page) are kept as they are: data.gov.il does
not mark them as enforced. Older app versions read only "sections", so the new lanes go to
"lanes" and never reach them.

Checks: at least MIN_LANES lanes, every point inside Israel, sane hours; any doubt exits non-zero
and the current file stays. Needs pyproj (ITM -> WGS84).

Usage: python tools/fetch_buslanes.py [--out data/buslanes.json] [--base data/buslanes.json]
"""
import argparse, json, sys, time, urllib.parse, urllib.request

from pyproj import Transformer

API = "https://data.gov.il/api/3/action/datastore_search"
RESOURCE = "a853e465-b435-445c-b38b-26fadd795df5"
UA = "SpeedAlert-data/1.0 (https://github.com/eldadgalker-dev/SpeedAlert)"
ENFORCED = ("מצלמה", "שילוט ומצלמה")
MIN_LANES = 60
ITM = Transformer.from_crs("EPSG:2039", "EPSG:4326", always_xy=True)


def records():
    out, offset = [], 0
    while True:
        q = urllib.parse.urlencode(dict(resource_id=RESOURCE, limit=1000, offset=offset))
        for attempt in range(4):
            try:
                req = urllib.request.Request(f"{API}?{q}", headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=90) as r:
                    res = json.load(r)["result"]
                break
            except Exception as e:  # noqa: BLE001 - network, retried
                print(f"fetch offset {offset}: {e}", file=sys.stderr)
                time.sleep(10 * (attempt + 1))
        else:
            raise SystemExit("could not fetch data.gov.il")
        out += res["records"]
        offset += len(res["records"])
        if not res["records"] or offset >= res.get("total", 0):
            return out


def window(rec, a):
    f, t = rec.get(f"FRHR_{a}_F"), rec.get(f"TOHR_{a}_F")
    if f is None or t is None:
        return None
    f, t = float(f), float(t)
    if not (0 <= f < t <= 24):
        raise SystemExit(f"bad hours {a} {f}-{t} in {rec.get('PTLNAME')}")
    return [round(f, 2), round(t, 2)]


def hours(rec, day):
    keys = (day,) if day == "SAT" else (day, f"{day}_2")
    return [w for w in (window(rec, k) for k in keys) if w]


def point(e, n):
    lon, lat = ITM.transform(float(e), float(n))
    if not (29.4 <= lat <= 33.4 and 34.2 <= lon <= 35.9):
        raise SystemExit(f"point outside Israel: {lat},{lon}")
    return [round(lat, 6), round(lon, 6)]


def lane(rec):
    # LAT_* hold the ITM northing and LON_* the easting (field names of the source).
    return {
        "name": (rec.get("PTLNAME") or rec.get("STREETNAME") or "").replace('""', '"').strip(),
        "city": (rec.get("AUTHORITY") or "").strip(),
        "from": point(rec["LON_START"], rec["LAT_START"]),
        "to": point(rec["LON_END"], rec["LAT_END"]),
        "wk": hours(rec, "WK"), "fri": hours(rec, "FRI"), "sat": hours(rec, "SAT"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/buslanes.json")
    ap.add_argument("--base", default="data/buslanes.json", help="file whose interurban sections are kept")
    a = ap.parse_args()
    recs = records()
    lanes = sorted((lane(r) for r in recs if r.get("ENFORCMENT") in ENFORCED),
                   key=lambda x: (x["city"], x["name"], x["from"]))
    if len(lanes) < MIN_LANES:
        raise SystemExit(f"only {len(lanes)} enforced lanes of {len(recs)} records")
    base = json.load(open(a.base, encoding="utf-8"))
    out = {
        "source": base["source"],
        "hours": base["hours"],
        "sections": base["sections"],
        "lanes_source": "Ministry of Transport, data.gov.il dataset nataz_kayam (CC-BY), lanes with enforcement cameras; "
                        "direction of travel from 'from' to 'to'; hours per day type (wk Sun-Thu, fri, sat), holidays not marked",
        "lanes": lanes,
    }
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("{\n")
        for k in ("source", "hours", "lanes_source"):
            f.write(f' {json.dumps(k)}: {json.dumps(out[k], ensure_ascii=False)},\n')
        for k in ("sections", "lanes"):
            f.write(f' {json.dumps(k)}: [\n')
            f.write(",\n".join("  " + json.dumps(x, ensure_ascii=False) for x in out[k]))
            f.write("\n ]" + (",\n" if k == "sections" else "\n"))
        f.write("}\n")
    print(f"{len(lanes)} enforced urban lanes of {len(recs)} records, {len(base['sections'])} interurban sections kept")


if __name__ == "__main__":
    main()
