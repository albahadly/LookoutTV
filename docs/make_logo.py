#!/usr/bin/env python3
"""Draws the Lookout TV logo and writes the TV banner, launcher icons and README images.

The mark is an eye (the lookout) whose pupil is a camera lens. Colours are the app's own
palette (ui/Palette.kt). Everything is drawn at 4x and downsampled for smooth edges.

    python3 docs/make_logo.py        (run from the project root; needs Pillow and the Inter font)
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
FONT_BOLD = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"
FONT_MEDIUM = "/usr/share/fonts/opentype/inter/Inter-Medium.otf"

BG = (14, 17, 22)          # Palette.Background
BG2 = (27, 32, 39)         # Palette.Tile
TEXT = (242, 244, 247)     # Palette.TextBright
QUIET = (174, 182, 194)    # Palette.Quiet
ACCENT = (91, 155, 255)    # Palette.Accent
ACCENT_DEEP = (36, 92, 190)
LIVE = (255, 107, 95)      # Palette.Live

SS = 4  # supersampling


def vertical_gradient(size, top, bottom):
    mask = Image.linear_gradient("L").resize(size)  # 0 at the top, 255 at the bottom
    return Image.composite(Image.new("RGB", size, bottom), Image.new("RGB", size, top), mask)


def radial_disc(diameter, centre, edge, curve=1.6):
    """A disc shaded from [centre] to [edge], with transparency outside the circle."""
    g = Image.radial_gradient("L").resize((diameter, diameter))  # 0 at the centre, 255 at the edge
    g = g.point(lambda v: int(255 * (v / 255) ** curve))
    disc = Image.composite(Image.new("RGB", (diameter, diameter), edge), Image.new("RGB", (diameter, diameter), centre), g)
    mask = Image.new("L", (diameter, diameter), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, diameter - 1, diameter - 1], fill=255)
    disc.putalpha(mask)
    return disc


def draw_mark(canvas, cx, cy, r):
    """The eye-and-lens mark, centred at (cx, cy); r is the lens radius (already supersampled)."""
    d = ImageDraw.Draw(canvas)
    a = r * 2.0    # half width of the eye
    h = r * 1.15   # half height of the eye
    stroke = max(2, int(r * 0.11))
    # The almond is the intersection of two equal circles: radius big, centres k above/below.
    big = (a * a + h * h) / (2 * h)
    k = big - h
    left = math.degrees(math.atan2(-k, -a))   # angle of the left corner from the lower circle's centre
    right = math.degrees(math.atan2(-k, a))
    # Upper edge: arc of the circle centred below; lower edge: arc of the circle centred above.
    d.arc([cx - big, cy + k - big, cx + big, cy + k + big], start=left, end=right, fill=TEXT, width=stroke)
    d.arc([cx - big, cy - k - big, cx + big, cy - k + big], start=-right, end=-left, fill=TEXT, width=stroke)
    for sx in (-a, a):  # round the corners
        d.ellipse([cx + sx - stroke / 2, cy - stroke / 2, cx + sx + stroke / 2, cy + stroke / 2], fill=TEXT)
    # Iris with a radial shade, then lens rings, pupil and highlight.
    iris = radial_disc(int(2 * r), ACCENT, ACCENT_DEEP)
    canvas.paste(iris, (int(cx - r), int(cy - r)), iris)
    for f in (0.78, 0.56):
        rr = r * f
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=(28, 70, 150), width=max(1, int(r * 0.045)))
    pr = r * 0.4
    d.ellipse([cx - pr, cy - pr, cx + pr, cy + pr], fill=BG)
    hr = r * 0.13
    hx, hy = cx - r * 0.3, cy - r * 0.32
    d.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=TEXT)
    # Live dot at the eye's lower-right corner.
    lr = r * 0.17
    lx, ly = cx + a * 0.82, cy + h * 0.95
    d.ellipse([lx - lr, ly - lr, lx + lr, ly + lr], fill=LIVE)


def wordmark(canvas, x, y, size, tagline=None, max_width=None):
    d = ImageDraw.Draw(canvas)
    bold = ImageFont.truetype(FONT_BOLD, size)
    if max_width:  # shrink to fit
        while d.textlength("Lookout TV", font=bold) > max_width and size > 8:
            size -= 1
            bold = ImageFont.truetype(FONT_BOLD, size)
    d.text((x, y), "Lookout", font=bold, fill=TEXT)
    w = d.textlength("Lookout ", font=bold)
    d.text((x + w, y), "TV", font=bold, fill=ACCENT)
    if tagline:
        medium = ImageFont.truetype(FONT_MEDIUM, int(size * 0.3))
        d.text((x + size * 0.04, y + size * 1.22), tagline, font=medium, fill=QUIET)


def banner(width, height):
    W, H = width * SS, height * SS
    img = vertical_gradient((W, H), BG2, BG)
    draw_mark(img, W * 0.2, H * 0.5, H * 0.15)
    wordmark(img, W * 0.41, H * 0.3, int(H * 0.26), "Your cameras, on the big screen", max_width=W * 0.55)
    return img.resize((width, height), Image.LANCZOS)


def icon(size, margin=0.0):
    """A rounded square with the mark; [margin] is transparent padding (launcher icons need some)."""
    S = size * SS
    m = int(S * margin)
    inner = S - 2 * m
    img = vertical_gradient((inner, inner), BG2, BG)
    draw_mark(img, inner * 0.5, inner * 0.5, inner * 0.19)
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, inner - 1, inner - 1], radius=int(inner * 0.2), fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(img, (m, m), mask)
    return out.resize((size, size), Image.LANCZOS)


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)
    print(path.relative_to(ROOT), img.size)


if __name__ == "__main__":
    # TV banner: 320x180 dp; xhdpi is the reference density.
    for density, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        save(banner(int(160 * scale), int(90 * scale)), RES / f"drawable-{density}" / "banner.png")
    # Launcher icon (phones/tablets and some TV launchers): rounded, with the usual margin.
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(icon(px, margin=0.08), RES / f"mipmap-{density}" / "ic_launcher.png")
    # README images.
    save(banner(1280, 720), ROOT / "docs" / "banner.png")
    save(icon(512), ROOT / "docs" / "icon.png")
