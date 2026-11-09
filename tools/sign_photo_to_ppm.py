# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
"""Turn a speed-sign photo into a test case for SignPhotosTest (PPM, long side at most 480 px, as on the phone).

Usage: python tools/sign_photo_to_ppm.py PHOTO VALUE [NAME] [--crop x0,y0,x1,y1]
Writes app/src/test/resources/signs/<VALUE>-<NAME>.ppm. Needs Pillow.
"""
import argparse, os
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("photo"); ap.add_argument("value", type=int); ap.add_argument("name", nargs="?")
ap.add_argument("--crop")
a = ap.parse_args()
im = Image.open(a.photo).convert("RGB")
if a.crop:
    im = im.crop(tuple(int(v) for v in a.crop.split(",")))
k = 480 / max(im.size)
if k < 1:
    im = im.resize((round(im.width * k), round(im.height * k)), Image.BILINEAR)
name = a.name or os.path.splitext(os.path.basename(a.photo))[0]
out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "signs", f"{a.value}-{name}.ppm")
os.makedirs(os.path.dirname(out), exist_ok=True)
im.save(out, format="PPM")
print(out, im.size)
