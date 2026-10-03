"""Render the 声桥 VU-meter icon at any size (PC web icons, iOS app icon, Windows .ico)."""
import math
import os
import sys

from PIL import Image, ImageDraw

WALNUT, PARCH, INK, RED, HUB, AMBER = (0x1c, 0x16, 0x12), (0xf3, 0xe3, 0xc3), (0x2b, 0x1d, 0x12), (0xa8, 0x30, 0x1c), (0x35, 0x2a, 0x22), (0xff, 0xb5, 0x47)


def icon(size, ss=4):
    W = size * ss
    im = Image.new('RGB', (W, W), WALNUT)
    d = ImageDraw.Draw(im)
    m = int(W * 0.13)
    face = [m, int(W * 0.19), W - m, int(W * 0.79)]
    d.rounded_rectangle(face, radius=int(W * 0.07), fill=PARCH)
    cx, cy = W / 2, face[3] + W * 0.08
    R = (face[3] - face[1]) * 0.95

    def pt(deg, r):
        a = math.radians(deg - 90)
        return (cx + r * math.cos(a), cy + r * math.sin(a))
    rr = R * 0.82
    box = [cx - rr, cy - rr, cx + rr, cy + rr]
    d.arc(box, start=-90 - 42, end=-90 + 18, fill=INK, width=max(2, int(W * 0.012)))
    d.arc(box, start=-90 + 18, end=-90 + 42, fill=RED, width=max(3, int(W * 0.04)))
    for deg in (-42, -28, -16, -6, 4, 18, 30, 42):
        d.line([pt(deg, rr), pt(deg, rr - W * 0.07)], fill=RED if deg > 18 else INK, width=max(2, int(W * 0.014)))
    d.line([(cx, cy), pt(12, R * 0.97)], fill=RED, width=max(3, int(W * 0.024)))
    d.rectangle([0, face[3], W, W], fill=WALNUT)
    d.pieslice([cx - W * 0.2, face[3] - W * 0.13, cx + W * 0.2, face[3] + W * 0.13], 180, 360, fill=HUB)
    lr = W * 0.035
    lx, ly = face[0] + W * 0.08, face[1] + W * 0.08
    d.ellipse([lx - lr, ly - lr, lx + lr, ly + lr], fill=AMBER)
    return im.resize((size, size), Image.LANCZOS)


if __name__ == '__main__':
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    web = os.path.join(root, 'pc', 'app', 'web')
    icon(180).save(os.path.join(web, 'icon-180.png'), optimize=True)
    icon(512).save(os.path.join(web, 'icon-512.png'), optimize=True)
    big = icon(1024)
    big.save(os.path.join(root, 'ios', 'ShengQiao', 'Assets.xcassets', 'AppIcon.appiconset', 'icon-1024.png'), optimize=True)
    icon(512).save(os.path.join(root, 'ios', 'ShengQiao', 'Assets.xcassets', 'NowPlaying.imageset', 'nowplaying.png'), optimize=True)
    big.convert('RGBA').save(os.path.join(root, 'pc', 'app', 'shengqiao.ico'),
                             sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print('icons written')
