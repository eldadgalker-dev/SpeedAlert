# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
"""Build data/penalties.json (speeding fines and points) from the consolidated law texts.

Sources: Hebrew Wikisource (the Open Law Book project, consolidated Israeli law; MediaWiki API,
open to automated reading):
  fines  - "צו התעבורה (עבירות קנס)", the rows for regulation 54(א) (items 75-78א);
  points - "תקנות התעבורה", the points table, rows for regulation 54(א) under their
           "N נקודות" headings.
Above the last fine band there is no fine in the order: a court summons.

The table is rebuilt from scratch and checked (contiguous bands from 1 km/h, sane amounts);
any parse doubt exits non-zero and the current file stays as it is.

Usage: python tools/fetch_penalties.py [--out data/penalties.json]
"""
import argparse, json, re, sys, time, urllib.parse, urllib.request

API = "https://he.wikisource.org/w/api.php"
FINES_PAGE = "צו התעבורה (עבירות קנס)"
POINTS_PAGE = "תקנות התעבורה"
UA = "SpeedAlert-data/1.0 (https://github.com/eldadgalker-dev/SpeedAlert)"


def page(title):
    """Wikitext and last revision time of a page."""
    q = urllib.parse.urlencode(dict(action="query", prop="revisions", titles=title, rvprop="content|timestamp",
                                    rvslots="main", format="json", formatversion=2))
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(f"{API}?{q}", headers={"User-Agent": UA}), timeout=90) as r:
                rev = json.load(r)["query"]["pages"][0]["revisions"][0]
                return rev["slots"]["main"]["content"], rev["timestamp"]
        except Exception as e:  # noqa: BLE001 - network, retried
            print(f"fetch {title}: {e}", file=sys.stderr)
            time.sleep(10 * (attempt + 1))
    raise SystemExit(f"could not fetch {title}")


def cells(row):
    return [re.sub(r"<[^>]+>|&nbsp;", " ", c).strip() for c in re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)]


NUM = r"(\d+)"
DASH = r"[־\-–\s]*"


def fine_bands(wt):
    """{'urban': [(from, to, fine)], 'interurban': [...]} from the 54(א) rows of the fines order."""
    out = {"urban": [], "interurban": []}
    for row in re.findall(r"<tr>(.*?)</tr>", wt, re.S):
        if "54(א)}}" not in row and "54(א)" not in row:
            continue
        c = cells(row)
        if len(c) < 4 or "קמ״ש מעל המותר" not in c[2] and 'קמ"ש מעל המותר' not in c[2]:
            continue
        text, amount = c[2], int(c[3].replace(",", ""))
        kind = "interurban" if "שאיננה עירונית" in text or "שאינה עירונית" in text else "urban"
        m = re.search(rf"מ{DASH}{NUM}\s*(?:ו?עד)\s*{NUM}", text) or re.search(rf"עד\s*{NUM}", text)
        if not m:
            raise SystemExit(f"unparsed fine row: {text}")
        lo, hi = (int(m.group(1)), int(m.group(2))) if m.lastindex == 2 else (1, int(m.group(1)))
        out[kind].append((lo, hi, amount))
    return out


def point_rules(wt):
    """[(kind, above, upto or None, points)] from the 54(א) rows of the points table."""
    rules, points = [], None
    for row in re.findall(r"<tr>(.*?)</tr>", wt, re.S):
        head = re.search(r"'''(\d+) נקודות'''", row)   # group heading row: <tr><th>'''10 נקודות'''</th></tr>
        if head:
            points = int(head.group(1)); continue
        if points is None or "54(א)" not in row:
            continue
        text = " ".join(cells(row))
        if "מעל למותר" not in text:
            continue
        kind = "interurban" if "שאינה עירונית" in text or "שאיננה עירונית" in text else "urban"
        m = re.search(rf"מעל\s*{NUM}\s*קמ[״\"]ש(?:\s*עד\s*{NUM})?", text)
        if not m:
            raise SystemExit(f"unparsed points row: {text}")
        rules.append((kind, int(m.group(1)), int(m.group(2)) if m.group(2) else None, points))
    return rules


def build(fines, rules):
    table = {}
    for kind in ("urban", "interurban"):
        bands = sorted(set(fines[kind]))
        if len(bands) < 2 or bands[0][0] != 1:
            raise SystemExit(f"{kind}: fine bands do not start at 1 km/h: {bands}")
        for (a, b, _), (c, _, _) in zip(bands, bands[1:]):
            if c != b + 1:
                raise SystemExit(f"{kind}: fine bands not contiguous: {bands}")
        if not all(100 <= f <= 10000 for _, _, f in bands):
            raise SystemExit(f"{kind}: fine out of range: {bands}")

        def pts(lo, hi):
            got = [p for k, above, upto, p in rules
                   if k == kind and lo > above and (upto is None or hi <= upto)]
            return max(got, default=0)

        rows = [{"from": a, "to": b, "fine": f, "points": pts(a, b)} for a, b, f in bands]
        last = bands[-1][1] + 1
        rows.append({"from": last, "to": 999, "fine": 0, "points": pts(last, 999), "court": True})
        if rows[-1]["points"] == 0:
            raise SystemExit(f"{kind}: no points found for the court band")
        table[kind] = rows
    return table


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", default="data/penalties.json")
    args = ap.parse_args()
    fwt, fts = page(FINES_PAGE)
    pwt, pts = page(POINTS_PAGE)
    table = build(fine_bands(fwt), point_rules(pwt))
    note = ("Fines (NIS) and points by km/h over the limit, rebuilt automatically every week. "
            f"Fines: צו התעבורה (עבירות קנס), תשס\"ב-2002, regulation 54(א) rows, Wikisource revision {fts}: "
            "https://he.wikisource.org/wiki/" + urllib.parse.quote(FINES_PAGE.replace(" ", "_")) + " . "
            f"Points: points table of תקנות התעבורה, תשכ\"א-1961, regulation 54(א) rows, Wikisource revision {pts}: "
            "https://he.wikisource.org/wiki/" + urllib.parse.quote(POINTS_PAGE.replace(" ", "_")) + " . "
            "Above the last fine band there is no fine in the order: court summons.")
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"note": note, **table}, f, ensure_ascii=False, indent=2)
        f.write("\n")
    for k in ("urban", "interurban"):
        print(k, [(r["from"], r["to"], r["fine"], r["points"]) for r in table[k]])


if __name__ == "__main__":
    main()
