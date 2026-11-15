"""Generate Windows, phone web and iOS icons from the desktop web artwork."""
from pathlib import Path

from PIL import Image


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    app = root / 'pc' / 'app'
    web = app / 'web'
    ios = root / 'ios' / 'ShengQiao' / 'Assets.xcassets'

    with Image.open(web / 'pc-icon.png') as source:
        desktop = source.convert('RGBA')
    desktop.save(app / 'shengqiao.ico', sizes=[(size, size) for size in (16, 24, 32, 48, 64, 128, 256)])

    # iOS and home-screen bookmarks apply their own mask and need opaque artwork.
    background = desktop.getpixel((desktop.width // 2, desktop.height - 1))[:3]
    artwork = Image.new('RGB', desktop.size, background)
    artwork.paste(desktop, mask=desktop.getchannel('A'))
    artwork.resize((180, 180), Image.Resampling.LANCZOS).save(web / 'icon-180.png', optimize=True)
    artwork.resize((1024, 1024), Image.Resampling.LANCZOS).save(ios / 'AppIcon.appiconset' / 'icon-1024.png', optimize=True)

    media = artwork.resize((512, 512), Image.Resampling.LANCZOS)
    media.save(web / 'icon-512.png', optimize=True)
    media.save(ios / 'NowPlaying.imageset' / 'nowplaying.png', optimize=True)
    print('icons written')
