"""
Turn the generated artwork into shippable avatar resources.

The generator returns ~1250px, ~1.5MB images. An avatar is drawn at 124dp,
so each one is squared off, resized to 512 and re-encoded. Twenty-two at
full size would add 30MB to an APK that is already 48MB.
"""
import os, sys, glob
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "out")
DST = os.path.join(HERE, "ship")
SIDE = 512

os.makedirs(DST, exist_ok=True)
rows = []
for f in sorted(glob.glob(os.path.join(SRC, "av_*.png"))):
    key = os.path.basename(f)[3:-4]
    im = Image.open(f).convert("RGB")
    # square off from the centre in case the generator drifted off-square
    w, h = im.size
    side = min(w, h)
    im = im.crop(((w - side) // 2, (h - side) // 2,
                  (w + side) // 2, (h + side) // 2))
    im = im.resize((SIDE, SIDE), Image.LANCZOS)
    out = os.path.join(DST, f"art_{key}.png")
    im.save(out, "PNG", optimize=True)
    rows.append((key, os.path.getsize(f), os.path.getsize(out)))

if not rows:
    print("nothing to process yet")
    sys.exit(0)
print(f"{'icon':<12} {'generated':>10} {'shipped':>9}")
for k, a, b in rows:
    print(f"{k:<12} {a/1024:>9.0f}K {b/1024:>8.0f}K")
print(f"{'TOTAL':<12} {sum(r[1] for r in rows)/1048576:>8.1f}MB "
      f"{sum(r[2] for r in rows)/1048576:>7.1f}MB")
