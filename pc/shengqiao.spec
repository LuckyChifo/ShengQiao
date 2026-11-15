# PyInstaller spec: builds dist/ShengQiao/ShengQiao.exe (windowed, tray icon). Run on Windows:
#   pip install -r requirements.txt pyinstaller && pyinstaller pc/shengqiao.spec --noconfirm
import os
from PyInstaller.utils.hooks import collect_submodules, collect_all, copy_metadata

app = os.path.join(SPECPATH, 'app')

# 客户端窗口：pywebview + pythonnet（WebView2 的 .NET 封装），带上它们的 DLL 和数据文件
wv_datas, wv_bins, wv_hidden = [], [], []
for pkg in ('webview', 'pythonnet', 'clr_loader'):
    d, b, h = collect_all(pkg)
    wv_datas += d; wv_bins += b; wv_hidden += h

license_metadata = copy_metadata('lameenc') + copy_metadata('pystray')

a = Analysis(
    [os.path.join(app, 'server.py')],
    pathex=[app],
    binaries=wv_bins,
    datas=wv_datas + license_metadata + [
           (os.path.join(SPECPATH, 'THIRD-PARTY-NOTICES.txt'), '.'),
           (os.path.join(app, 'web'), 'web'), (os.path.join(app, 'shengqiao.ico'), '.'),
           (os.path.join(app, 'lib', 'rnnoise.dll'), 'lib'), (os.path.join(app, 'lib', 'RNNOISE-LICENSE.txt'), 'lib'),
           (os.path.join(app, 'lib', 'opus.dll'), 'lib'), (os.path.join(app, 'lib', 'OPUS-LICENSE.txt'), 'lib')],
    hiddenimports=['tray', 'mic', 'capture', 'selftest', 'denoise', 'window', 'usbmux', 'netinfo', 'pystray._win32', 'PIL.Image', 'clr']
                  + wv_hidden + collect_submodules('zeroconf') + collect_submodules('soundcard'),
    excludes=['tkinter', 'matplotlib', 'pytest'],
)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name='ShengQiao', console=False,
          icon=os.path.join(app, 'shengqiao.ico'), upx=False)
coll = COLLECT(exe, a.binaries, a.datas, name='ShengQiao', upx=False)
