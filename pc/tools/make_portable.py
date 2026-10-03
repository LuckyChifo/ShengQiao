"""Build the portable Windows folder (embedded-Python launcher variant) from this repo.

    python pc/tools/make_portable.py OUT_DIR [--wheels DIR]

Downloads cp312/win_amd64 wheels with pip (unless --wheels is given), unpacks them into
OUT_DIR/ShengQiao/deps, copies the app and launcher scripts with Windows line endings.
On first run start.bat downloads the official embeddable Python 3.12 into ./runtime.
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys
import zipfile

PC = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(PC)
REQS = ['soundcard', 'numpy', 'cffi', 'qrcode', 'lameenc', 'PyAudioWPatch', 'colorama', 'pystray', 'Pillow', 'zeroconf']
SKIP_WHEELS = ('python_xlib',)          # pystray's Linux-only dependency


def crlf(src, dst, bom=False):
    with open(src, 'r', encoding='utf-8') as f:
        text = f.read().replace('\r\n', '\n').replace('\n', '\r\n')
    with open(dst, 'w', encoding='utf-8-sig' if bom else 'utf-8', newline='') as f:
        f.write(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out')
    ap.add_argument('--wheels')
    a = ap.parse_args()
    wheels = a.wheels
    if not wheels:
        wheels = os.path.join(a.out, '_wheels')
        os.makedirs(wheels, exist_ok=True)
        subprocess.check_call([sys.executable, '-m', 'pip', 'download', '--quiet', '--platform', 'win_amd64',
                               '--python-version', '3.12', '--only-binary=:all:', '--implementation', 'cp',
                               '-d', wheels] + REQS)
    dst = os.path.join(a.out, 'ShengQiao')
    shutil.rmtree(dst, ignore_errors=True)
    os.makedirs(os.path.join(dst, 'deps'))
    for w in sorted(glob.glob(os.path.join(wheels, '*.whl'))):
        name = os.path.basename(w)
        if name.startswith(SKIP_WHEELS):
            continue
        if not (name.endswith('none-any.whl') or 'cp312-win_amd64' in name):
            raise SystemExit('unexpected wheel tag: ' + name)
        with zipfile.ZipFile(w) as z:
            z.extractall(os.path.join(dst, 'deps'))
    for root, dirs, files in os.walk(os.path.join(dst, 'deps')):
        for d in list(dirs):
            if d in ('tests', '__pycache__'):
                shutil.rmtree(os.path.join(root, d))
                dirs.remove(d)
        for f in files:
            if f.endswith('.lib') and 'numpy' in root:
                os.remove(os.path.join(root, f))
    shutil.copytree(os.path.join(PC, 'app'), os.path.join(dst, 'app'),
                    ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    crlf(os.path.join(PC, 'app', 'python312._pth'), os.path.join(dst, 'app', 'python312._pth'))
    crlf(os.path.join(PC, 'start.bat'), os.path.join(dst, 'start.bat'))
    for bat in ('firewall.bat', 'selftest.bat', 'debug.bat'):
        crlf(os.path.join(PC, bat), os.path.join(dst, bat))
    crlf(os.path.join(PC, 'README-Windows.md'), os.path.join(dst, 'README.txt'), bom=True)
    print('built', dst)


if __name__ == '__main__':
    main()
