"""
Generate the profile avatar icons as Android vector drawables.

Hand-guessing SVG path strings produces wonky shapes, so every path here is
COMPUTED from real geometry: circles as two arcs, stars and polygons from
trigonometry, crescents as two circles under an even-odd fill. Each icon is
a single filled path on a 48x48 viewport with no strokes, which keeps it
crisp at avatar size and lets the app tint it.
"""
import math, os, textwrap

OUT = os.path.join(os.path.dirname(__file__), "av")
os.makedirs(OUT, exist_ok=True)

def n(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s != "-0" else "0"

def circle(cx, cy, r):
    """a full circle as two half arcs, which every renderer handles"""
    return (f"M{n(cx-r)},{n(cy)} a{n(r)},{n(r)} 0 1,0 {n(2*r)},0 "
            f"a{n(r)},{n(r)} 0 1,0 {n(-2*r)},0 Z")

def poly(pts):
    d = f"M{n(pts[0][0])},{n(pts[0][1])}"
    for x, y in pts[1:]:
        d += f" L{n(x)},{n(y)}"
    return d + " Z"

def star(cx, cy, outer, inner, points=5, rot=-90):
    pts = []
    for i in range(points * 2):
        r = outer if i % 2 == 0 else inner
        a = math.radians(rot + i * 180.0 / points)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(pts)

def rrect(x, y, w, h, r=0):
    if r <= 0:
        return poly([(x, y), (x+w, y), (x+w, y+h), (x, y+h)])
    return (f"M{n(x+r)},{n(y)} L{n(x+w-r)},{n(y)} A{n(r)},{n(r)} 0 0,1 {n(x+w)},{n(y+r)} "
            f"L{n(x+w)},{n(y+h-r)} A{n(r)},{n(r)} 0 0,1 {n(x+w-r)},{n(y+h)} "
            f"L{n(x+r)},{n(y+h)} A{n(r)},{n(r)} 0 0,1 {n(x)},{n(y+h-r)} "
            f"L{n(x)},{n(y+r)} A{n(r)},{n(r)} 0 0,1 {n(x+r)},{n(y)} Z")

def streak(x1, y1, x2, y2, w1, w2):
    """a tapering bar from one point to another, for comet tails"""
    import math as _m
    a = _m.atan2(y2 - y1, x2 - x1) + _m.pi / 2
    dx, dy = _m.cos(a), _m.sin(a)
    return poly([(x1 + dx*w1/2, y1 + dy*w1/2), (x2 + dx*w2/2, y2 + dy*w2/2),
                 (x2 - dx*w2/2, y2 - dy*w2/2), (x1 - dx*w1/2, y1 - dy*w1/2)])

def ring(cx, cy, outer, inner):
    return circle(cx, cy, outer) + " " + circle(cx, cy, inner)

def ellipse(cx, cy, rx, ry):
    return (f"M{n(cx-rx)},{n(cy)} a{n(rx)},{n(ry)} 0 1,0 {n(2*rx)},0 "
            f"a{n(rx)},{n(ry)} 0 1,0 {n(-2*rx)},0 Z")

ICONS = {}

# --- the Ottoman set, for the Turkish library -------------------------------

# crescent and star: outer circle with an offset circle cut out of it
ICONS["crescent"] = (circle(21.5, 24, 15) + " " + circle(28, 24, 13)
                     + " " + star(32.5, 24, 5.8, 2.4), "evenOdd")

# tulip, the Ottoman motif: three petals over a stem
tulip = (
    f"M24,10 C20,14 17.5,18.5 17.5,23 C17.5,28 20.2,31 24,31 "
    f"C27.8,31 30.5,28 30.5,23 C30.5,18.5 28,14 24,10 Z "
    f"M15.5,16.5 C12.5,20 12,25 14.5,28.5 C16.5,31 19,31.5 20.5,30.5 "
    f"C17.5,28 16,22.5 15.5,16.5 Z "
    f"M32.5,16.5 C32,22.5 30.5,28 27.5,30.5 C29,31.5 31.5,31 33.5,28.5 "
    f"C36,25 35.5,20 32.5,16.5 Z "
    + rrect(22.8, 31, 2.4, 9)
)
ICONS["tulip"] = (tulip, None)

# crossed blades
def blade(angle):
    a = math.radians(angle)
    ca, sa = math.cos(a), math.sin(a)
    def t(x, y):
        return (24 + x * ca - y * sa, 24 + x * sa + y * ca)
    pts = [t(-15, -1.4), t(9, -1.4), t(15.5, 0), t(9, 1.4), t(-15, 1.4)]
    guard = [t(-9.5, -4.5), t(-7.5, -4.5), t(-7.5, 4.5), t(-9.5, 4.5)]
    pommel = [t(-15.5, -2.6), t(-12.5, -2.6), t(-12.5, 2.6), t(-15.5, 2.6)]
    return poly(pts) + " " + poly(guard) + " " + poly(pommel)
ICONS["swords"] = (blade(-35) + " " + blade(35), None)

# shield with a band
shield = (f"M24,7 L39,12 C39,26 33,36.5 24,41 C15,36.5 9,26 9,12 Z")
ICONS["shield"] = (shield + " " + rrect(22.6, 15, 2.8, 18) + " " + rrect(15, 22.6, 18, 2.8), "evenOdd")

# crown
crown = poly([(10, 34), (13, 15), (19, 23), (24, 12), (29, 23), (35, 15), (38, 34)])
ICONS["crown"] = (crown + " " + rrect(10, 35.5, 28, 4, 1.4), None)

# hanging lantern
lantern = (rrect(22.5, 6, 3, 4)
           + " " + rrect(16, 10, 16, 3, 1.2)
           + " " + f"M18,13 L30,13 C32.5,19 32.5,28 30,34 L18,34 C15.5,28 15.5,19 18,13 Z"
           + " " + rrect(17, 34, 14, 3, 1.2)
           + " " + rrect(22.5, 37, 3, 4.5, 1.2))
ICONS["lantern"] = (lantern, None)

# fez with a tassel
fez = ("M15.5,33 C15,27 16.5,21.5 17.8,17.5 L30.2,17.5 C31.5,21.5 33,27 32.5,33 Z"
       + " " + rrect(14, 33, 20, 3.6, 1.3)
       + " " + ellipse(24, 17.5, 6.2, 2.3)
       + " " + streak(24, 15.6, 33.5, 19.5, 2.2, 2.2)
       + " " + rrect(32.4, 19, 2.2, 9)
       + " " + circle(33.5, 29.5, 2.8))
ICONS["fez"] = (fez, None)

# --- cinema ----------------------------------------------------------------

# clapperboard
clap = (rrect(8, 20, 32, 19, 2)
        + " " + f"M8,18.5 L36,11 L39.5,17 L11.5,24.5 Z")
teeth = ""
for i in range(5):
    x = 11 + i * 6.2
    teeth += " " + poly([(x, 20.6), (x + 3.4, 19.6), (x + 4.6, 23.2), (x + 1.2, 24.2)])
ICONS["clapper"] = (clap + teeth, "evenOdd")

# film reel: a ring with five windows
reel = ring(24, 24, 16, 4.2)
for i in range(5):
    a = math.radians(-90 + i * 72)
    reel += " " + circle(24 + 10.2 * math.cos(a), 24 + 10.2 * math.sin(a), 3.6)
ICONS["reel"] = (reel, "evenOdd")

# vinyl record
ICONS["vinyl"] = (circle(24, 24, 16) + " " + circle(24, 24, 11.5) + " "
                  + circle(24, 24, 9.5) + " " + circle(24, 24, 5) + " "
                  + circle(24, 24, 1.6), "evenOdd")

# theatre masks, comedy beside tragedy
def mask(cx, smile):
    d = f"M{n(cx-8)},14 C{n(cx-9)},26 {n(cx-5)},36 {n(cx)},36 C{n(cx+5)},36 {n(cx+9)},26 {n(cx+8)},14 Z"
    d += " " + ellipse(cx - 3.2, 21, 1.5, 2.1) + " " + ellipse(cx + 3.2, 21, 1.5, 2.1)
    if smile:
        d += f" M{n(cx-4)},27 C{n(cx-2)},31 {n(cx+2)},31 {n(cx+4)},27 C{n(cx+2)},29.5 {n(cx-2)},29.5 {n(cx-4)},27 Z"
    else:
        d += f" M{n(cx-4)},31 C{n(cx-2)},27 {n(cx+2)},27 {n(cx+4)},31 C{n(cx+2)},28.5 {n(cx-2)},28.5 {n(cx-4)},31 Z"
    return d
ICONS["masks"] = (mask(15, True) + " " + mask(33, False), "evenOdd")

# --- adventure --------------------------------------------------------------

# compass rose
ICONS["compass"] = (star(24, 24, 17, 4.5, 4, -90) + " " + star(24, 24, 11, 3, 4, -45)
                    + " " + circle(24, 24, 2.6), "evenOdd")

# skeleton key
key = (ring(24, 13, 8.5, 3.4)
       + " " + rrect(22.3, 20, 3.4, 21)
       + " " + rrect(25.7, 28.5, 6, 3)
       + " " + rrect(25.7, 34.5, 8, 3))
ICONS["key"] = (key, "evenOdd")

# dagger
dagger = (poly([(24, 5), (27.4, 13), (27.4, 29), (20.6, 29), (20.6, 13)])
          + " " + rrect(15.5, 29, 17, 3.2, 1.2)
          + " " + rrect(22.4, 32.2, 3.2, 8, 1)
          + " " + circle(24, 41.5, 2.4))
ICONS["dagger"] = (dagger, None)

# lighthouse
light = (poly([(19, 18), (29, 18), (32, 38), (16, 38)])
         + " " + rrect(17.5, 14.5, 13, 3.4, 1)
         + " " + rrect(20, 8, 8, 6.5, 1.2)
         + " " + rrect(14, 38, 20, 3.4, 1.2)
         + " " + poly([(30.5, 9), (38, 6), (38, 10), (30.5, 12.5)])
         + " " + poly([(17.5, 9), (10, 6), (10, 10), (17.5, 12.5)]))
ICONS["lighthouse"] = (light, None)

# comet
ICONS["comet"] = (star(32, 15, 8.5, 3.4)
                  + " " + streak(27, 20, 9, 38, 5.4, 0.8)
                  + " " + streak(31, 25.5, 19, 37.5, 3.4, 0.8)
                  + " " + streak(21.5, 16, 9.5, 28, 3.4, 0.8), None)

# mountain peaks under a moon
ICONS["mountains"] = (poly([(4, 38), (18, 16), (26, 28), (31, 21), (44, 38)])
                      + " " + circle(33, 12, 5) + " " + circle(36, 11, 4.2), "evenOdd")

# a geometric owl
# the tufts are part of the head outline, not shapes floating beside it;
# even-odd then nests eye ring over socket over pupil
owl = ("M11,17 L13,8 L20.5,13.5 C22.5,12.8 25.5,12.8 27.5,13.5 L35,8 L37,17 "
       "C38.3,19.7 39,22.7 39,26 C39,34 32.3,41 24,41 "
       "C15.7,41 9,34 9,26 C9,22.7 9.7,19.7 11,17 Z"
       + " " + circle(17.6, 23, 5.6) + " " + circle(17.6, 23, 2.2)
       + " " + circle(30.4, 23, 5.6) + " " + circle(30.4, 23, 2.2)
       + " " + poly([(24, 25.5), (26.6, 30.5), (21.4, 30.5)]))
ICONS["owl"] = (owl, "evenOdd")

# a galleon, for the sea-faring half of the Turkish epics
ship = ("M7,29 L41,29 C38.5,37 32,41 24,41 C16,41 9.5,37 7,29 Z"
        + " " + rrect(22.8, 6, 2.4, 23)
        + " " + poly([(26, 9), (37, 22), (26, 22)])
        + " " + poly([(22, 12), (22, 22), (12, 22)])
        + " " + poly([(25.2, 4.5), (33, 7), (25.2, 9.5)]))
ICONS["ship"] = (ship, None)

# an hourglass
ICONS["hourglass"] = (rrect(13, 7, 22, 3.4, 1.2)
                      + " " + rrect(13, 37.6, 22, 3.4, 1.2)
                      + " " + poly([(16, 10.4), (32, 10.4), (25.2, 24), (32, 37.6), (16, 37.6), (22.8, 24)]), None)

# a fortress
def crenel(x, w, top):
    """battlements: three merlons across the top of a tower"""
    d = ""
    step = w / 5.0
    for i in range(3):
        d += " " + rrect(x + i * 2 * step, top, step, 3.5)
    return d
fort = (rrect(7, 19, 10, 22) + crenel(7, 10, 15.5)
        + " " + rrect(31, 19, 10, 22) + crenel(31, 10, 15.5)
        + " " + rrect(17, 24, 14, 17) + crenel(17, 14, 20.5)
        + " " + f"M20.5,41 L20.5,33 C20.5,31 22,29.5 24,29.5 C26,29.5 27.5,31 27.5,33 L27.5,41 Z")
ICONS["fortress"] = (fort, "evenOdd")

# a tulip tea glass, which is what everyone is actually holding
tea = ("M17,13 L31,13 C31,19 28.2,22 28.2,25.5 C28.2,29 30.5,32 31,35 "
       "L17,35 C17.5,32 19.8,29 19.8,25.5 C19.8,22 17,19 17,13 Z"
       + " " + ellipse(24, 37.5, 12, 3)
       + " " + poly([(20, 10), (21.5, 5), (19, 2.5), (20.5, 8)])
       + " " + poly([(27.5, 10), (29, 5), (26.5, 2.5), (28, 8)]))
ICONS["tea"] = (tea, None)

TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Profile avatar: {name}.
     An original flat mark, computed geometry rather than traced artwork, on
     a 48x48 grid. Tinted by the app, so the colour is set here to white. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:fillColor="#FFFFFFFF"{fill}
        android:pathData="{d}" />
</vector>
'''

for name, (d, fill) in ICONS.items():
    ft = f'\n        android:fillType="{fill}"' if fill else ""
    open(os.path.join(OUT, f"av_{name}.xml"), "w").write(
        TEMPLATE.format(name=name, d=d, fill=ft))

print(f"generated {len(ICONS)} avatars:")
print("  " + ", ".join(sorted(ICONS)))
