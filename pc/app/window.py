"""声桥 · 电脑端客户端窗口。

控制页（web/pc.html）不再丢给浏览器，而是装进一个独立的桌面窗口（Windows 自带的 WebView2，
由 pywebview 驱动）。窗口跑在单独的子进程里：托盘、服务和窗口互不影响，关窗口只是缩回托盘。

    server.py --window URL      子进程入口（见 server.main）
    open_console(url)           父进程调用：窗口已开就把它提到最前，否则拉起一个
WebView2 或 pywebview 不可用时依次退回：Edge/Chrome 的“应用模式”窗口 → 默认浏览器。
"""
import os
import subprocess
import sys

from capture import log, IS_WINDOWS

TITLE = '声桥'
MUTEX = 'Local\\ShengQiao-window'
SIZE = (1080, 720)
MIN_SIZE = (850, 590)
LIGHT_BACKGROUND = '#f2f6f4'
DARK_BACKGROUND = '#101614'

_child = None


# ------------------------------------------------------------------ 父进程

def _find_window():
    import ctypes
    return ctypes.windll.user32.FindWindowW(None, TITLE)


def _focus(hwnd):
    import ctypes
    u = ctypes.windll.user32
    if u.IsIconic(hwnd):
        u.ShowWindow(hwnd, 9)                       # SW_RESTORE
    u.SetForegroundWindow(hwnd)


def child_command(url):
    if getattr(sys, 'frozen', False):
        return [sys.executable, '--window', url]
    exe = sys.executable
    if IS_WINDOWS and exe.lower().endswith('python.exe'):
        w = exe[:-10] + 'pythonw.exe'
        if os.path.exists(w):
            exe = w
    return [exe, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'server.py'), '--window', url]


def open_console(url):
    """打开（或提到最前）客户端窗口。"""
    global _child
    if not IS_WINDOWS:
        import webbrowser
        webbrowser.open(url)
        return
    if _child is not None and _child.poll() is None:
        hwnd = _find_window()
        if hwnd:
            _focus(hwnd)
        return                                     # 子进程还在启动，窗口马上就出来
    flags = 0x08000000 if hasattr(subprocess, 'CREATE_NO_WINDOW') else 0
    try:
        _child = subprocess.Popen(child_command(url), creationflags=flags, close_fds=True)
    except Exception as e:
        log('打开客户端窗口失败（%s），改用浏览器' % e)
        import webbrowser
        webbrowser.open(url)


def close_console():
    """退出声桥时一并关掉客户端窗口。"""
    if _child is not None and _child.poll() is None:
        try:
            _child.terminate()
        except Exception:
            pass


# ------------------------------------------------------------------ 子进程

def _icon_path():
    here = os.path.dirname(os.path.abspath(__file__))
    for base in (getattr(sys, '_MEIPASS', None), here):
        if base and os.path.exists(os.path.join(base, 'shengqiao.ico')):
            return os.path.join(base, 'shengqiao.ico')
    return None


def _system_dark():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Themes\Personalize') as key:
            return winreg.QueryValueEx(key, 'AppsUseLightTheme')[0] == 0
    except (ImportError, OSError):
        return False


def _style_window(window, dark, set_icon=False):
    """同步原生标题栏，保留 Windows 的移动、缩放和窗口按钮。"""
    try:
        import ctypes
        from ctypes import wintypes
        hwnd = int(window.native.Handle.ToInt64())
        if set_icon:
            path = _icon_path()
            if path:
                load_image = ctypes.windll.user32.LoadImageW
                load_image.argtypes = (wintypes.HINSTANCE, wintypes.LPCWSTR, wintypes.UINT,
                                       ctypes.c_int, ctypes.c_int, wintypes.UINT)
                load_image.restype = wintypes.HANDLE
                send_message = ctypes.windll.user32.SendMessageW
                send_message.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
                send_message.restype = ctypes.c_ssize_t
                for kind, size in ((1, 32), (0, 16)):
                    icon = load_image(None, path, 1, size, size, 0x10)       # IMAGE_ICON, LR_LOADFROMFILE
                    if icon:
                        send_message(hwnd, 0x80, kind, icon)                 # WM_SETICON
        set_attribute = ctypes.windll.dwmapi.DwmSetWindowAttribute
        set_attribute.argtypes = (wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD)
        background = DARK_BACKGROUND if dark else LIGHT_BACKGROUND
        caption = int.from_bytes(bytes.fromhex(background[1:]), 'little')    # COLORREF 0x00BBGGRR
        for attr, val in ((20, int(dark)), (35, caption)):
            value = ctypes.c_int(val)
            set_attribute(hwnd, attr, ctypes.byref(value), ctypes.sizeof(value))
    except Exception:
        pass


def _fallback(url):
    """没有 WebView2 / pywebview：用 Edge 或 Chrome 的应用模式（无地址栏），再不行就默认浏览器。"""
    import shutil
    import webbrowser
    cands = []
    for env in ('PROGRAMFILES(X86)', 'PROGRAMFILES', 'LOCALAPPDATA'):
        root = os.environ.get(env)
        if root:
            cands += [os.path.join(root, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
                      os.path.join(root, 'Google', 'Chrome', 'Application', 'chrome.exe')]
    cands += [shutil.which('msedge') or '', shutil.which('chrome') or '']
    for exe in cands:
        if exe and os.path.exists(exe):
            try:
                subprocess.Popen([exe, '--app=' + url, '--window-size=%d,%d' % SIZE])
                return
            except Exception:
                pass
    webbrowser.open(url)


def run_window(url, data_dir=None):
    """子进程入口：显示窗口，直到用户关掉。"""
    if IS_WINDOWS:
        try:
            import ctypes
            k32 = ctypes.windll.kernel32
            k32.CreateMutexW(None, False, MUTEX)
            if k32.GetLastError() == 183:           # 已经有一个窗口了
                hwnd = _find_window()
                if hwnd:
                    _focus(hwnd)
                return 0
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID('ShengQiao.Client')   # 任务栏单独成组、用我们的图标
        except Exception:
            pass
    try:
        import webview
    except Exception as e:
        log('没有 pywebview（%s），改用应用模式窗口' % e)
        _fallback(url)
        return 0
    try:
        dark = _system_dark()
        window = webview.create_window(TITLE, url, width=SIZE[0], height=SIZE[1], min_size=MIN_SIZE,
                                       background_color=DARK_BACKGROUND if dark else LIGHT_BACKGROUND,
                                       text_select=False)
        if IS_WINDOWS:
            def set_title_theme(dark):
                _style_window(window, dark is True)

            window.expose(set_title_theme)
            window.events.shown += lambda: _style_window(window, dark, set_icon=True)
        storage = os.path.join(data_dir, 'webview') if data_dir else None
        webview.start(gui='edgechromium' if IS_WINDOWS else None, private_mode=False, storage_path=storage)
    except Exception as e:
        log('客户端窗口启动失败（%s），改用应用模式窗口' % e)
        _fallback(url)
    return 0
