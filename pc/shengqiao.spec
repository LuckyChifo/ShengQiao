# PyInstaller spec: builds dist/ShengQiao/ShengQiao.exe (windowed, tray icon). Run on Windows:
#   pip install -r requirements.txt pyinstaller && pyinstaller pc/shengqiao.spec --noconfirm
import os
from PyInstaller.utils.hooks import collect_submodules

app = os.path.join(SPECPATH, 'app')

a = Analysis(
    [os.path.join(app, 'server.py')],
    pathex=[app],
    datas=[(os.path.join(app, 'web'), 'web'), (os.path.join(app, 'shengqiao.ico'), '.')],
    hiddenimports=['tray', 'mic', 'capture', 'selftest', 'pystray._win32', 'PIL.Image']
                  + collect_submodules('zeroconf') + collect_submodules('soundcard'),
    excludes=['tkinter', 'matplotlib', 'pytest'],
)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name='ShengQiao', console=False,
          icon=os.path.join(app, 'shengqiao.ico'), upx=False)
coll = COLLECT(exe, a.binaries, a.datas, name='ShengQiao', upx=False)
