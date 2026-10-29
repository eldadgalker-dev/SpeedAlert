# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
"""Split data/roads.txt into tiles of TILE degrees (data/roads/<lat>_<lon>.txt) plus index.txt.

The app keeps only the tiles around the car in memory and downloads only tiles whose sha256 in
index.txt changed (user request 2026-10-29: 82 MB of memory with the whole table loaded).
Each tile holds the pieces of road whose segments come within MARGIN degrees of it, in the
roads.txt line format (limit urban lat lon dlat dlon ...), so a lookup needs only one tile.

Usage: python tools/tile_roads.py [data/roads.txt] [data/roads]
"""
import hashlib, math, os, sys

TILE = 0.1          # degrees, about 11 km
MARGIN = 0.0005     # degrees, about 55 m (> the app's 20 m match distance)
SCALE = 100000      # coordinates in roads.txt are degrees * 1e5


def tiles_of(a, b):
    """Tiles whose MARGIN-expanded box the segment's box touches."""
    la0, la1 = sorted((a[0], b[0])); lo0, lo1 = sorted((a[1], b[1]))
    t = TILE * SCALE; m = MARGIN * SCALE
    ys = range(math.floor((la0 - m) / t), math.floor((la1 + m) / t) + 1)
    xs = range(math.floor((lo0 - m) / t), math.floor((lo1 + m) / t) + 1)
    return [(y, x) for y in ys for x in xs]


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "data/roads.txt"
    out = sys.argv[2] if len(sys.argv) > 2 else "data/roads"
    pieces = {}   # tile -> list of lines
    header = ""
    with open(src, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                header = header or line
                continue
            n = [int(v) for v in line.split()]
            if len(n) < 6 or len(n) % 2:
                continue
            limit, urban = n[0], n[1]
            pts = [(n[2], n[3])]
            for i in range(4, len(n), 2):
                pts.append((pts[-1][0] + n[i], pts[-1][1] + n[i + 1]))
            runs = {}   # tile -> list of runs (lists of points) of consecutive segments
            for a, b in zip(pts, pts[1:]):
                for t in tiles_of(a, b):
                    r = runs.setdefault(t, [])
                    if r and r[-1][-1] == a:
                        r[-1].append(b)
                    else:
                        r.append([a, b])
            for t, rs in runs.items():
                for r in rs:
                    d = [f"{limit} {urban} {r[0][0]} {r[0][1]}"]
                    d += [f"{q[0] - p[0]} {q[1] - p[1]}" for p, q in zip(r, r[1:])]
                    pieces.setdefault(t, []).append(" ".join(d))
    os.makedirs(out, exist_ok=True)
    keep = set()
    index = []
    for (y, x), lines in sorted(pieces.items()):
        name = f"{y}_{x}"
        data = (header + "\n".join(lines) + "\n").encode("utf-8")
        path = os.path.join(out, name + ".txt")
        old = open(path, "rb").read() if os.path.exists(path) else None
        if old != data:
            with open(path, "wb") as f:
                f.write(data)
        keep.add(name + ".txt")
        index.append(f"{name} {hashlib.sha256(data).hexdigest()} {len(data)}")
    for f in os.listdir(out):
        if f.endswith(".txt") and f != "index.txt" and f not in keep:
            os.remove(os.path.join(out, f))
    with open(os.path.join(out, "index.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# roads tiles of {TILE} degrees: name sha256 bytes\n" + "\n".join(index) + "\n")
    total = sum(int(l.split()[2]) for l in index)
    print(f"{len(index)} tiles, {total} bytes, largest {max(int(l.split()[2]) for l in index)}")


if __name__ == "__main__":
    main()
