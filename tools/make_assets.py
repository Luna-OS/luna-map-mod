#!/usr/bin/env python3
"""Erzeugt die Luna-Map-Grafiken.

  python3 tools/make_assets.py <pfad/zu/xaerominimap-*.jar>

- assets/lunamap/icon.png          Mod-Icon (Axo vor Nachthimmel mit Mond)
- assets/lunamap/textures/gui/axo.png   Axo-Logo fuer den Luna-Map-Screen
- resourcepacks/luna_theme/.../minimap_frame.png   Minimap-Rahmen in Luna-Farben
  (umgefaerbt aus dem Rahmen im Minimap-Jar, das deshalb als Argument noetig ist)
"""
import io
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"

# Luna-Palette
NIGHT = (17, 13, 40, 255)
NIGHT_2 = (33, 25, 72, 255)
OUTLINE = (91, 79, 158, 255)
LAVENDER = (168, 150, 232, 255)
MOON = (255, 236, 160, 255)

# Axo, Pixel fuer Pixel nach dem Vorbild-Screenshot
AXO = [
    "......p.......PPPPPPPP.......p....",
    ".......p....PPWWWWWWWWPP....p.....",
    "........p..PWWWWWWWWWWWWP..p......",
    ".........mmWWWWWWWWWWWWWWmm.......",
    ".....pp...PWWWWWWWWWWWWWWP...pp...",
    ".......ppmmWWWDWWWWWDWWWWmmpp.....",
    "..........PWWWDDWWWWDDWWWP........",
    ".........mmWWcWWWWWWWWcWWmm.......",
    "........p.PWWWWWWmmWWWWWWP.p......",
    ".......p...PWWWWWWWWWWWWP...p.....",
    "......p.....PPWWWWWWWWPP.....p....",
    "..............PPPPPPPP............",
    ".........PPP.PWWWWWWWWP...........",
    ".........PWPPPWWlWWlWWPP..........",
    "..........PWWWWWWWWWWWPWP.........",
    "...........PPPPWWWWWWPPWP.........",
    "...............PPPPPP.............",
]
AXO_COLORS = {
    "W": (251, 247, 240, 255),
    "P": OUTLINE,
    "p": (246, 168, 200, 255),
    "m": (229, 134, 178, 255),
    "D": (36, 28, 72, 255),
    "c": (246, 186, 150, 255),
    "l": (220, 215, 250, 255),
}


def axo_sprite():
    img = Image.new("RGBA", (len(AXO[0]), len(AXO)), (0, 0, 0, 0))
    for y, row in enumerate(AXO):
        for x, ch in enumerate(row):
            if ch in AXO_COLORS:
                img.putpixel((x, y), AXO_COLORS[ch])
    return img.crop(img.getbbox())


def crescent(size, color):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    r = size / 2
    for y in range(size):
        for x in range(size):
            cx, cy = x + 0.5 - r, y + 0.5 - r
            inner = (cx - r * 0.45) ** 2 + (cy + r * 0.25) ** 2
            if cx * cx + cy * cy <= r * r and inner > (r * 0.8) ** 2:
                img.putpixel((x, y), color)
    return img


def make_icon(axo):
    cells = 32
    img = Image.new("RGBA", (cells, cells), (0, 0, 0, 0))
    # Nacht-Quadrat mit leichtem Verlauf und abgerundeten Ecken
    corners = {(0, 0), (1, 0), (0, 1)}
    for y in range(cells):
        t = y / (cells - 1)
        col = tuple(int(NIGHT_2[i] * (1 - t) + NIGHT[i] * t) for i in range(3)) + (255,)
        for x in range(cells):
            if (min(x, cells - 1 - x), min(y, cells - 1 - y)) not in corners:
                img.putpixel((x, y), col)
    for sx, sy in [(4, 4), (9, 2), (15, 6), (5, 10), (27, 13)]:
        img.putpixel((sx, sy), LAVENDER)
    img.alpha_composite(crescent(9, MOON), (21, 2))
    img.alpha_composite(axo, ((cells - axo.width) // 2, 13))
    return img.resize((256, 256), Image.NEAREST)


def recolor_frame(src):
    """Graue Rahmenpixel -> Luna-Verlauf, halbtransparentes Weiss -> Lavendel."""
    stops = [(0, (14, 10, 34)), (45, (40, 30, 86)), (95, OUTLINE[:3]), (150, LAVENDER[:3]), (200, MOON[:3])]
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = src.getpixel((x, y))
            if a == 0:
                continue
            if a < 255 and r > 200:
                out.putpixel((x, y), (214, 200, 255, a))
                continue
            lum = (r + g + b) / 3
            for (l0, c0), (l1, c1) in zip(stops, stops[1:]):
                if lum <= l1:
                    t = (lum - l0) / (l1 - l0)
                    out.putpixel((x, y), tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3)) + (a,))
                    break
            else:
                out.putpixel((x, y), MOON[:3] + (a,))
    return out


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    axo = axo_sprite()
    (RES / "assets/lunamap/textures/gui").mkdir(parents=True, exist_ok=True)
    make_icon(axo).save(RES / "assets/lunamap/icon.png")
    axo.save(RES / "assets/lunamap/textures/gui/axo.png")

    with zipfile.ZipFile(sys.argv[1]) as jar:
        frame = Image.open(io.BytesIO(jar.read("assets/xaerobetterpvp/gui/minimap_frame.png"))).convert("RGBA")
    dst = RES / "resourcepacks/luna_theme/assets/xaerobetterpvp/gui/minimap_frame.png"
    dst.parent.mkdir(parents=True, exist_ok=True)
    recolor_frame(frame).save(dst)
    print("ok")


if __name__ == "__main__":
    main()
