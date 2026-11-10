"""App icon drawn in code (tray icon and packaging icons share it)."""

from __future__ import annotations


def icon_image(size: int = 128):
    """3x3 deck grid with a lit center key, matching the Android launcher icon."""
    from PIL import Image, ImageDraw

    scale = 4  # supersample for smooth corners
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.22), fill=(16, 17, 20, 255))
    cell = s * 0.2
    gap = s * 0.07
    start = (s - (3 * cell + 2 * gap)) / 2
    for r in range(3):
        for c in range(3):
            x = start + c * (cell + gap)
            y = start + r * (cell + gap)
            color = (198, 243, 107, 255) if (r, c) == (1, 1) else (74, 80, 94, 255)
            d.rounded_rectangle((x, y, x + cell, y + cell), radius=int(cell * 0.28), fill=color)
    return img.resize((size, size), Image.LANCZOS)
