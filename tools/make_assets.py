#!/usr/bin/env python3
"""Erzeugt die Luna-Map-Grafiken.

  python3 tools/make_assets.py

- assets/lunamap/icon.png               Mod-Icon (Axo vor Nachthimmel mit Mond)
- assets/lunamap/textures/gui/axo.png   Axo-Logo fuer den Einstellungs-Screen
- assets/lunamap/textures/gui/arrow.png Spielerpfeil fuer Minimap und Weltkarte
"""
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


ARROW = [
    "......OO......",
    ".....OPPO.....",
    ".....OPPO.....",
    "....OPPPPO....",
    "....OPPPPO....",
    "...OPPPPPPO...",
    "...OPPPPPPO...",
    "..OPPPPPPPPO..",
    "..OPPPPPPPPO..",
    ".OPPPPOOPPPPO.",
    ".OPPPO..OPPPO.",
    "OPPOO....OOPPO",
    "OOO........OOO",
]


def make_arrow():
    """16x16, Spitze zeigt nach oben (Norden)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    colors = {"O": NIGHT, "P": (246, 168, 200, 255)}
    for y, row in enumerate(ARROW):
        for x, ch in enumerate(row):
            if ch in colors:
                img.putpixel((x + 1, y + 1), colors[ch])
    # Mondgelbe Spitze
    for x, y in [(7, 2), (8, 2), (7, 3), (8, 3)]:
        img.putpixel((x, y), MOON)
    return img


def main():
    axo = axo_sprite()
    gui = RES / "assets/lunamap/textures/gui"
    gui.mkdir(parents=True, exist_ok=True)
    make_icon(axo).save(RES / "assets/lunamap/icon.png")
    axo.save(gui / "axo.png")
    make_arrow().save(gui / "arrow.png")
    print("ok")


if __name__ == "__main__":
    main()
